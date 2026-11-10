package jabs.scenario;

import jabs.consensus.algorithm.HotStuff;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2c-3: (viii) sem crash a saida do {@link HotStuffLANScenario} e IDENTICA a do commit bd1914d (CSV de
 * referencia gerado ANTES da mudanca, mesma semente e config de teste; coluna BlockHashCode removida: hash de
 * identidade varia por execucao); e o conjunto explicito de nos em crash do cenario.
 */
class HotStuffLANScenarioCrashTest {

    private static String referencia(String nome) throws IOException {
        try (InputStream in = HotStuffLANScenarioCrashTest.class.getResourceAsStream("/hotstuff/" + nome)) {
            assertTrue(in != null, "recurso de referencia " + nome);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String roda(long seed, int n, double duracao, Set<Integer> crash, Path csv) throws IOException {
        HotStuffLANScenario sc = crash == null
                ? new HotStuffLANScenario("hotstuff-csv", seed, n, duracao, HotStuffTestConfig.config())
                : new HotStuffLANScenario("hotstuff-csv", seed, n, duracao, HotStuffTestConfig.config(), crash);
        sc.addMetricsLogger(csv.toString());
        sc.run();
        return Files.readString(csv);
    }

    private static String normaliza(String csvComHash) {
        // o CSV de referencia ja vem sem BlockHashCode e sem virgula final; o gerado e normalizado do mesmo modo
        String[] linhas = csvComHash.split("\r?\n"); // o logger grava CRLF
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
            }
            StringBuilder l = new StringBuilder();
            for (int i = 0; i < c.length; i++) {
                if (i != idx) {
                    if (l.length() > 0) {
                        l.append(',');
                    }
                    l.append(c[i]);
                }
            }
            out.append(l).append('\n');
        }
        return out.toString();
    }

    @Test
    void semCrashASaidaEIdenticaADoCommitBd1914d(@TempDir Path dir) throws IOException {
        for (int n : new int[]{4, 7}) {
            String esperado = normaliza(referencia("bd1914d-seed1-n" + n + "-30s.csv"));
            String sem = normaliza(roda(1, n, 30.0, null, dir.resolve("a-" + n + ".csv")));
            String vazio = normaliza(roda(1, n, 30.0, Set.of(), dir.resolve("b-" + n + ".csv")));
            assertTrue(esperado.lines().count() > 20, "referencia com varias finalizacoes");
            assertEquals(esperado, sem, "n=" + n + ": construtor antigo");
            assertEquals(esperado, vazio, "n=" + n + ": conjunto vazio de crash");
        }
    }

    @Test
    void comCrashDaPrimeiraVisaoOCenarioFinalizaSemOsNosEmCrash(@TempDir Path dir) throws IOException {
        int n = 4;
        HotStuffLANScenario sc = new HotStuffLANScenario("hotstuff-crash", 1, n, 20.0,
                HotStuffTestConfig.config(2.0), Set.of(1));
        sc.addMetricsLogger(dir.resolve("c.csv").toString());
        sc.run();
        assertEquals(Set.of(1), sc.getCrashedNodeIds());
        assertTrue(sc.getMetrics().getBlockCount() > 5, "a rede progride apesar do crash");
        for (Object o : sc.getNetwork().getAllNodes()) {
            HotStuffNode no = (HotStuffNode) o;
            HotStuff<?, ?> alg = (HotStuff<?, ?>) no.getConsensusAlgorithm();
            if (no.getNodeID() == 1) {
                assertFalse(alg.isStarted(), "no em crash nao e iniciado");
                assertTrue(no.getNodeNetworkInterface().isNetworkInterfaceDown());
                assertEquals(0, no.getConsensusAlgorithm().getCanonicalChainHead().getHeight());
            } else {
                assertTrue(alg.isStarted());
                assertTrue(no.getConsensusAlgorithm().getCanonicalChainHead().getHeight() > 5);
            }
        }
        String csv = Files.readString(dir.resolve("c.csv"));
        assertTrue(csv.lines().skip(2).noneMatch(l -> l.split(",")[1].equals("1")), "o no em crash nunca e o primeiro a finalizar");
    }

    @Test
    void mesmaSementeMesmoCsvComCrash(@TempDir Path dir) throws IOException {
        for (int n : new int[]{4, 7}) {
            String a = normaliza(roda(42, n, 15.0, Set.of(1), dir.resolve("a-" + n + ".csv")));
            String b = normaliza(roda(42, n, 15.0, Set.of(1), dir.resolve("b-" + n + ".csv")));
            // config de teste: timeout 10 s (default de HotStuffTestConfig.config()); so a identidade importa aqui
            assertTrue(a.lines().count() > 1);
            assertEquals(a, b, "n=" + n);
        }
    }

    @Test
    void conjuntoDeCrashInvalidoFalhaAlto() {
        assertThrows(IllegalArgumentException.class,
                () -> new HotStuffLANScenario("x", 1, 4, 1.0, HotStuffTestConfig.config(), Set.of(4)));
        assertThrows(IllegalArgumentException.class,
                () -> new HotStuffLANScenario("x", 1, 4, 1.0, HotStuffTestConfig.config(), Set.of(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new HotStuffLANScenario("x", 1, 4, 1.0, HotStuffTestConfig.config(), null));
    }
}
