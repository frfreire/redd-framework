package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.consensus.algorithm.DAGsper;
import jabs.metrics.SimulationMetrics;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.ethereum.EthereumDAGsperMiner;
import jabs.network.node.nodes.ethereum.EthereumDAGsperNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 5 (debito 7): o DAGsper roda de ponta a ponta por um cenario (o mesmo usado pelo
 * MVPComparison), com a rede instanciando DAGsper de verdade, trafego medido, determinismo por semente e modelo
 * de falha explicito. Todos os testes usam n = 10 (menor rede em que o setup P2P termina).
 */
class DAGsperScenarioTest {
    private static final int MINERS = 3;
    private static final int OTHERS = 7;
    private static final int CHECKPOINT_SPACE = 14;
    private static final double INTERVAL = 15;
    private static final double STOP = 600;

    /** Resultado de uma execucao: metricas e linhas do CSV (sem a coluna *hash*). */
    private record Execucao(SimulationMetrics metricas, List<List<String>> linhas, List<String> cabecalho,
                            DAGsperNetworkScenario cenario) {}

    private static Execucao rodar(long seed, int bizantinos, ByzantineConfig.AttackType ataque) throws IOException {
        Path csv = Files.createTempFile("dagsper-cenario", ".csv");
        try {
            SimulationMetrics m = new SimulationMetrics();
            DAGsperNetworkScenario c = new DAGsperNetworkScenario("teste", seed, STOP, INTERVAL, CHECKPOINT_SPACE,
                    MINERS, OTHERS, bizantinos, ataque, true, 5.0);
            c.addMetricsLogger(csv.toString(), m);
            c.run();
            List<String> todas = Files.readAllLines(csv);
            List<String> dados = new ArrayList<>();
            for (String l : todas) {
                if (!l.isBlank() && !l.startsWith("#")) {
                    dados.add(l);
                }
            }
            List<String> cabCompleto = List.of(dados.get(0).split(","));
            int hash = -1;
            for (int i = 0; i < cabCompleto.size(); i++) {
                if (cabCompleto.get(i).toLowerCase().contains("hash")) {
                    hash = i;
                }
            }
            List<String> cab = new ArrayList<>(cabCompleto);
            if (hash >= 0) {
                cab.remove(hash);
            }
            List<List<String>> linhas = new ArrayList<>();
            for (String l : dados.subList(1, dados.size())) {
                List<String> campos = new ArrayList<>(List.of(l.split(",", -1)));
                if (hash >= 0) {
                    campos.remove(hash);
                }
                linhas.add(campos);
            }
            return new Execucao(m, linhas, cab, c);
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    private static int coluna(Execucao e, String nome) {
        return e.cabecalho().indexOf(nome);
    }

    // ---------- a rede instancia o DAGsper (o defeito original: GhostProtocol puro) ----------

    @Test
    void redeInstanciaDagsperEmTodosOsNos() throws IOException {
        Execucao e = rodar(1, 0, ByzantineConfig.AttackType.NONE);
        assertEquals(MINERS + OTHERS, e.cenario().getNetwork().getAllNodes().size());
        int mineradores = 0;
        for (Object o : e.cenario().getNetwork().getAllNodes()) {
            PeerBlockchainNode<?, ?> no = (PeerBlockchainNode<?, ?>) o;
            assertInstanceOf(DAGsper.class, no.getConsensusAlgorithm(), "no " + no.nodeID + " deve rodar DAGsper");
            if (no instanceof EthereumDAGsperMiner) {
                mineradores++;
            } else {
                assertInstanceOf(EthereumDAGsperNode.class, no);
            }
            // quorum de 2f+1 sobre o total de nos, nao sobre uma fracao
            assertEquals(DAGsper.quorumSize(MINERS + OTHERS), ((DAGsper<?, ?>) no.getConsensusAlgorithm()).getRequiredVotes());
        }
        assertEquals(MINERS, mineradores);
    }

    // ---------- executa e mede ----------

    @Test
    void executaFinalizaBlocosETrafegoEMedido() throws IOException {
        Execucao e = rodar(1, 0, ByzantineConfig.AttackType.NONE);
        assertFalse(e.linhas().isEmpty(), "DAGsper deve finalizar blocos em " + STOP + " s");
        int t = coluna(e, "TrafficUntilFinalization");
        int c = coluna(e, "TrafficConsensusUntilFinalization");
        assertTrue(t >= 0 && c >= 0, "colunas de trafego presentes: " + e.cabecalho());
        boolean algumConsenso = false;
        for (List<String> l : e.linhas()) {
            long total = Long.parseLong(l.get(t));
            long consenso = Long.parseLong(l.get(c));
            assertTrue(total > 0, "C_B_total medido > 0 (sem trafego nao ha bloco recebido): " + l);
            assertTrue(consenso >= 0, "C_B_consenso medido, nunca o sentinela -1 no DAGsper: " + l);
            assertTrue(consenso <= total, "consenso (so votos) <= total (bloco + votos): " + l);
            algumConsenso |= consenso > 0;
        }
        assertTrue(algumConsenso, "ha votos de rede contados no consenso");
        assertEquals(e.linhas().size(), e.metricas().getBlockCount());
    }

    // ---------- determinismo por semente ----------

    @Test
    void mesmaSementeMesmaSaida() throws IOException {
        Execucao a = rodar(1, 0, ByzantineConfig.AttackType.NONE);
        Execucao b = rodar(1, 0, ByzantineConfig.AttackType.NONE);
        assertFalse(a.linhas().isEmpty());
        assertEquals(a.linhas(), b.linhas(), "mesma semente => mesmo CSV (colunas *hash* ignoradas)");
    }

    @Test
    void sementesDistintasSaidasDistintas() throws IOException {
        Execucao a = rodar(1, 0, ByzantineConfig.AttackType.NONE);
        Execucao b = rodar(2, 0, ByzantineConfig.AttackType.NONE);
        assertFalse(a.linhas().isEmpty());
        assertFalse(b.linhas().isEmpty());
        assertNotEquals(a.linhas(), b.linhas(), "sementes distintas => saidas distintas");
    }

    // ---------- limite n >= 10: falha alto em vez de travar ----------

    @Test
    void redePequenaFalhaAltoEmVezDeTravar() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new DAGsperNetworkScenario("pequeno", 1, STOP, INTERVAL, CHECKPOINT_SPACE, 2, 7, 0,
                        ByzantineConfig.AttackType.NONE, false, 5.0));
        assertTrue(ex.getMessage().contains("n >= 10"), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new DAGsperNetworkScenario("sem-minerador", 1, STOP, INTERVAL, CHECKPOINT_SPACE, 0, 10, 0,
                        ByzantineConfig.AttackType.NONE, false, 5.0));
    }

    // ---------- modelo de falha: SILENT/WITHHOLD, o resto e recusado ----------

    @Test
    void silenciososAteFToleradosAcimaDeFNaoFinaliza() throws IOException {
        // n = 10 => f = 3, quorum = 7: com 4 silenciosos so 6 nos votam e nada pode ser justificado/finalizado.
        Execucao acima = rodar(1, 4, ByzantineConfig.AttackType.SILENT);
        assertEquals(0, acima.linhas().size(), "4 silenciosos de 10 (> f = 3): sem quorum, sem finalizacao");
        Execucao zero = rodar(1, 0, ByzantineConfig.AttackType.SILENT);
        assertFalse(zero.linhas().isEmpty(), "0 silenciosos: finaliza");
    }

    @Test
    void withholdTemAMesmaSemanticaDeSilent() throws IOException {
        Execucao s = rodar(1, 4, ByzantineConfig.AttackType.SILENT);
        Execucao w = rodar(1, 4, ByzantineConfig.AttackType.WITHHOLD);
        assertEquals(s.linhas(), w.linhas(), "WITHHOLD e SILENT: mesma semantica (o no nao emite o proprio voto)");
    }

    @Test
    void ataqueNaoImplementadoERecusado() {
        for (ByzantineConfig.AttackType a : new ByzantineConfig.AttackType[] {
                ByzantineConfig.AttackType.EQUIVOCATION, ByzantineConfig.AttackType.DOUBLE_SIGN}) {
            assertThrows(UnsupportedOperationException.class,
                    () -> new DAGsperNetworkScenario("x", 1, STOP, INTERVAL, CHECKPOINT_SPACE, MINERS, OTHERS, 1, a,
                            false, 5.0), a + " nao esta implementado: recusar, nao tratar como honesto");
        }
    }

    @Test
    void bizantinosSaoDeterministicosEAninhados() throws IOException {
        Execucao tres = rodar(5, 3, ByzantineConfig.AttackType.SILENT);
        Execucao quatro = rodar(5, 4, ByzantineConfig.AttackType.SILENT);
        Execucao tresDeNovo = rodar(5, 3, ByzantineConfig.AttackType.SILENT);
        Set<Integer> a = tres.cenario().getByzantineConfig().getByzantineValidatorIds();
        Set<Integer> b = quatro.cenario().getByzantineConfig().getByzantineValidatorIds();
        assertEquals(3, a.size());
        assertEquals(4, b.size());
        assertTrue(b.containsAll(a), "mesma semente, f maior contem o conjunto do f menor");
        assertEquals(a, tresDeNovo.cenario().getByzantineConfig().getByzantineValidatorIds());
        assertEquals(3, tres.metricas().getByzantineValidators());
    }

    // ---------- determinismo entre JVMs com hash de identidade diferente ----------

    /** Roda o MVPComparison (o mesmo ponto de entrada do portao) num processo novo com -XX:hashCode=modo. */
    private static List<String> csvEmProcessoNovo(int modoHashCode, long seed) throws Exception {
        String jvm = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path pasta = Files.createTempDirectory("dagsper-hash");
        try {
            Process p = new ProcessBuilder(jvm, "-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=" + modoHashCode,
                    "-cp", cp, "jabs.example.MVPComparison", "--protocols=DAGSPER", "--validators=10", "--trials=1",
                    "--duration=600", "--seed=" + seed, "--output=" + pasta)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertTrue(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "execucao nao terminou");
            assertEquals(0, p.exitValue());
            List<String> saida = new ArrayList<>();
            for (String l : Files.readAllLines(pasta.resolve("dagsper-10v-t0.csv"))) {
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                List<String> campos = new ArrayList<>(List.of(l.split(",", -1)));
                campos.remove(3); // BlockHashCode: hashCode de objeto varia por execucao
                saida.add(String.join(",", campos));
            }
            return saida;
        } finally {
            try (var arquivos = Files.walk(pasta)) {
                arquivos.sorted(java.util.Comparator.reverseOrder()).forEach(a -> a.toFile().delete());
            }
        }
    }

    @Test
    void saidaNaoDependeDoHashDeIdentidadeDosObjetos() throws Exception {
        List<String> a = csvEmProcessoNovo(0, 1);
        List<String> b = csvEmProcessoNovo(4, 1);
        List<String> c = csvEmProcessoNovo(5, 1);
        assertTrue(a.size() > 1, "o cenario deve finalizar blocos");
        assertEquals(a, b, "hashCode=0 x 4: o ramo principal, os blocos e o trafego nao podem depender do endereco");
        assertEquals(a, c, "hashCode=0 x 5");
    }

    // ---------- higiene ----------

    @Test
    void cenarioNaoTemAleatoriedadeForaDoEngineNemFormulaDeTrafego() throws IOException {
        String codigo = Files.readString(java.nio.file.Paths.get("src/main/java/jabs/scenario/DAGsperNetworkScenario.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertFalse(codigo.contains("Math.random"));
        assertFalse(codigo.contains("new Random(") || codigo.contains("new java.util.Random("));
        assertFalse(codigo.contains("nextGaussian"));
        assertFalse(codigo.contains("getSize()"), "o cenario nao calcula trafego: so os hooks medem packet.getSize()");
    }
}
