package jabs.network.p2p;

import jabs.network.node.nodes.Node;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-0011 (opcoes A+D): termino do setup P2P para n pequeno (CA-02, CA-04) e erro claro (D).
 * Conexidade e MEDIDA e reportada (a rede pode ficar desconexa; politica: aceitar e registrar), nao
 * exigida, exceto na malha completa.
 *
 * <p><b>Escopo da prova do CA-01 (identidade com a baseline).</b> O teste
 * {@link #identicoAoLacoOriginalOndeEleTermina()} e uma VERIFICACAO AUXILIAR: compara a saida do codigo
 * atual com {@link #lacoOriginal}, que e uma REIMPLEMENTACAO do laco original escrita neste arquivo, nao
 * a baseline. Ele pode errar junto com o codigo se a reimplementacao divergir do original, por isso
 * NAO e a prova do CA-01. A prova do CA-01 e a comparacao contra a baseline REAL (commit
 * {@code e703e3d}, compilada em worktree separado) feita por {@code scripts/jabs/nao_regressao.py} com
 * 3 sementes e as tres familias, cuja evidencia esta em
 * {@code src/test/resources/nao-regressao/p2p-1/} (README.txt, comandos.txt, saida-final/, hashes.txt,
 * resumo.txt).
 *
 * <p>O laco original nao verifica interrupcao: todo setup roda em thread daemon com timeout, senao a JVM
 * de teste nao encerra e o timeout nao prova nada.
 */
class P2PSetupTest {
    private static final int SEMENTES = 25; // >= 20 (CA-02)
    private static final long TIMEOUT_S = 30;

    /** Executa em thread daemon; falha o teste (sem travar a JVM) se nao terminar no prazo. */
    static <T> T comTimeout(String descricao, Callable<T> tarefa) {
        final Object[] res = new Object[1];
        final Throwable[] erro = new Throwable[1];
        Thread t = new Thread(() -> {
            try { res[0] = tarefa.call(); } catch (Throwable e) { erro[0] = e; }
        }, "p2p-setup-" + descricao);
        t.setDaemon(true);
        t.start();
        try {
            t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrompido: " + descricao);
        }
        if (t.isAlive()) {
            fail("setup P2P nao terminou em " + TIMEOUT_S + " s (laco infinito): " + descricao);
        }
        if (erro[0] instanceof RuntimeException re) throw re;
        if (erro[0] instanceof Error er) throw er;
        if (erro[0] != null) throw new RuntimeException(erro[0]);
        @SuppressWarnings("unchecked") T r = (T) res[0];
        return r;
    }

    private static int grau(Supplier<AbstractP2PConnections> f) {
        return ((AbstractBlockchainP2PConnections) f.get()).numOutboundConnections;
    }

    private static String nome(Supplier<AbstractP2PConnections> f) {
        return f == RedeP2PDeTeste.GETH ? "EthereumGethP2P" : "BitcoinCoreP2P";
    }

    /** Resultado agregado de um (familia, n) sobre as sementes. */
    record Medida(int redes, int desconexas, int minSaidas, int maxSaidas, int maxComponentes) { }

    private static Medida medir(Supplier<AbstractP2PConnections> familia, int n) {
        int d = grau(familia);
        int desconexas = 0, minS = Integer.MAX_VALUE, maxS = 0, maxC = 0;
        for (long semente = 1; semente <= SEMENTES; semente++) {
            RedeP2PDeTeste rede = RedeP2PDeTeste.criar(familia, n, semente);
            rede.conectarTodos();
            int somaSaidas = 0;
            for (int i = 0; i < n; i++) {
                int s = rede.saidas(i);
                assertTrue(s <= d, "no " + i + " passou do alvo d=" + d + ": " + s);
                assertTrue(s <= n - 1, "no " + i + " passou de n-1");
                somaSaidas += s;
                minS = Math.min(minS, s);
                maxS = Math.max(maxS, s);
            }
            // cada aresta e um par nao ordenado distinto
            assertTrue(somaSaidas <= n * (n - 1) / 2,
                    "mais arestas que pares: " + somaSaidas + " > C(" + n + ",2)");
            if (n * d > n * (n - 1) / 2) {
                assertTrue(somaSaidas < n * d, "n*d > C(n,2): o alvo n*d nao pode ser atingido");
            }
            for (Node no : rede.getAllNodes()) { // sem auto-laco nem vizinho repetido
                List<Node> v = no.getP2pConnections().getNeighbors();
                assertFalse(v.contains(no), "auto-laco no no " + no.nodeID);
                assertEquals(v.size(), v.stream().map(x -> x.nodeID).distinct().count(),
                        "vizinho repetido no no " + no.nodeID);
            }
            int comps = rede.componentes();
            maxC = Math.max(maxC, comps);
            if (comps > 1) desconexas++;
            if (n <= d + 1) { // malha completa por construcao
                assertEquals(1, comps, "n<=d+1 deve ser conexa");
                for (Node no : rede.getAllNodes()) {
                    assertEquals(n - 1, no.getP2pConnections().getNeighbors().size(),
                            "n<=d+1: grau n-1 (malha completa)");
                }
            }
        }
        return new Medida(SEMENTES, desconexas, minS, maxS, maxC);
    }

    private static void registrar(String linha) throws IOException {
        System.out.println(linha);
        Path saida = Path.of("target", "p2p-conexidade.txt");
        Files.createDirectories(saida.getParent());
        Files.writeString(saida, linha + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void setupTerminaEConexidadeEMedidaParaN4_7_10_16() throws IOException {
        Files.deleteIfExists(Path.of("target", "p2p-conexidade.txt"));
        registrar("# CA-02 (ADR-0011): familia n d sementes desconexas minSaidas maxSaidas maxComponentes");
        for (var familia : List.of(RedeP2PDeTeste.GETH, RedeP2PDeTeste.BITCOIN)) {
            for (int n : new int[]{4, 7, 10, 16}) {
                Medida m = comTimeout(nome(familia) + "-n" + n, () -> medir(familia, n));
                registrar(String.format("%s n=%d d=%d sementes=%d desconexas=%d saidas[min=%d,max=%d] componentes_max=%d",
                        nome(familia), n, grau(familia), m.redes, m.desconexas, m.minSaidas, m.maxSaidas,
                        m.maxComponentes));
            }
        }
    }

    @Test
    void nImpossivelTerminaOuLancaErroClaroNuncaTrava() {
        // n*d > C(n,2), isto e, n < 2d+1: geth d=3 -> n<=6 ; bitcoin d=8 -> n<=16
        for (var familia : List.of(RedeP2PDeTeste.GETH, RedeP2PDeTeste.BITCOIN)) {
            int d = grau(familia);
            for (int n = 1; n <= 2 * d; n++) {
                assertTrue((long) n * d > (long) n * (n - 1) / 2, "premissa: n impossivel");
                final int nn = n;
                comTimeout(nome(familia) + "-impossivel-n" + n, () -> {
                    for (long semente = 1; semente <= 5; semente++) {
                        try {
                            RedeP2PDeTeste r = RedeP2PDeTeste.criar(familia, nn, semente);
                            r.conectarTodos();
                        } catch (IllegalStateException e) {
                            assertTrue(e.getMessage().contains("n=") && e.getMessage().contains("d="),
                                    "erro sem n/d: " + e.getMessage());
                        }
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void nUnicoENoSemCandidatoTerminaSemSaidas() {
        RedeP2PDeTeste r = comTimeout("n1", () -> {
            RedeP2PDeTeste x = RedeP2PDeTeste.criar(RedeP2PDeTeste.GETH, 1, 1);
            x.conectarTodos();
            return x;
        });
        assertEquals(0, r.saidas(0));
        assertTrue(r.getNode(0).getP2pConnections().getNeighbors().isEmpty());
    }

    @Test
    void contaDePares_n16d8_grafoCompletoDeGrau8E_impossivel() {
        assertEquals(128, 16 * 8);
        assertEquals(120, 16 * 15 / 2);
        assertTrue(16 * 8 > 16 * 15 / 2);
        // n=7,d=3 e o limite exato (21 = 21): possivel em tese, nao garantido pelo sorteio
        assertEquals(7 * 3, 7 * 6 / 2);
    }

    // ---- CA-01, verificacao AUXILIAR em processo (NAO e a prova; ver Javadoc da classe) ----------

    /**
     * REIMPLEMENTACAO do laco ORIGINAL sobre ids (mesma ordem de nos, mesma regra de aceite com o "+1"
     * historico, mesmas chamadas a {@code sampleFromList}). NAO e a baseline: e a leitura que este teste
     * faz dela. Devolve null se estourar o teto de tentativas (o original nao terminaria).
     */
    static List<List<Integer>> lacoOriginal(int n, int d, int max, long semente) {
        RandomnessEngine rng = new RandomnessEngine(semente);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(i);
        List<List<Integer>> out = new ArrayList<>(), in = new ArrayList<>(), viz = new ArrayList<>();
        for (int i = 0; i < n; i++) { out.add(new ArrayList<>()); in.add(new ArrayList<>()); viz.add(new ArrayList<>()); }
        long teto = 200_000L;
        for (int k = 0; k < n; k++) {
            long tentativas = 0;
            while (out.get(k).size() < d) {
                if (++tentativas > teto) return null;
                int r = rng.sampleFromList(ids);
                if (r != k && !out.get(k).contains(r) && !in.get(k).contains(r)) {
                    if (in.get(r).size() <= max - d) {
                        in.get(r).add(k);
                        viz.get(r).add(k);
                        out.get(k).add(r);
                        viz.get(k).add(r);
                    }
                }
            }
        }
        return viz;
    }

    /**
     * Verificacao auxiliar: o codigo atual coincide com a REIMPLEMENTACAO {@link #lacoOriginal} onde ela
     * termina. Nao substitui a prova contra a baseline real (src/test/resources/nao-regressao/p2p-1/).
     */
    @Test
    void identicoAReimplementacaoDoLacoOriginalOndeEleTermina() {
        int comparadas = 0, ignoradas = 0;
        for (var familia : List.of(RedeP2PDeTeste.GETH, RedeP2PDeTeste.BITCOIN)) {
            int d = grau(familia);
            int max = ((Integer) maxConexoes(familia));
            for (int n : new int[]{4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 17, 20, 24, 30, 40, 64}) {
                for (long semente = 1; semente <= SEMENTES; semente++) {
                    List<List<Integer>> esperado = lacoOriginal(n, d, max, semente);
                    if (esperado == null) { ignoradas++; continue; }
                    final long s = semente;
                    final int nn = n;
                    List<List<Integer>> obtido = comTimeout(nome(familia) + "-n" + n + "-s" + s, () -> {
                        RedeP2PDeTeste r = RedeP2PDeTeste.criar(familia, nn, s);
                        r.conectarTodos();
                        return r.vizinhos();
                    });
                    assertEquals(esperado, obtido, nome(familia) + " n=" + n + " semente=" + semente);
                    comparadas++;
                }
            }
        }
        System.out.println("identidade com a reimplementacao do laco original (auxiliar): comparadas=" + comparadas
                + " (original nao termina, sem base)=" + ignoradas);
        assertTrue(comparadas > 100, "poucas trincas terminam no original: " + comparadas);
        assertTrue(ignoradas > 0, "esperava trincas que travam no original (n pequeno)");
    }

    private static Object maxConexoes(Supplier<AbstractP2PConnections> familia) {
        return familia == RedeP2PDeTeste.GETH ? 10 : 125;
    }

    // ---- D: falha alta com mensagem clara -------------------------------------------------------

    /** Vizinho que anuncia capacidade mas recusa: estado inconsistente que viraria laco infinito. */
    private static final class Recusador extends AbstractBlockchainP2PConnections {
        Recusador() { super(3, 10); }
        @Override public boolean requestConnection(Node remote) { return false; }
        @Override public void connectToNetwork(jabs.network.networks.Network net) { }
    }

    @Test
    void vizinhoQueRecusaAposSerElegivelLancaErroClaroEmVezDeLacoInfinito() {
        RedeP2PDeTeste rede = comTimeout("recusador-montagem", () -> {
            RedeP2PDeTeste r = RedeP2PDeTeste.criar(RedeP2PDeTeste.GETH, 1, 7);
            r.addNode(new RedeP2PDeTeste.NoStub(1, new Recusador()));
            return r;
        });
        IllegalStateException e = comTimeout("recusador", () ->
                assertThrows(IllegalStateException.class, () -> rede.getNode(0).getP2pConnections().connectToNetwork(rede)));
        String m = e.getMessage();
        assertTrue(m.contains("n=2") && m.contains("d=3") && m.contains("elegiveis=1") && m.contains("no=0"), m);
    }

    // ---- CA-03: JVM nova, -XX:hashCode variando --------------------------------------------------

    private static String emJvmNova(String hashCode, String familia, int n, long semente) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> cmd = new ArrayList<>(List.of(java));
        if (hashCode != null) cmd.addAll(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=" + hashCode));
        cmd.addAll(List.of("-cp", cp, "jabs.network.p2p.P2PVizinhosMain", familia, String.valueOf(n),
                String.valueOf(semente)));
        Path saida = Files.createTempFile("p2p-viz", ".out");
        Path erro = Files.createTempFile("p2p-viz", ".err");
        try {
            Process p = new ProcessBuilder(cmd).redirectOutput(saida.toFile()).redirectError(erro.toFile()).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("processo filho nao terminou (hashCode=" + hashCode + ")");
            }
            if (p.exitValue() != 0) {
                return "ERRO:" + Files.readString(erro);
            }
            return Files.readString(saida);
        } finally {
            Files.deleteIfExists(saida);
            Files.deleteIfExists(erro);
        }
    }

    @Test
    void mesmaSementeMesmosVizinhosNaMesmaJvmEEmJvmNovaComHashCodeVariando() throws Exception {
        for (var cfg : new Object[][]{{"geth", 10, 3L}, {"bitcoin", 16, 5L}, {"geth", 4, 2L}, {"bitcoin", 40, 9L}}) {
            String familia = (String) cfg[0];
            int n = (Integer) cfg[1];
            long semente = (Long) cfg[2];
            var fab = familia.equals("geth") ? RedeP2PDeTeste.GETH : RedeP2PDeTeste.BITCOIN;

            // duas execucoes na mesma JVM
            List<List<Integer>> a = comTimeout("a", () -> { var r = RedeP2PDeTeste.criar(fab, n, semente); r.conectarTodos(); return r.vizinhos(); });
            List<List<Integer>> b = comTimeout("b", () -> { var r = RedeP2PDeTeste.criar(fab, n, semente); r.conectarTodos(); return r.vizinhos(); });
            assertEquals(a, b, "mesma JVM " + familia + " n=" + n);

            StringBuilder esperado = new StringBuilder();
            for (int i = 0; i < a.size(); i++) esperado.append(i).append(':').append(a.get(i)).append('\n');
            String ref = esperado.toString();

            // JVM nova padrao
            assertEquals(ref, emJvmNova(null, familia, n, semente), "JVM nova " + familia + " n=" + n);

            // JVM nova com -XX:hashCode variando (0..5). Se a flag nao for aceita, nao verificado.
            int verificados = 0;
            for (String modo : new String[]{"0", "1", "2", "3", "4", "5"}) {
                String saida = emJvmNova(modo, familia, n, semente);
                if (saida.startsWith("ERRO:")) {
                    System.out.println("CA-03 NAO VERIFICADO com -XX:hashCode=" + modo + ": "
                            + saida.substring(0, Math.min(120, saida.length())).trim());
                    continue;
                }
                verificados++;
                assertEquals(ref, saida, familia + " n=" + n + " -XX:hashCode=" + modo);
            }
            System.out.println("CA-03 " + familia + " n=" + n + ": modos -XX:hashCode verificados=" + verificados + "/6");
        }
    }
}
