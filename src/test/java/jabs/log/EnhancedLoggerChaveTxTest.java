package jabs.log;

import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.scenario.HotStuffLANScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HASHCODE-hotstuff-tx-por-valor / LOGGER-chave-txid-sequencial: o {@link EnhancedBlockFinalizationLogger} nao pode
 * chavear transacoes por {@code Hash} (sem hashCode/equals: identidade). A chave e um id sequencial POR SIMULACAO.
 *
 * <p>Estes testes FALHAM sem a correcao: (1) as chaves de {@code getBlockTransactions()} deixam de ser {@code Long}
 * (voltam a ser o {@code Hash}); (2) um contador estatico global faria a 2a simulacao na mesma JVM comecar onde a 1a
 * parou; (3) sob {@code -XX:hashCode=2} o custo volta a ser quadratico (ver {@link TempoChaveTxFilho}).
 */
class EnhancedLoggerChaveTxTest {
    private static final double DURACAO_S = 10.0;

    private record Corrida(Map<Integer, Set<Long>> txPorAltura, int rastreadas, String csvSemHash) {
    }

    private static Corrida roda(long seed, int n, double duracao, Path csv) throws IOException {
        HotStuffLANScenario sc = new HotStuffLANScenario("chave-tx", seed, n, duracao, HotStuffTestConfig.config());
        EnhancedBlockFinalizationLogger logger = new EnhancedBlockFinalizationLogger(csv, sc.getMetrics());
        sc.AddNewLogger(logger);
        sc.run();
        Map<Integer, Set<Long>> copia = new TreeMap<>();
        for (Map.Entry<Integer, Set<Object>> e : logger.getBlockTransactions().entrySet()) {
            Set<Long> ids = new TreeSet<>();
            for (Object o : e.getValue()) {
                ids.add((Long) o); // ClassCastException se a chave nao for o id sequencial
            }
            copia.put(e.getKey(), ids);
        }
        return new Corrida(copia, logger.getDoubleSpendTracker().getTotalTransactionsTracked(), semColunaHash(csv));
    }

    /** CSV sem comentarios e sem a coluna BlockHashCode (hashCode de identidade do bloco, varia por JVM). */
    private static String semColunaHash(Path csv) throws IOException {
        List<String> linhas = Files.readAllLines(csv);
        int idx = -1;
        StringBuilder out = new StringBuilder();
        for (String l : linhas) {
            if (l.startsWith("#") || l.isBlank()) {
                continue;
            }
            String[] c = l.split(",", -1);
            if (idx < 0) {
                for (int i = 0; i < c.length; i++) {
                    if (c[i].equals("BlockHashCode")) {
                        idx = i;
                    }
                }
                assertTrue(idx >= 0, "cabecalho com BlockHashCode");
            }
            for (int i = 0; i < c.length; i++) {
                if (i != idx) {
                    out.append(c[i]).append(',');
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static Set<Long> uniao(Corrida c) {
        Set<Long> todos = new TreeSet<>();
        for (Set<Long> s : c.txPorAltura().values()) {
            for (Long o : s) {
                assertTrue(todos.add(o), "tx em dois blocos finalizados (nao esperado no caminho honesto)");
            }
        }
        return todos;
    }

    @Test
    void chaveDasTransacoesEIdSequencialPorValorDesdeZeroSemBuracos(@TempDir Path dir) throws IOException {
        Corrida c = roda(7, 4, DURACAO_S, dir.resolve("a.csv"));
        assertFalse(c.txPorAltura().isEmpty(), "deve finalizar blocos com transacoes");
        Set<Long> ids = uniao(c);
        long k = ids.size();
        assertTrue(k > 0);
        for (long i = 0; i < k; i++) {
            assertTrue(ids.contains(i), "ids contiguos a partir de 0; falta " + i);
        }
        assertEquals(k, c.rastreadas(), "DoubleSpendTracker rastreia exatamente as mesmas chaves");
    }

    /**
     * Duas simulacoes em sequencia na MESMA JVM: mesmo resultado (ids, contagem e CSV). Com contador estatico global a
     * segunda comecaria no ultimo id da primeira e a comparacao de ids falharia. Uma terceira, com outra semente,
     * roda entre elas para que o estado sobrante de uma corrida nao possa coincidir por acaso.
     */
    @Test
    void duasSimulacoesNaMesmaJvmDaoOMesmoResultado(@TempDir Path dir) throws IOException {
        Corrida a = roda(11, 4, DURACAO_S, dir.resolve("a.csv"));
        Corrida outra = roda(12, 4, DURACAO_S, dir.resolve("o.csv"));
        Corrida b = roda(11, 4, DURACAO_S, dir.resolve("b.csv"));
        assertEquals(a.txPorAltura(), b.txPorAltura(), "ids por altura iguais: o contador e por simulacao");
        assertEquals(a.rastreadas(), b.rastreadas());
        assertEquals(a.csvSemHash(), b.csvSemHash());
        assertEquals(0L, uniao(outra).iterator().next(), "a corrida intercalada tambem comecou do zero");
        assertEquals(0L, uniao(b).iterator().next());
    }

    /**
     * Sob -XX:hashCode=2 (JVM filha) a simulacao de referencia, em escala reduzida, termina em tempo da ordem do caso
     * sem a flag. Antes da correcao: 14,6 s com a flag contra 0,6 s sem (15 s simulados, lote 500); depois: ~0,6 s. O
     * limite (6 s) e folgado para nao depender da maquina e ainda separa os dois casos.
     */
    @Test
    void sobHashCode2OCustoDoLoggerNaoEQuadratico() throws Exception {
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path saida = Files.createTempFile("chave-tx-filho", ".txt");
        try {
            List<String> cmd = new ArrayList<>(List.of(jvm, "-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2",
                    "-cp", cp, TempoChaveTxFilho.class.getName()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(saida.toFile()).start();
            boolean terminou = p.waitFor(120L, TimeUnit.SECONDS);
            if (!terminou) {
                p.destroyForcibly().waitFor();
            }
            String log = Files.readString(saida);
            assertTrue(terminou, "JVM filha nao terminou em 120 s (custo quadratico?): " + log);
            assertEquals(0, p.exitValue(), log);
            assertTrue(log.contains("CHAVE-TX-OK"), log);
        } finally {
            Files.deleteIfExists(saida);
        }
    }
}
