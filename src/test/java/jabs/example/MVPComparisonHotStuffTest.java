package jabs.example;

import jabs.consensus.config.HotStuffConsensusConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integracao do HotStuff ao harness MVP (D3a-2a-2c-4): TODA flag {@code --hs-*} e obrigatoria e sem padrao, a saida tem
 * as colunas comuns e a corrida e deterministica por semente.
 *
 * <p><b>Todos os valores abaixo sao FIXTURE DE TESTE</b> (para exercitar o codigo): nao sao calibracao nem valor de
 * pesquisa; os valores do estudo sao decisao do humano (ADR-0015: A-04..A-06, A-08..A-10, A-24, A-25 etc.).
 */
class MVPComparisonHotStuffTest {
    /** Nomes das flags, na ordem do config. A lista e conferida contra {@link MVPComparison#knownFlags()}. */
    private static final String[] HS_FLAGS = {
            "hs-view-timeout", "hs-max-future-view-horizon", "hs-batch-size", "hs-tx-size",
            "hs-individual-signature-size", "hs-aggregated-signature-size", "hs-aggregated-share-size",
            "hs-message-overhead", "hs-signature-mode", "hs-leader-policy", "hs-voter-encoding"};

    /** FIXTURE DE TESTE (nao e calibracao). */
    private static Map<String, String> fixture() {
        Map<String, String> m = new HashMap<>();
        m.put("hs-view-timeout", "10.0");
        m.put("hs-max-future-view-horizon", "1000");
        m.put("hs-batch-size", "20");
        m.put("hs-tx-size", "512");
        m.put("hs-individual-signature-size", "64");
        m.put("hs-aggregated-signature-size", "96");
        m.put("hs-aggregated-share-size", "96");
        m.put("hs-message-overhead", "10");
        m.put("hs-signature-mode", "INDIVIDUAL");
        m.put("hs-leader-policy", "ROTATING");
        m.put("hs-voter-encoding", "BITMAP");
        return m;
    }

    private static List<String> hsArgs(Map<String, String> m) {
        List<String> a = new ArrayList<>();
        for (String k : HS_FLAGS) {
            if (m.containsKey(k)) {
                a.add("--" + k + "=" + m.get(k));
            }
        }
        return a;
    }

    private static String[] args(Map<String, String> m, String... base) {
        List<String> a = new ArrayList<>(List.of(base));
        a.addAll(hsArgs(m));
        return a.toArray(new String[0]);
    }

    @Test
    void todasAsFlagsHsSaoConhecidas() {
        assertEquals(11, HS_FLAGS.length);
        for (String f : HS_FLAGS) {
            assertTrue(MVPComparison.knownFlags().contains(f), f);
        }
    }

    @Test
    void configSoEExigidaQuandoHotStuffEstaNaLista() {
        assertNull(MVPComparison.parseHotStuffConfigIfRequested(List.of("ARQ", "TENDERMINT"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.parseHotStuffConfigIfRequested(List.of("HOTSTUFF"), Map.of()));
    }

    @Test
    void flagsPreenchemAConfiguracaoSemPadraoSilencioso() {
        Map<String, String> cli = fixture();
        // valores DIFERENTES dos padroes do config (ROTATING, INDIVIDUAL, 10) para provar que a flag vence e que
        // o padrao do config nao decide: STABLE, AGGREGATED e overhead 17.
        cli.put("hs-leader-policy", "STABLE");
        cli.put("hs-signature-mode", "AGGREGATED");
        cli.put("hs-message-overhead", "17");
        cli.put("hs-view-timeout", "3.5");
        cli.put("hs-max-future-view-horizon", "77");
        cli.put("hs-batch-size", "40");
        cli.put("hs-tx-size", "256");
        cli.put("hs-individual-signature-size", "48");
        cli.put("hs-aggregated-signature-size", "50");
        cli.put("hs-aggregated-share-size", "52");
        HotStuffConsensusConfig c = MVPComparison.parseHotStuffConfigIfRequested(List.of("hotstuff"), cli);
        assertEquals(HotStuffConsensusConfig.LeaderPolicy.STABLE, c.getLeaderPolicy());
        assertEquals(HotStuffConsensusConfig.SignatureMode.AGGREGATED, c.getSignatureMode());
        assertEquals(HotStuffConsensusConfig.VoterEncoding.BITMAP, c.getVoterEncoding());
        assertEquals(17, c.getMessageOverheadBytes());
        assertEquals(3.5, c.getViewTimeoutSeconds());
        assertEquals(77L, c.getMaxFutureViewHorizon());
        assertEquals(40, c.getBatchSize());
        assertEquals(256, c.getTxSizeBytes());
        assertEquals(48, c.getIndividualSignatureSizeBytes());
        assertEquals(50, c.getAggregatedSignatureSizeBytes());
        assertEquals(52, c.getAggregatedShareSizeBytes());
    }

    @Test
    void cadaFlagAusenteOuVaziaFalhaAltoNomeandoAFlag() {
        for (String flag : HS_FLAGS) {
            Map<String, String> ausente = fixture();
            ausente.remove(flag);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.parseHotStuffConfigIfRequested(List.of("HOTSTUFF"), ausente), flag);
            assertTrue(e.getMessage().contains("--" + flag), e.getMessage());
            assertTrue(e.getMessage().contains("a definir com o humano"), e.getMessage());
            Map<String, String> vazia = fixture();
            vazia.put(flag, "  ");
            assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.parseHotStuffConfigIfRequested(List.of("HOTSTUFF"), vazia), flag + " vazia");
        }
    }

    @Test
    void valoresInvalidosFalhamAlto() {
        String[][] ruins = {
                {"hs-view-timeout", "abc"}, {"hs-view-timeout", "0"}, {"hs-view-timeout", "-1"},
                {"hs-view-timeout", "Infinity"}, {"hs-view-timeout", "NaN"},
                {"hs-max-future-view-horizon", "0"}, {"hs-max-future-view-horizon", "-3"},
                {"hs-max-future-view-horizon", "1.5"},
                {"hs-batch-size", "0"}, {"hs-batch-size", "x"}, {"hs-tx-size", "0"}, {"hs-tx-size", "-1"},
                {"hs-individual-signature-size", "0"}, {"hs-aggregated-signature-size", "-1"},
                {"hs-aggregated-share-size", "0"}, {"hs-message-overhead", "-1"}, {"hs-message-overhead", "abc"},
                {"hs-signature-mode", "individual"}, {"hs-signature-mode", "BLS"},
                {"hs-leader-policy", "rotating"}, {"hs-leader-policy", "RANDOM"},
                {"hs-voter-encoding", "LIST"}, {"hs-voter-encoding", "bitmap"}};
        for (String[] r : ruins) {
            Map<String, String> cli = fixture();
            cli.put(r[0], r[1]);
            assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.parseHotStuffConfigIfRequested(List.of("HOTSTUFF"), cli),
                    r[0] + "=" + r[1]);
        }
    }

    @Test
    void loteVezesTxQueEstouraIntFalhaAlto() {
        Map<String, String> cli = fixture();
        cli.put("hs-batch-size", "2000000000");
        cli.put("hs-tx-size", "512");
        assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.parseHotStuffConfigIfRequested(List.of("HOTSTUFF"), cli));
    }

    @Test
    void flagAusenteFalhaNoMainAntesDeCriarSaida(@TempDir Path tmp) {
        for (String flag : HS_FLAGS) {
            Path out = tmp.resolve("sem-" + flag);
            Map<String, String> cli = fixture();
            cli.remove(flag);
            String[] a = args(cli, "--protocols=HOTSTUFF", "--validators=4", "--trials=1", "--duration=1",
                    "--output=" + out);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a), flag);
            assertTrue(e.getMessage().contains("--" + flag), e.getMessage());
            assertTrue(Files.notExists(out), "falha deve ocorrer antes de criar saida: " + flag);
        }
    }

    @Test
    void nMenorQue4FalhaAntesDeCriarSaida(@TempDir Path tmp) {
        Path out = tmp.resolve("n3");
        String[] a = args(fixture(), "--protocols=HOTSTUFF", "--validators=4,3", "--trials=1", "--output=" + out);
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a));
        assertTrue(Files.notExists(out), "n invalido nao deve criar diretorio de saida");
    }

    @Test
    void ataqueDiferenteDeNenhumFalhaAltoParaHotStuff(@TempDir Path tmp) {
        for (String ataque : new String[] {"EQUIVOCATION", "SILENT", "x"}) {
            Path out = tmp.resolve("ataque-" + ataque);
            String[] a = args(fixture(), "--protocols=HOTSTUFF", "--validators=4", "--trials=1", "--attack=" + ataque,
                    "--output=" + out);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a));
            assertEquals("ataque " + ataque + " ainda nao implementado para HOTSTUFF (2d)", e.getMessage());
            assertTrue(Files.notExists(out), "falha antes de criar saida");
        }
        MVPComparison.rejectAttackForHotStuff(List.of("HOTSTUFF"), Map.of("attack", "none"));
        MVPComparison.rejectAttackForHotStuff(List.of("HOTSTUFF"), Map.of("attack", "nenhum"));
        MVPComparison.rejectAttackForHotStuff(List.of("HOTSTUFF"), Map.of());
        // protocolo sem HotStuff: a flag segue ignorada como antes
        MVPComparison.rejectAttackForHotStuff(List.of("ARQ"), Map.of("attack", "EQUIVOCATION"));
    }

    private static void roda(Path out, long seed, double duracao, Map<String, String> cli) throws IOException {
        MVPComparison.main(args(cli, "--protocols=HOTSTUFF", "--validators=4", "--trials=1", "--duration=" + duracao,
                "--seed=" + seed, "--output=" + out, "--csv-out=" + out.resolve("mvp-results.csv")));
    }

    @Test
    void executaHotStuffComAsColunasComuns(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("mvp");
        roda(out, 7, 10.0, fixture());
        List<String> linhas = Files.readAllLines(out.resolve("mvp-results.csv"));
        assertEquals("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std", linhas.get(0));
        String row = linhas.stream().filter(l -> l.startsWith("HOTSTUFF;")).findFirst()
                .orElseThrow(() -> new AssertionError("linha HOTSTUFF ausente: " + linhas));
        String[] c = row.split(";");
        assertEquals(9, c.length);
        assertEquals("4", c[1]);
        assertEquals("1", c[2]);
        assertTrue(Double.parseDouble(c[3]) > 0, "Tb_avg medido: " + row);
        assertTrue(Double.parseDouble(c[5]) > 0, "Cb_avg medido: " + row);
        assertTrue(Double.parseDouble(c[7]) > 0, "Tt_avg medido: " + row);
        // CSV por bloco: cabecalho comum aos demais protocolos, com a granularidade declarada
        List<String> bloco = Files.readAllLines(out.resolve("hotstuff-4v-t0.csv"));
        assertTrue(bloco.get(0).startsWith("#Simulation name: HotStuff - 4 validators"), bloco.get(0));
        assertTrue(bloco.get(0).contains("Cb granularity: bytes received by first-finalizing node per height"), bloco.get(0));
        assertTrue(bloco.get(1).startsWith("Time,NodeID,BlockHeight,BlockHashCode,BlockSize,"), bloco.get(1));
        assertTrue(bloco.get(1).endsWith("TrafficUntilFinalization,Tb_AvgFinalizationTime_s,Cb_AvgTraffic_MB,"
                + "BFT_ByzantineTolerance_pct,Pdv_DoubleSend_SuccessProbability_pct,TrafficConsensusUntilFinalization"),
                bloco.get(1));
        assertTrue(bloco.size() > 2, "ha blocos finalizados");
    }

    @Test
    void zeroBlocosFinalizadosFalhaAltoEmVezDeEscreverZero(@TempDir Path tmp) {
        Path out = tmp.resolve("curto");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> roda(out, 7, 0.0001, fixture()));
        assertTrue(e.getMessage().contains("0 blocos") || e.getMessage().contains("HOTSTUFF"), e.getMessage());
        String csv;
        try {
            csv = Files.readString(out.resolve("mvp-results.csv"));
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        assertTrue(!csv.contains("HOTSTUFF;"), "nenhuma linha HOTSTUFF com zeros inventados: " + csv);
    }

    /** Variacoes de caixa e de espacos que a regra unica ({@code trim} + maiusculas) deve tratar como HOTSTUFF. */
    private static final String[] VARIANTES = {"hotstuff", "HotStuff", "HOTSTUFF", " hotstuff ", "\tHotStuff  "};

    @Test
    void normalizacaoUnicaReconheceHotStuffEmQualquerCaixa() {
        for (String v : VARIANTES) {
            assertTrue(MVPComparison.isHotStuff(v), "[" + v + "]");
            assertEquals("HOTSTUFF", MVPComparison.normalizeProtocol(v));
        }
        for (String n : new String[] {"ARQ", "tendermint", "HOTSTUFF2", "hot stuff", ""}) {
            assertTrue(!MVPComparison.isHotStuff(n), "[" + n + "]");
        }
        assertTrue(!MVPComparison.isHotStuff(null));
    }

    @Test
    void falhaDeTrialERelancadaParaHotStuffEmQualquerCaixa() {
        Exception causa = new IllegalStateException("HOTSTUFF finalizou 0 blocos");
        for (String v : VARIANTES) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> MVPComparison.rethrowIfHotStuff(v, 4, 7L, causa), "[" + v + "]");
            assertTrue(e.getMessage().contains("trial HOTSTUFF falhou (n=4, seed=7)"), e.getMessage());
            assertTrue(e.getCause() == causa);
        }
        // outros protocolos: comportamento anterior (nao relanca; o laco segue)
        for (String outro : new String[] {"ARQ", "arq", "TENDERMINT", "EARTH"}) {
            MVPComparison.rethrowIfHotStuff(outro, 4, 7L, causa);
        }
    }

    @Test
    void runProtocolDespachaHotStuffEmQualquerCaixa(@TempDir Path tmp) {
        for (String v : VARIANTES) {
            // config nula: o ramo HOTSTUFF recusa com a sua mensagem (se caisse em "Unknown protocol", seria outro texto)
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.runProtocol(v, 4, 1L, 1.0, tmp.resolve("x.csv").toString(), true, 5.0, false,
                            null, null), "[" + v + "]");
            assertTrue(e.getMessage().contains("exige flags --hs-*"), "[" + v + "] " + e.getMessage());
        }
    }

    @Test
    void flagsObrigatoriasAusentesEmQualquerCaixa(@TempDir Path tmp) {
        for (String v : VARIANTES) {
            assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.parseHotStuffConfigIfRequested(List.of(v), Map.of()), "[" + v + "]");
            Path out = tmp.resolve("sem-flags-" + v.trim());
            String[] a = {"--protocols=" + v, "--validators=4", "--trials=1", "--duration=1", "--output=" + out};
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a),
                    "[" + v + "]");
            assertTrue(e.getMessage().contains("--hs-"), e.getMessage());
            assertTrue(Files.notExists(out), "falha antes de criar saida: [" + v + "]");
        }
    }

    @Test
    void ataqueRejeitadoEmQualquerCaixa(@TempDir Path tmp) {
        for (String v : VARIANTES) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.rejectAttackForHotStuff(List.of(v), Map.of("attack", "EQUIVOCATION")),
                    "[" + v + "]");
            assertEquals("ataque EQUIVOCATION ainda nao implementado para HOTSTUFF (2d)", e.getMessage());
            MVPComparison.rejectAttackForHotStuff(List.of(v), Map.of("attack", "none"));
            Path out = tmp.resolve("ataque-" + v.trim());
            String[] a = args(fixture(), "--protocols=" + v, "--validators=4", "--trials=1", "--attack=SILENT",
                    "--output=" + out);
            assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a), "[" + v + "]");
            assertTrue(Files.notExists(out), "falha antes de criar saida: [" + v + "]");
        }
    }

    @Test
    void falhaDeTrialNaoGeraLinhaDeZerosNemCsvAgregadoEmQualquerCaixa(@TempDir Path tmp) throws IOException {
        for (String v : VARIANTES) {
            Path out = tmp.resolve("curto-" + v.trim());
            String[] a = args(fixture(), "--protocols=" + v, "--validators=4", "--trials=1", "--duration=0.0001",
                    "--seed=7", "--output=" + out, "--csv-out=" + out.resolve("mvp-results.csv"));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> MVPComparison.main(a),
                    "[" + v + "]");
            assertTrue(e.getMessage().contains("trial HOTSTUFF falhou"), e.getMessage());
            // so o cabecalho foi gravado: nenhuma linha de resultado, de zeros ou nao
            List<String> linhas = Files.readAllLines(out.resolve("mvp-results.csv"));
            assertTrue(linhas.stream().noneMatch(l -> l.toUpperCase().startsWith("HOTSTUFF")),
                    "sem linha agregada: [" + v + "] " + linhas);
            assertEquals("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std", linhas.get(0));
            assertTrue(linhas.stream().filter(l -> !l.startsWith("#") && !l.isBlank()).count() == 1,
                    "so o cabecalho: " + linhas);
        }
    }

    /** Remove a coluna cujo nome contem "hash" (identidade do objeto varia por execucao; mesmo criterio do script). */
    private static String semHash(String csv) {
        StringBuilder out = new StringBuilder();
        int idx = -1;
        for (String linha : csv.split("\n")) {
            if (linha.startsWith("#") || linha.isBlank()) {
                out.append(linha).append('\n');
                continue;
            }
            String[] c = linha.split(",", -1);
            if (idx < 0 && linha.contains("BlockHashCode")) {
                for (int i = 0; i < c.length; i++) {
                    if (c[i].toLowerCase().contains("hash")) {
                        idx = i;
                    }
                }
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

    @Test
    void mesmaSementeDuasExecucoesCsvIdentico(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        roda(a, 5, 10.0, fixture());
        roda(b, 5, 10.0, fixture());
        assertEquals(Files.readString(a.resolve("mvp-results.csv")), Files.readString(b.resolve("mvp-results.csv")));
        assertEquals(semHash(Files.readString(a.resolve("hotstuff-4v-t0.csv"))),
                semHash(Files.readString(b.resolve("hotstuff-4v-t0.csv"))));
    }

    @Test
    void outrosProtocolosNaoExigemFlagsHs(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("arq");
        MVPComparison.main(new String[] {"--protocols=ARQ", "--validators=7", "--trials=1", "--duration=5",
                "--seed=1", "--output=" + out, "--csv-out=" + out.resolve("r.csv")});
        assertTrue(Files.readString(out.resolve("r.csv")).contains("ARQ;7;"));
    }
}
