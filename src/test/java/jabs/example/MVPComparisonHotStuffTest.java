package jabs.example;

import jabs.config.ByzantineConfig;
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
import java.util.Set;

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
        assertTrue(MVPComparison.knownFlags().contains("attack"));
        assertTrue(MVPComparison.knownFlags().contains("byzantine-count"));
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
    void soEquivocationEAceitoParaHotStuffOsDemaisFalhamAltoAntesDeCriarSaida(@TempDir Path tmp) {
        for (String ataque : new String[] {"SILENT", "WITHHOLD", "DOUBLE_SIGN", "x", ""}) {
            Path out = tmp.resolve("ataque-" + ataque);
            String[] a = args(fixture(), "--protocols=HOTSTUFF", "--validators=4", "--trials=1", "--attack=" + ataque,
                    "--byzantine-count=1", "--output=" + out);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a),
                    ataque);
            assertTrue(e.getMessage().contains(ataque.isEmpty() ? "ataque desconhecido" : "ataque"), e.getMessage());
            assertTrue(Files.notExists(out), "falha antes de criar saida: " + ataque);
        }
        // mensagens claras: ataque conhecido-nao-implementado x desconhecido
        assertEquals("ataque SILENT nao implementado para HOTSTUFF (so EQUIVOCATION)", assertThrows(
                IllegalArgumentException.class,
                () -> MVPComparison.resolveHotStuffAttack(List.of("HOTSTUFF"), Map.of("attack", "SILENT"))).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.resolveHotStuffAttack(List.of("HOTSTUFF"), Map.of("attack", "x"))).getMessage()
                .contains("ataque desconhecido 'x' para HOTSTUFF. Validos:"));
        // aceitos
        assertEquals(ByzantineConfig.AttackType.EQUIVOCATION,
                MVPComparison.resolveHotStuffAttack(List.of("HOTSTUFF"), Map.of("attack", "EQUIVOCATION")));
        assertEquals(ByzantineConfig.AttackType.EQUIVOCATION,
                MVPComparison.resolveHotStuffAttack(List.of("HOTSTUFF"), Map.of("attack", "equivocation")));
        // sem ataque: ausente, none, nenhum (o caminho honesto de sempre)
        for (Map<String, String> cli : List.of(Map.<String, String>of(), Map.of("attack", "none"),
                Map.of("attack", "NONE"), Map.of("attack", "nenhum"))) {
            assertEquals(ByzantineConfig.AttackType.NONE, MVPComparison.resolveHotStuffAttack(List.of("HOTSTUFF"), cli));
        }
        // protocolo sem HotStuff: a flag segue ignorada como antes (inclusive ataque desconhecido)
        assertEquals(ByzantineConfig.AttackType.NONE,
                MVPComparison.resolveHotStuffAttack(List.of("ARQ"), Map.of("attack", "EQUIVOCATION")));
        // HotStuff misturado a outro protocolo com ataque ativo: os outros rodariam honestos sob o mesmo rotulo
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.resolveHotStuffAttack(
                List.of("ARQ", "HOTSTUFF"), Map.of("attack", "EQUIVOCATION")));
    }

    @Test
    void attackForMainNaoTocaOEnumSemAtaqueEDelegaComHotStuff() {
        // sem --attack, ou sem HOTSTUFF: null (o enum AttackType nao e tocado no main; D3a-2a-2d-2, ver attackForMain)
        assertNull(MVPComparison.attackForMain(List.of("HOTSTUFF"), Map.of()));
        assertNull(MVPComparison.attackForMain(List.of("ARQ", "AMBIFY"), Map.of()));
        assertNull(MVPComparison.attackForMain(List.of("ARQ"), Map.of("attack", "EQUIVOCATION")));
        assertNull(MVPComparison.attackForMain(List.of("ARQ"), Map.of("attack", "x-desconhecido")));
        // com --attack e HOTSTUFF: mesma regra de resolveHotStuffAttack (inclusive a recusa de misto e de ataque nao implementado)
        assertEquals(ByzantineConfig.AttackType.EQUIVOCATION,
                MVPComparison.attackForMain(List.of(" hotstuff "), Map.of("attack", "equivocation")));
        assertEquals(ByzantineConfig.AttackType.NONE,
                MVPComparison.attackForMain(List.of("HOTSTUFF"), Map.of("attack", "none")));
        assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.attackForMain(List.of("HOTSTUFF"), Map.of("attack", "SILENT")));
        assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.attackForMain(List.of("ARQ", "HOTSTUFF"), Map.of("attack", "EQUIVOCATION")));
        // null e NONE equivalem a "sem ataque" nos consumidores
        assertTrue(MVPComparison.noAttack(null));
        assertTrue(MVPComparison.noAttack(ByzantineConfig.AttackType.NONE));
        assertTrue(!MVPComparison.noAttack(ByzantineConfig.AttackType.EQUIVOCATION));
        assertEquals(List.of(), MVPComparison.attackLines(null, 0));
        assertEquals(0, MVPComparison.resolveByzantineCount(List.of("ARQ"), null, Map.of("byzantine-count", "3"), List.of(4)));
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.resolveByzantineCount(List.of("HOTSTUFF"), null,
                Map.of("byzantine-count", "1"), List.of(4)));
    }

    @Test
    void contagemBizantinaInvalidaFalhaAltoAntesDeCriarSaida(@TempDir Path tmp) {
        String[][] ruins = {
                {"EQUIVOCATION", null}, {"EQUIVOCATION", ""}, {"EQUIVOCATION", "0"}, {"EQUIVOCATION", "-1"},
                {"EQUIVOCATION", "1.5"}, {"EQUIVOCATION", "25%"}, {"EQUIVOCATION", "abc"}, {"EQUIVOCATION", "5"},
                {"none", "1"}, {null, "1"}};
        for (String[] r : ruins) {
            Path out = tmp.resolve("cnt-" + r[0] + "-" + r[1]);
            List<String> a = new ArrayList<>(List.of("--protocols=HOTSTUFF", "--validators=4", "--trials=1",
                    "--duration=1", "--output=" + out));
            a.addAll(hsArgs(fixture()));
            if (r[0] != null) {
                a.add("--attack=" + r[0]);
            }
            if (r[1] != null) {
                a.add("--byzantine-count=" + r[1]);
            }
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.main(a.toArray(new String[0])), r[0] + "/" + r[1]);
            assertTrue(e.getMessage().contains("--byzantine-count"), e.getMessage());
            assertTrue(Files.notExists(out), "falha antes de criar saida: " + r[0] + "/" + r[1]);
        }
        // um dos n e menor que a contagem: falha mesmo que os outros caibam
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.resolveByzantineCount(List.of("HOTSTUFF"),
                ByzantineConfig.AttackType.EQUIVOCATION, Map.of("byzantine-count", "5"), List.of(7, 4)));
        assertEquals(2, MVPComparison.resolveByzantineCount(List.of("HOTSTUFF"), ByzantineConfig.AttackType.EQUIVOCATION,
                Map.of("byzantine-count", "2"), List.of(7)));
        // sem ataque: 0 e aceito (ou ausente); sem HOTSTUFF a flag e ignorada como na baseline
        assertEquals(0, MVPComparison.resolveByzantineCount(List.of("HOTSTUFF"), ByzantineConfig.AttackType.NONE,
                Map.of("byzantine-count", "0"), List.of(4)));
        assertEquals(0, MVPComparison.resolveByzantineCount(List.of("ARQ"), ByzantineConfig.AttackType.NONE,
                Map.of("byzantine-count", "3"), List.of(4)));
    }

    private static final Set<Integer> PIN_S1_N10_F3 = Set.of(4, 5, 7);
    private static final Set<Integer> PIN_S2_N7_F2 = Set.of(3, 4);

    /** Implementacao de referencia do sorteio do HOTSTUFF: fluxo dedicado = RandomnessEngine(seed ^ sal), nextInt(n). */
    private static Set<Integer> sorteioReferencia(int n, int f, long seed) {
        jabs.simulator.randengine.RandomnessEngine fluxo =
                new jabs.simulator.randengine.RandomnessEngine(seed ^ ByzantineConfig.BYZANTINE_STREAM_SALT);
        Set<Integer> ids = new java.util.HashSet<>();
        while (ids.size() < f) {
            ids.add(fluxo.nextInt(n));
        }
        return ids;
    }

    @Test
    void sorteioDeBizantinosDoHotStuffUsaFluxoRandomnessEngineDedicadoEAninhado() {
        for (long seed : new long[] {1L, 2L, 5004L}) {
            for (int n : new int[] {4, 7, 10}) {
                Set<Integer> anterior = Set.of();
                for (int f = 0; f <= n; f++) {
                    ByzantineConfig c = ByzantineConfig.withExactCount(n, f, "EQUIVOCATION", seed);
                    Set<Integer> ids = c.getByzantineValidatorIds();
                    assertEquals(f, ids.size());
                    // (i) mesma semente, mesmo conjunto; e e exatamente o fluxo dedicado do RandomnessEngine
                    assertEquals(ids, ByzantineConfig.withExactCount(n, f, "EQUIVOCATION", seed).getByzantineValidatorIds());
                    assertEquals(sorteioReferencia(n, f, seed), ids, "seed=" + seed + " n=" + n + " f=" + f);
                    // (ii) aninhado entre valores de f (ADR-0015 B-38)
                    assertTrue(ids.containsAll(anterior), "aninhado: " + anterior + " em " + ids);
                    anterior = ids;
                    for (int id : ids) {
                        assertTrue(id >= 0 && id < n);
                    }
                    assertEquals(n, c.getTotalValidators());
                    assertEquals("EQUIVOCATION", c.getAttackType());
                    assertEquals(seed, c.getRandomSeed());
                }
            }
        }
        // sementes diferentes dao conjuntos diferentes em algum caso (o fluxo depende da semente)
        boolean algumaDifere = false;
        for (long seed = 1; seed <= 20 && !algumaDifere; seed++) {
            algumaDifere = !ByzantineConfig.withExactCount(10, 3, "EQUIVOCATION", seed).getByzantineValidatorIds()
                    .equals(ByzantineConfig.withExactCount(10, 3, "EQUIVOCATION", seed + 1).getByzantineValidatorIds());
        }
        assertTrue(algumaDifere);
        // fixa os conjuntos: trocar o gerador (ex.: java.util.Random) ou o sal muda ao menos um deles
        assertEquals(PIN_S1_N10_F3, ByzantineConfig.withExactCount(10, 3, "EQUIVOCATION", 1L).getByzantineValidatorIds());
        assertEquals(PIN_S2_N7_F2, ByzantineConfig.withExactCount(7, 2, "EQUIVOCATION", 2L).getByzantineValidatorIds());
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.withExactCount(4, 5, "EQUIVOCATION", 1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.withExactCount(4, -1, "EQUIVOCATION", 1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.withExactCount(0, 0, "EQUIVOCATION", 1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.withExactCount(4, 1, "equivocation", 1));
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
    void ataqueTratadoEmQualquerCaixa(@TempDir Path tmp) {
        for (String v : VARIANTES) {
            // EQUIVOCATION e aceito, SILENT e recusado, em qualquer caixa do nome do protocolo
            assertEquals(ByzantineConfig.AttackType.EQUIVOCATION,
                    MVPComparison.resolveHotStuffAttack(List.of(v), Map.of("attack", "EQUIVOCATION")), "[" + v + "]");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.resolveHotStuffAttack(List.of(v), Map.of("attack", "WITHHOLD")), "[" + v + "]");
            assertEquals("ataque WITHHOLD nao implementado para HOTSTUFF (so EQUIVOCATION)", e.getMessage());
            assertEquals(ByzantineConfig.AttackType.NONE,
                    MVPComparison.resolveHotStuffAttack(List.of(v), Map.of("attack", "none")));
            Path out = tmp.resolve("ataque-" + v.trim());
            String[] a = args(fixture(), "--protocols=" + v, "--validators=4", "--trials=1", "--attack=SILENT",
                    "--byzantine-count=1", "--output=" + out);
            assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(a), "[" + v + "]");
            assertTrue(Files.notExists(out), "falha antes de criar saida: [" + v + "]");
        }
    }

    /** Roda o MVPComparison HOTSTUFF com --attack/--byzantine-count (fixture); {@code null} em ataque = honesto. */
    private static void rodaAtaque(Path out, int n, long seed, double duracao, String ataque, String contagem)
            throws IOException {
        List<String> a = new ArrayList<>(List.of("--protocols=HOTSTUFF", "--validators=" + n, "--trials=1",
                "--duration=" + duracao, "--seed=" + seed, "--output=" + out,
                "--csv-out=" + out.resolve("mvp-results.csv")));
        a.addAll(hsArgs(fixture()));
        if (ataque != null) {
            a.add("--attack=" + ataque);
            a.add("--byzantine-count=" + contagem);
        }
        MVPComparison.main(a.toArray(new String[0]));
    }

    @Test
    void equivocationRodaDifereDoHonestoEEDeterministica(@TempDir Path tmp) throws Exception {
        int[][] casos = {{4, 1}, {7, 2}}; // n, f com n = 3f+1
        for (int[] c : casos) {
            int n = c[0];
            String f = String.valueOf(c[1]);
            Path hon = tmp.resolve("hon-" + n);
            Path a1 = tmp.resolve("a1-" + n);
            Path a2 = tmp.resolve("a2-" + n);
            rodaAtaque(hon, n, 3, 10.0, null, null);
            rodaAtaque(a1, n, 3, 10.0, "EQUIVOCATION", f);
            rodaAtaque(a2, n, 3, 10.0, "EQUIVOCATION", f);
            String blocoNome = "hotstuff-" + n + "v-t0.csv";
            String h = semHash(Files.readString(hon.resolve(blocoNome)));
            String x1 = semHash(Files.readString(a1.resolve(blocoNome)));
            String x2 = semHash(Files.readString(a2.resolve(blocoNome)));
            assertEquals(x1, x2, "mesma semente, duas execucoes: CSV por bloco identico (n=" + n + ")");
            assertEquals(Files.readString(a1.resolve("mvp-results.csv")), Files.readString(a2.resolve("mvp-results.csv")),
                    "CSV agregado identico (n=" + n + ")");
            assertTrue(!h.equals(x1), "o ataque tem de mudar o CSV por bloco (n=" + n + ")");
            assertTrue(!Files.readString(hon.resolve("mvp-results.csv")).equals(Files.readString(a1.resolve("mvp-results.csv"))),
                    "o ataque tem de mudar o CSV agregado (n=" + n + ")");
            // o nome do cenario declara ataque e contagem; blocos finalizados (nao so cabecalho)
            List<String> linhas = Files.readAllLines(a1.resolve(blocoNome));
            assertTrue(linhas.get(0).contains("HotStuff - " + n + " validators, EQUIVOCATION f=" + f), linhas.get(0));
            assertTrue(linhas.size() > 2, "ha blocos finalizados sob ataque (n=" + n + ")");
            // o agregado declara o ataque em linha '#' e mantem as colunas comuns e a linha HOTSTUFF medida
            List<String> agg = Files.readAllLines(a1.resolve("mvp-results.csv"));
            assertTrue(agg.get(0).startsWith("# attack=EQUIVOCATION byzantine_count=" + f), agg.get(0));
            assertEquals("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std", agg.get(1));
            assertTrue(agg.get(2).startsWith("HOTSTUFF;" + n + ";1;"), agg.get(2));
            // sem ataque, o agregado nao ganha linha '#' (saida honesta byte a byte a de antes)
            assertTrue(Files.readAllLines(hon.resolve("mvp-results.csv")).get(0).startsWith("project;"));
        }
    }

    @Test
    void contagemDiferenteMudaOResultadoEHonestoNaoEAfetadoPorContagemZero(@TempDir Path tmp) throws Exception {
        Path um = tmp.resolve("f1");
        Path dois = tmp.resolve("f2");
        rodaAtaque(um, 7, 3, 10.0, "EQUIVOCATION", "1");
        rodaAtaque(dois, 7, 3, 10.0, "EQUIVOCATION", "2");
        assertTrue(!semHash(Files.readString(um.resolve("hotstuff-7v-t0.csv")))
                .equals(semHash(Files.readString(dois.resolve("hotstuff-7v-t0.csv")))), "f=1 difere de f=2");
        // --attack=none com --byzantine-count=0 == honesto, byte a byte
        Path hon = tmp.resolve("hon");
        Path zero = tmp.resolve("zero");
        rodaAtaque(hon, 4, 3, 5.0, null, null);
        rodaAtaque(zero, 4, 3, 5.0, "none", "0");
        assertEquals(semHash(Files.readString(hon.resolve("hotstuff-4v-t0.csv"))),
                semHash(Files.readString(zero.resolve("hotstuff-4v-t0.csv"))));
        assertEquals(Files.readString(hon.resolve("mvp-results.csv")), Files.readString(zero.resolve("mvp-results.csv")));
    }

    @Test
    void falhaDeTrialSobAtaqueAindaAbortaSemLinhaDeZeros(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("curto-ataque");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rodaAtaque(out, 4, 7, 0.0001, "EQUIVOCATION", "1"));
        assertTrue(e.getMessage().contains("trial HOTSTUFF falhou"), e.getMessage());
        List<String> linhas = Files.readAllLines(out.resolve("mvp-results.csv"));
        assertTrue(linhas.stream().noneMatch(l -> l.toUpperCase().startsWith("HOTSTUFF")), linhas.toString());
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
