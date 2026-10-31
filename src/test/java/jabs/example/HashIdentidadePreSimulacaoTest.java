package jabs.example;

import jabs.config.NetworkSizing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1, rodada 2: o pre-simulacao do modo legacy nao pode deslocar a sequencia de {@link System#identityHashCode} da
 * thread principal (criterio 7: a saida de Casper/Parlia/Nakamoto depende dessa sequencia; ver o README da N1).
 *
 * <p><b>Como mede.</b> O hash de identidade padrao do HotSpot e um xorshift POR THREAD: a sequencia de uma JVM nova
 * e a mesma a cada execucao e cada {@code identityHashCode} de um objeto novo avanca o estado em 1. Entao, numa JVM
 * filha, ler {@code identityHashCode(new Object())} antes e depois de um trecho e procurar os valores na sequencia
 * de referencia (outra JVM filha) da o numero de hashes que o trecho consumiu. {@link SondaPreSim} faz isso para o
 * trecho pre-simulacao em tres versoes: {@code base} (replica do original 325f4e3), {@code antigo} (replica da N1
 * antes da rodada 2: CliArgs + enum + record + concatenacao) e {@code novo} (as rotinas REAIS de
 * {@link MVPComparison}).
 *
 * <p><b>Limites do teste</b> (honestos): (1) o consumo e por thread e a sequencia padrao depende do build da JVM;
 * so a igualdade <i>entre JVMs filhas do mesmo teste</i> e afirmada, nunca um numero absoluto. (2) A versao
 * {@code base} e uma REPLICA do trecho original (a baseline nao esta na arvore); a equivalencia com o original e
 * conferida de ponta a ponta fora daqui ({@code nao_regressao.py} contra 325f4e3, 18 de 18 IDENTICO). (3) Mede o
 * trecho pre-simulacao de {@code MVPComparison.main}, nao a simulacao. (4) Se uma JVM futura mudar o gerador de
 * hash (ex.: {@code -XX:hashCode} diferente do padrao), o teste ainda compara as tres versoes sob o MESMO modo,
 * mas um modo sem sequencia por thread (constante) tornaria o consumo nao observavel: o teste falha em vez de
 * passar em silencio quando a referencia nao e reconhecida.
 */
class HashIdentidadePreSimulacaoTest {
    @TempDir
    Path tmp;

    private static String classpath() throws IOException {
        Path base = Path.of("").toAbsolutePath();
        StringBuilder cp = new StringBuilder();
        cp.append(base.resolve("target/test-classes")).append(':').append(base.resolve("target/classes"));
        Path deps = base.resolve("target/cp.txt");
        if (Files.exists(deps)) {
            cp.append(':').append(Files.readString(deps).trim());
        }
        return cp.toString();
    }

    private static String rodar(String cp, Path saida, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("java", "-cp", cp));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(saida.toFile()).start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("JVM filha estourou 120 s: " + cmd);
        }
        assertEquals(0, p.exitValue(), "JVM filha: " + Files.readString(saida));
        return Files.readString(saida, StandardCharsets.UTF_8);
    }

    private static int total(String saida) {
        for (String l : saida.split("\n")) {
            if (l.contains("TOTAL pre-simulacao")) {
                return Integer.parseInt(l.substring(l.indexOf("delta=") + 6).trim());
            }
        }
        throw new IllegalStateException("sem linha TOTAL na saida da sonda: " + saida);
    }

    @Test
    void preSimulacaoLegacyConsomeOMesmoNumeroDeHashesQueABaseline() throws Exception {
        String cp = classpath();
        Path ref = tmp.resolve("ref.txt");
        // sequencia de referencia de uma JVM nova: 2000 valores sobram (o pre-simulacao consome dezenas)
        rodar(cp, ref, "jabs.example.SondaHash", "ref", "2000");
        List<String> seq = Files.readAllLines(ref);
        assertEquals(2000, seq.size());
        assertEquals(2000, seq.stream().distinct().count(),
                "referencia sem repeticao: com hash constante (-XX:hashCode=2) o consumo nao e observavel");

        String[][] casos = {{"MCO2", "10"}, {"AMBIFY", "10"}, {"OFFSET", "40"}, {"ARQ", "7"}};
        for (String[] c : casos) {
            int base = total(rodar(cp, tmp.resolve("b-" + c[0]), "jabs.example.SondaPreSim", ref.toString(), "base",
                    c[0], c[1]));
            int novo = total(rodar(cp, tmp.resolve("n-" + c[0]), "jabs.example.SondaPreSim", ref.toString(), "novo",
                    c[0], c[1]));
            int antigo = total(rodar(cp, tmp.resolve("a-" + c[0]), "jabs.example.SondaPreSim", ref.toString(),
                    "antigo", c[0], c[1]));
            assertEquals(base, novo, c[0] + ": o pre-simulacao legacy atual consome " + novo
                    + " hashes de identidade; a baseline consumia " + base + " (a diferenca desloca a saida)");
            // controle: a sonda tem de enxergar o defeito da versao anterior, senao o teste nao prova nada
            assertTrue(antigo > base, c[0] + ": a sonda nao distingue a versao antiga (" + antigo + ") da baseline ("
                    + base + ")");
        }
    }

    @Test
    void reescritaSemClasseNovaPreservaOComportamentoLegacyENodes() {
        // describeSizing == texto antigo montado com NetworkSizing.Decomposition.describe()
        for (boolean nodes : new boolean[]{false, true}) {
            NetworkSizing.Semantics sem = nodes ? NetworkSizing.Semantics.NODES : NetworkSizing.Semantics.LEGACY;
            for (int n = 2; n <= 400; n++) {
                String d = NetworkSizing.forMvp(sem, n).describe();
                assertEquals("protocol=MCO2 " + d + " voters=casper_voters", MVPComparison.describeSizing("MCO2", nodes, n));
                assertEquals("protocol=TREE " + d + " voters=casper_voters", MVPComparison.describeSizing("TREE", nodes, n));
                assertEquals("protocol=AMBIFY " + d + " voters=parlia_validators",
                        MVPComparison.describeSizing("AMBIFY", nodes, n));
                assertEquals("protocol=ARQ n_semantics=" + sem.rotulo() + " n_requested=" + n + " nodes=" + n
                        + " validators=" + n, MVPComparison.describeSizing("ARQ", nodes, n));
                assertEquals("protocol=OFFSET n_semantics=" + sem.rotulo() + " n_requested=" + n + " nodes=" + n
                        + " miners=" + n + " stakeholders=0", MVPComparison.describeSizing("OFFSET", nodes, n));
                int miners = Math.max(1, (int) Math.round(n * 0.3));
                assertEquals("protocol=DAGSPER n_semantics=" + sem.rotulo() + " n_requested=" + n + " nodes=" + n
                        + " miners=" + miners + " stakeholders=" + (n - miners) + " voters=all",
                        MVPComparison.describeSizing("DAGSPER", nodes, n));
                int[] m = MVPComparison.mvpSizing(nodes, n);
                assertEquals(NetworkSizing.forMvp(sem, n).miners(), m[0]);
                assertEquals(NetworkSizing.forMvp(sem, n).stakeholders(), m[1]);
                int[] w = ByzantineSweep.sweepSizing(nodes, n);
                assertEquals(NetworkSizing.forSweep(sem, n).miners(), w[0]);
                assertEquals(NetworkSizing.forSweep(sem, n).stakeholders(), w[1]);
            }
        }
        // legacy aceita n pequeno como antes (so nodes exige n >= 2)
        for (int n = -3; n <= 1; n++) {
            int[] m = MVPComparison.mvpSizing(false, n);
            assertEquals((int) (n * 0.3), m[0]);
            assertEquals((int) (n * 0.7), m[1]);
        }
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.mvpSizing(true, 1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.sweepSizing(true, 1));
        assertEquals("protocol=XPTO sizing=desconhecido (protocolo nao reconhecido)",
                MVPComparison.describeSizing("XPTO", false, 10));
    }

    @Test
    void semanticaDoNEFlagsIgualAoEnumENosDoisCaminhos() {
        for (String v : new String[]{null, "legacy", "nodes"}) {
            boolean esperado = NetworkSizing.Semantics.fromFlag(v) == NetworkSizing.Semantics.NODES;
            assertEquals(esperado, MVPComparison.isNodesMode(v), String.valueOf(v));
            assertEquals(esperado, ByzantineSweep.isNodesMode(v), String.valueOf(v));
        }
        for (String ruim : new String[]{"", " ", "NODES", "Nodes", "node", "nodes ", "validators", "true"}) {
            assertThrows(IllegalArgumentException.class, () -> MVPComparison.isNodesMode(ruim), "'" + ruim + "'");
            assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.isNodesMode(ruim), "'" + ruim + "'");
        }
        Map<String, String> m1 = MVPComparison.parseArgs(new String[]{"--protocols=MCO2", "--validators=10", "--n-semantics=nodes"});
        assertEquals(Map.of("protocols", "MCO2", "validators", "10", "n-semantics", "nodes"), m1);
        Map<String, String> m2 = ByzantineSweep.parseArgs(new String[]{"--type=POS", "--validators=10", "--n-semantics=nodes",
                "--max-pdv-pct=1"});
        assertEquals(Map.of("type", "POS", "validators", "10", "n-semantics", "nodes", "max-pdv-pct", "1"), m2);
        // rodada 3: chaves de um caminho no outro nao sao lidas, mas (como na baseline) nao abortam
        assertEquals(Map.of("type", "POS"), MVPComparison.parseArgs(new String[]{"--type=POS"}));
        assertEquals(Map.of("protocols", "MCO2"), ByzantineSweep.parseArgs(new String[]{"--protocols=MCO2"}));
    }
}
