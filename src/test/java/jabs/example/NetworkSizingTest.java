package jabs.example;

import jabs.config.NetworkSizing;
import jabs.network.networks.GlobalProofOfWorkNetwork;
import jabs.network.node.nodes.Node;
import jabs.scenario.AbstractScenario;
import jabs.scenario.CasperEthereumNetworkScenario;
import jabs.scenario.ParliaBSCNetworkScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1-conversao-n-mvpcomparison: o {@code n} da linha de comando (ADR-0011, auditoria L10).
 * Os testes CONSTROEM a rede (createNetwork, sem simular) e contam {@code getAllNodes()}.
 */
class NetworkSizingTest {
    private static final int[] N_ESTUDO = {4, 16, 64, 128};

    @TempDir
    Path tmp;

    private static AbstractScenario construir(String proto, NetworkSizing.Decomposition d, long seed) {
        if (proto.equals("AMBIFY")) {
            ParliaBSCNetworkScenario s = new ParliaBSCNetworkScenario("t", seed, 60.0, 3.0, 3, 21, d.miners(),
                    d.stakeholders());
            s.createNetwork();
            return s;
        }
        CasperEthereumNetworkScenario s = new CasperEthereumNetworkScenario("t", seed, 60.0, 15.0, 14, d.miners(),
                d.stakeholders());
        s.createNetwork();
        return s;
    }

    private static int nos(AbstractScenario s) {
        return s.getNetwork().getAllNodes().size();
    }

    @Test
    void legacyContinuaConstruindo3_15_63_127() {
        int[] esperado = {3, 15, 63, 127};
        for (String proto : new String[]{"MCO2", "AMBIFY"}) {
            for (int i = 0; i < N_ESTUDO.length; i++) {
                NetworkSizing.Decomposition d = NetworkSizing.forMvp(NetworkSizing.Semantics.LEGACY, N_ESTUDO[i]);
                assertEquals(esperado[i], d.totalNodes(), "formula " + proto + " n=" + N_ESTUDO[i]);
                assertEquals(esperado[i], nos(construir(proto, d, 1L)), "construido " + proto + " n=" + N_ESTUDO[i]);
            }
        }
    }

    @Test
    void legacyReproduzAsExpressoesOriginais() {
        for (int n = 1; n <= 900; n++) {
            NetworkSizing.Decomposition d = NetworkSizing.mvpLegacy(n);
            assertEquals((int) (n * 0.3), d.miners(), "n=" + n);
            assertEquals((int) (n * 0.7), d.stakeholders(), "n=" + n);
            NetworkSizing.Decomposition w = NetworkSizing.sweepLegacy(n);
            assertEquals((int) Math.max(1, Math.round(n * 0.3)), w.miners(), "sweep n=" + n);
            assertEquals(Math.max(1, n), w.stakeholders(), "sweep n=" + n);
        }
    }

    @Test
    void modoNodesConstroiExatamenteN() {
        for (String proto : new String[]{"MCO2", "AMBIFY"}) {
            for (int n : N_ESTUDO) {
                NetworkSizing.Decomposition d = NetworkSizing.forMvp(NetworkSizing.Semantics.NODES, n);
                AbstractScenario s = construir(proto, d, 1L);
                assertEquals(n, nos(s), proto + " n=" + n);
                assertEquals(d.miners(), ((GlobalProofOfWorkNetwork<?, ?, ?, ?>) s.getNetwork()).getAllMiners().size());
            }
        }
    }

    @Test
    void decomposicaoSomaNEMineradoresMinimo1() {
        for (int n = 2; n <= 1000; n++) {
            NetworkSizing.Decomposition d = NetworkSizing.nodes(n);
            assertEquals(n, d.miners() + d.stakeholders(), "n=" + n);
            assertEquals(n, d.totalNodes());
            assertTrue(d.miners() >= 1 && d.stakeholders() >= 1, "n=" + n);
            assertEquals(Math.max(1L, Math.round(n * 0.3 + 1e-9)), d.miners(), "round-half-up n=" + n);
        }
        // valores do estudo e de validacao (fixados aqui para o README)
        assertEquals(1, NetworkSizing.nodes(4).miners());
        assertEquals(3, NetworkSizing.nodes(4).stakeholders());
        assertEquals(5, NetworkSizing.nodes(16).miners());
        assertEquals(19, NetworkSizing.nodes(64).miners());
        assertEquals(38, NetworkSizing.nodes(128).miners());
        assertEquals(77, NetworkSizing.nodes(256).miners());
        assertEquals(120, NetworkSizing.nodes(400).miners());
        assertEquals(240, NetworkSizing.nodes(800).miners());
    }

    @Test
    void nodesComNMenorQue2FalhaAlto() {
        assertThrows(IllegalArgumentException.class, () -> NetworkSizing.nodes(1));
        assertThrows(IllegalArgumentException.class, () -> NetworkSizing.nodes(0));
        assertThrows(IllegalArgumentException.class, () -> NetworkSizing.nodes(-5));
    }

    @Test
    void deterministicoPorSemente() {
        for (String proto : new String[]{"MCO2", "AMBIFY"}) {
            NetworkSizing.Decomposition d = NetworkSizing.nodes(16);
            assertEquals(vizinhanca(construir(proto, d, 7L)), vizinhanca(construir(proto, d, 7L)),
                    proto + ": mesma semente, mesma topologia");
            assertFalse(vizinhanca(construir(proto, d, 7L)).equals(vizinhanca(construir(proto, d, 8L))),
                    proto + ": sementes diferentes, topologias diferentes");
        }
    }

    private static List<List<Integer>> vizinhanca(AbstractScenario s) {
        List<List<Integer>> r = new ArrayList<>();
        for (Object o : s.getNetwork().getAllNodes()) {
            List<Integer> ids = new ArrayList<>();
            for (Node v : ((Node) o).getP2pConnections().getNeighbors()) {
                ids.add(v.nodeID);
            }
            r.add(ids);
        }
        return r;
    }

    @Test
    void flagValoresInvalidosFalhamAlto() {
        assertEquals(NetworkSizing.Semantics.LEGACY, NetworkSizing.Semantics.fromFlag(null));
        assertEquals(NetworkSizing.Semantics.LEGACY, NetworkSizing.Semantics.fromFlag("legacy"));
        assertEquals(NetworkSizing.Semantics.NODES, NetworkSizing.Semantics.fromFlag("nodes"));
        for (String ruim : new String[]{"", " ", "NODES", "Nodes", "node", "nodes ", "validators", "true"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> NetworkSizing.Semantics.fromFlag(ruim), "'" + ruim + "'");
            assertTrue(e.getMessage().contains("legacy") && e.getMessage().contains("nodes"), e.getMessage());
        }
    }

    /** Roda {@code r} capturando o stderr (restaura sempre). */
    private static String capturarStderr(Runnable r) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(original);
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Rodada 3: flag desconhecida, argumento sem '=' e flag repetida voltam a ser tratados EXATAMENTE como na
     * baseline 325f4e3 (ignorados / ultima vence), com AVISO so no stderr. A unica validacao nova e a de
     * --n-semantics (e a de n em nodes).
     */
    @Test
    void flagDesconhecidaOuMalFormadaTemOComportamentoDaBaselineNosDoisCaminhos() {
        Set<String> conhecidas = MVPComparison.knownFlags();
        assertTrue(conhecidas.contains(NetworkSizing.FLAG));
        assertTrue(ByzantineSweep.knownFlags().contains(NetworkSizing.FLAG));
        // o que a baseline fazia: so "--x=y" entra no mapa; o resto e ignorado; a ultima ocorrencia vence
        Map<String, Map<String, String>> esperado = new LinkedHashMap<>();
        esperado.put("--n-semantic=nodes", Map.of("n-semantic", "nodes"));      // desconhecida: entra, nunca e lida
        esperado.put("--n-semantics", Map.of());                                   // sem '=': ignorada
        esperado.put("--n-semantics nodes", Map.of());
        esperado.put("n-semantics=nodes", Map.of());                               // sem '--': ignorada
        esperado.put("--=x", Map.of("", "x"));                                     // como na baseline
        esperado.put("--validatorz=4", Map.of("validatorz", "4"));
        esperado.put("--byzantine=0,33", Map.of("byzantine", "0,33"));           // validate-metrics.sh/.ps1
        esperado.put("--validators=", Map.of("validators", ""));
        for (Map.Entry<String, Map<String, String>> e : esperado.entrySet()) {
            String[] args = {e.getKey()};
            assertEquals(e.getValue(), MVPComparison.parseArgs(args), "MVP " + e.getKey());
            assertEquals(e.getValue(), ByzantineSweep.parseArgs(args), "Sweep " + e.getKey());
        }
        String[] repetida = {"--n-semantics=nodes", "--n-semantics=legacy", "--seed=1", "--seed=2"};
        assertEquals(Map.of("n-semantics", "legacy", "seed", "2"), MVPComparison.parseArgs(repetida));
        assertEquals(Map.of("n-semantics", "legacy", "seed", "2"), ByzantineSweep.parseArgs(repetida));
        // AVISO so no stderr, so para o que a baseline ignorava; nada para flags conhecidas
        String[] limpos = {"--protocols=ARQ", "--validators=7", "--n-semantics=legacy"};
        assertEquals("", capturarStderr(() -> MVPComparison.parseArgs(limpos)));
        assertEquals("", capturarStderr(() -> ByzantineSweep.parseArgs(new String[]{"--type=POS", "--validators=10"})));
        String aviso = capturarStderr(() -> MVPComparison.parseArgs(new String[]{"--byzantine=0,33"}));
        assertTrue(aviso.startsWith("AVISO: flag desconhecida '--byzantine'") && aviso.contains("--n-semantics"), aviso);
        String semIgual = capturarStderr(() -> ByzantineSweep.parseArgs(new String[]{"--n-semantics", "nodes"}));
        assertTrue(semIgual.contains("argumento '--n-semantics' ignorado"), semIgual);
        // --n-semantics com valor invalido continua falhando alto, antes de qualquer simulacao ou arquivo
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(new String[]{
                "--protocols=AMBIFY", "--validators=4", "--n-semantics=NODES", "--output=" + tmp.resolve("a")}));
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(new String[]{
                "--protocols=AMBIFY", "--validators=4", "--n-semantics=", "--output=" + tmp.resolve("a2")}));
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(new String[]{
                "--protocols=AMBIFY", "--validators=4", "--n-semantics=nodes ", "--output=" + tmp.resolve("a3")}));
        // n invalido em nodes continua falhando alto
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(new String[]{
                "--protocols=AMBIFY", "--validators=1", "--n-semantics=nodes", "--output=" + tmp.resolve("c")}));
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(new String[]{
                "--type=POS", "--validators=10", "--n-semantics=x", "--out=" + tmp.resolve("d.csv")}));
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(new String[]{
                "--type=POSA", "--validators=1", "--n-semantics=nodes", "--out=" + tmp.resolve("e.csv")}));
        assertFalse(Files.exists(tmp.resolve("a")) || Files.exists(tmp.resolve("a2")) || Files.exists(tmp.resolve("a3"))
                || Files.exists(tmp.resolve("b")) || Files.exists(tmp.resolve("c"))
                || Files.exists(tmp.resolve("d.csv")) || Files.exists(tmp.resolve("e.csv")),
                "nada deve ser gravado quando a configuracao e invalida");
    }

    /**
     * Rodada 3: o erro de digitacao '--n-semantic=nodes' deixa de abortar (a baseline o ignorava) e roda no modo
     * legacy, com o aviso so no stderr; o stdout e o CSV nao mencionam a flag desconhecida.
     */
    @Test
    void flagDesconhecidaNaoAbortaMasAvisaSoNoStderr() throws IOException {
        Path out = tmp.resolve("typo");
        String[] args = {"--protocols=MCO2", "--validators=16", "--trials=0", "--n-semantic=nodes", "--output=" + out};
        java.io.PrintStream so = System.out;
        java.io.ByteArrayOutputStream bufOut = new java.io.ByteArrayOutputStream();
        String err;
        System.setOut(new java.io.PrintStream(bufOut, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            err = capturarStderr(() -> {
                try {
                    MVPComparison.main(args);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } finally {
            System.setOut(so);
        }
        String saida = bufOut.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(err.contains("AVISO: flag desconhecida '--n-semantic'"), err);
        assertFalse(saida.contains("AVISO") || saida.contains("n-semantic="), "aviso fora do stdout");
        assertTrue(saida.contains("n-semantics=legacy"), "modo legacy");
        String csv = Files.readString(out.resolve("mvp-results.csv"));
        assertTrue(csv.contains("n_semantics=legacy") && csv.contains("nodes=15 miners=4 stakeholders=11"), csv);
        assertFalse(csv.contains("AVISO"), "aviso fora do CSV");
    }

    /** Sem linhas '#' e sem colunas cujo cabecalho contem "hash" (hash de identidade do bloco: como nao_regressao.py). */
    private static List<String> semColunasHash(List<String> linhas) {
        List<String> dados = linhas.stream().filter(l -> !l.startsWith("#")).toList();
        String[] cab = dados.get(0).split(",", -1);
        List<String> r = new ArrayList<>();
        for (String l : dados) {
            String[] c = l.split(",", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < c.length; i++) {
                if (i < cab.length && cab[i].toLowerCase(java.util.Locale.ROOT).contains("hash")) {
                    continue;
                }
                sb.append(c[i]).append(';');
            }
            r.add(sb.toString());
        }
        return r;
    }

    /**
     * Rodada 3: {@code validate-metrics.sh/.ps1} passam {@code --byzantine=0,33}, flag que a baseline nunca leu e
     * ignorava. A invocacao tem de TERMINAR e dar o mesmo resultado que sem a flag (ARQ n=7, 5 s, semente 1).
     * A igualdade com a baseline real (325f4e3) e conferida fora do teste (README da N1, rodada 3).
     */
    @Test
    void invocacaoDoValidateMetricsComByzantineTerminaComoSemAFlag() throws IOException {
        Path com = tmp.resolve("com");
        Path sem = tmp.resolve("sem");
        String err = capturarStderr(() -> {
            try {
                MVPComparison.main(new String[]{"--protocols=ARQ", "--validators=7", "--trials=1", "--duration=5",
                        "--seed=1", "--byzantine=0,33", "--output=" + com});
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        MVPComparison.main(new String[]{"--protocols=ARQ", "--validators=7", "--trials=1", "--duration=5",
                "--seed=1", "--output=" + sem});
        assertTrue(err.startsWith("AVISO: flag desconhecida '--byzantine'"), err);
        assertEquals(1, err.lines().filter(l -> l.startsWith("AVISO")).count(), "um unico aviso: " + err);
        for (String nome : new String[]{"mvp-results.csv", "arq-7v-t0.csv"}) {
            List<String> a = semColunasHash(Files.readAllLines(com.resolve(nome)));
            List<String> b = semColunasHash(Files.readAllLines(sem.resolve(nome)));
            assertTrue(a.size() >= 2, nome + " com dados: " + a);
            assertEquals(b, a, nome + ": --byzantine nao muda o resultado (a baseline o ignorava)");
        }
    }

    @Test
    void cabecalhoDoCsvTemLinhasComentadasComADecomposicao() throws IOException {
        for (String sem : new String[]{"legacy", "nodes"}) {
            Path out = tmp.resolve("mvp-" + sem);
            MVPComparison.main(new String[]{"--protocols=ARQ,MCO2,AMBIFY", "--validators=10", "--trials=0",
                    "--duration=10", "--seed=1", "--n-semantics=" + sem, "--output=" + out});
            List<String> linhas = Files.readAllLines(out.resolve("mvp-results.csv"));
            List<String> comentarios = linhas.stream().filter(l -> l.startsWith("#")).toList();
            List<String> dados = linhas.stream().filter(l -> !l.startsWith("#")).toList();
            assertEquals("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std", dados.get(0),
                    "cabecalho de colunas inalterado, so precedido por '#'");
            assertTrue(comentarios.get(0).contains("n_semantics=" + sem), comentarios.toString());
            assertTrue(comentarios.stream().anyMatch(l -> l.contains("protocol=MCO2") && l.contains("nodes=")
                    && l.contains("miners=") && l.contains("stakeholders=") && l.contains("casper_S=")), comentarios.toString());
            assertTrue(comentarios.stream().anyMatch(l -> l.contains("protocol=AMBIFY")
                    && l.contains("parlia_validators=")), comentarios.toString());
        }
        // valores concretos de n=10: legacy 3+7 = 10; nodes 3+7 = 10 (coincidem); o caso que diverge e n=16
        Path out = tmp.resolve("mvp-16");
        MVPComparison.main(new String[]{"--protocols=MCO2", "--validators=16", "--trials=0", "--n-semantics=legacy",
                "--output=" + out});
        assertTrue(Files.readString(out.resolve("mvp-results.csv")).contains("nodes=15 miners=4 stakeholders=11"));
    }
}
