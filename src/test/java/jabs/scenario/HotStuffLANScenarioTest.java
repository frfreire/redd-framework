package jabs.scenario;

import jabs.consensus.algorithm.HotStuff;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cenario executavel HotStuff (caminho honesto): finaliza, preenche metricas e e deterministico por semente. */
class HotStuffLANScenarioTest {
    private static final double DURACAO_S = 10.0;

    private static String roda(long seed, int n, Path csv) throws IOException {
        HotStuffLANScenario scenario = new HotStuffLANScenario("hotstuff-test", seed, n, DURACAO_S, HotStuffTestConfig.config());
        scenario.addMetricsLogger(csv.toString());
        scenario.run();
        assertEquals(n, scenario.getNetwork().getAllNodes().size());
        assertTrue(scenario.getMetrics().getBlockCount() > 0, "deve finalizar blocos");
        assertTrue(scenario.getMetrics().getAverageBlockFinalizationTime() > 0, "Tb medido do BlockFinalizationEvent");
        assertTrue(scenario.getMetrics().getAverageTrafficPerBlock() > 0, "Cb medido do BlockFinalizationEvent");
        HotStuffNode primeiro = (HotStuffNode) scenario.getNetwork().getAllNodes().get(0);
        for (Object o : scenario.getNetwork().getAllNodes()) {
            HotStuffNode no = (HotStuffNode) o;
            assertTrue(((HotStuff<?, ?>) no.getConsensusAlgorithm()).isStarted());
            // os nos podem diferir de no maximo uma altura no corte do tempo; o mais avancado estende o outro
            long dif = Math.abs(no.getConsensusAlgorithm().getCanonicalChainHead().getHeight()
                    - primeiro.getConsensusAlgorithm().getCanonicalChainHead().getHeight());
            assertTrue(dif <= 1, "alturas finais diferem em " + dif);
        }
        return semColunaHash(Files.readString(csv));
    }

    /**
     * Remove a coluna BlockHashCode (Object.hashCode de identidade do bloco, que varia por execucao: o mesmo criterio de
     * scripts/jabs/checar-determinismo.py, que ignora colunas *hash*). Todo o resto do CSV tem de ser identico.
     */
    private static String semColunaHash(String csv) {
        String[] linhas = csv.split("\n");
        int idx = -1;
        StringBuilder out = new StringBuilder();
        for (String linha : linhas) {
            if (linha.startsWith("#")) {
                out.append(linha).append('\n');
                continue;
            }
            String[] c = linha.split(",", -1);
            if (idx < 0) {
                for (int i = 0; i < c.length; i++) {
                    if (c[i].equals("BlockHashCode")) {
                        idx = i;
                    }
                }
                assertTrue(idx >= 0, "cabecalho com BlockHashCode");
            }
            StringBuilder l = new StringBuilder();
            for (int i = 0; i < c.length; i++) {
                if (i != idx) {
                    l.append(c[i]).append(',');
                }
            }
            out.append(l).append('\n');
        }
        return out.toString();
    }

    @Test
    void executaFinalizaEPreencheMetricas(@TempDir Path dir) throws IOException {
        for (int n : new int[]{4, 7}) {
            roda(1, n, dir.resolve("hotstuff-" + n + ".csv"));
        }
    }

    @Test
    void mesmaSementeMesmoCsv(@TempDir Path dir) throws IOException {
        for (int n : new int[]{4, 7}) {
            String a = roda(42, n, dir.resolve("a-" + n + ".csv"));
            String b = roda(42, n, dir.resolve("b-" + n + ".csv"));
            assertTrue(a.lines().count() > 1, "CSV com cabecalho e linhas");
            assertEquals(a, b, "n=" + n);
        }
    }

    @Test
    void configuracaoObrigatoriaEExigeQuatroNos() {
        assertThrows(IllegalArgumentException.class, () -> new HotStuffLANScenario("n3", 1, 3, 1.0, HotStuffTestConfig.config()));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffLANScenario("sem-config", 1, 4, 1.0, null));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffLANScenario("sem-duracao", 1, 4, 0.0, HotStuffTestConfig.config()));
    }
}
