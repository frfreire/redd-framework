package jabs.metrics;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.scenario.HotStuffLANScenario;
import jabs.scenario.TendermintLANScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HS-CB-fase2, PARTE A: soma da rede por bloco, estatisticas e coluna secundaria do primeiro no.
 *
 * <p><b>Todos os valores de configuracao abaixo sao FIXTURE DE TESTE</b> (tamanhos h=32, o=10, s=64, sp=80, lote 20,
 * tx 512 B, semente efetiva 4001/7001 = baseSeed(1)+n*1000+trial(0)): reproduzem os valores de referencia do oraculo
 * independente {@code /tmp/opencode/diag/Extract.java}; nao sao calibracao nem valor de pesquisa (ADR-0015).
 */
class CbRedeMedidaTest {
    // ---- FIXTURE (nao e valor de pesquisa) ----
    private static final int H = 32;          // hash do bloco (constante do codigo; conferida abaixo)
    private static final int O = 10;          // overhead de mensagem
    private static final int S = 64;          // assinatura individual
    private static final int SP = 80;         // assinatura do proponente (Tendermint)
    private static final int BATCH = 20;
    private static final int TX = 512;
    private static final int B = BATCH * TX;  // 10240
    private static final double DURACAO_S = 30.0;

    private static HotStuffConsensusConfig hsConfig() {
        return HotStuffConsensusConfig.builder()
                .viewTimeoutSeconds(10.0).maxFutureViewHorizon(1000).batchSize(BATCH).txSizeBytes(TX)
                .individualSignatureSizeBytes(S).aggregatedSignatureSizeBytes(96).aggregatedShareSizeBytes(96)
                .messageOverheadBytes(O)
                .signatureMode(HotStuffConsensusConfig.SignatureMode.INDIVIDUAL)
                .leaderPolicy(HotStuffConsensusConfig.LeaderPolicy.ROTATING)
                .voterEncoding(HotStuffConsensusConfig.VoterEncoding.BITMAP).build();
    }

    private static TendermintConsensusConfig tmConfig() {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .batchSize(BATCH).txSizeBytes(TX).messageOverheadBytes(O).signatureSizeBytes(S)
                .proposerSignatureSizeBytes(SP).build();
    }

    private static final class Run {
        SimulationMetrics metrics;
        CbRedeMedida.Result result;
    }

    private static Run hotstuff(int n, Path dir) throws IOException {
        Run r = new Run();
        HotStuffLANScenario sc = new HotStuffLANScenario("hs-cb", 1L + n * 1000L, n, DURACAO_S, hsConfig());
        sc.addMetricsLogger(dir.resolve("hs-" + n + ".csv").toString());
        sc.run();
        r.metrics = sc.getMetrics();
        r.result = CbRedeMedida.medir(sc.getNetwork().getAllNodes(), r.metrics);
        return r;
    }

    private static Run tendermint(int n, Path dir) throws IOException {
        Run r = new Run();
        TendermintLANScenario sc = new TendermintLANScenario("tm-cb", 1L + n * 1000L, n, DURACAO_S, tmConfig());
        sc.addMetricsLogger(dir.resolve("tm-" + n + ".csv").toString());
        sc.run();
        r.metrics = sc.getMetrics();
        r.result = CbRedeMedida.medir(sc.getNetwork().getAllNodes(), r.metrics);
        return r;
    }

    /** Q = h + o + ceil(n/8) + (2f+1)*s, com f = (n-1)/3. */
    private static long qc(int n) {
        int f = (n - 1) / 3;
        return H + O + (n + 7) / 8 + (long) (2 * f + 1) * S;
    }

    private static long voto() {
        return H + O + S;
    }

    /** HotStuff, caminho feliz: (n-1)*[B + 4(h+o) + 5Q + 3*voto]. */
    static long analiticoHotStuff(int n) {
        return (long) (n - 1) * (B + 4L * (H + O) + 5 * qc(n) + 3 * voto());
    }

    /** Tendermint, caminho feliz: (n-1)*[B + h+o+sp + 2n*voto]. */
    static long analiticoTendermint(int n) {
        return (long) (n - 1) * (B + H + O + SP + 2L * n * voto());
    }

    // ---------------------------------------------------------------- (a) formula analitica

    @Test
    void constantesDaFixtureConferemComOCodigo() {
        assertEquals(H, HotStuffBlock.HOTSTUFF_BLOCK_HASH_SIZE);
        assertEquals(H, TendermintBlock.TENDERMINT_BLOCK_HASH_SIZE);
    }

    @Test
    void formulaAnaliticaReproduzOsValoresDeReferencia() {
        // 35703/75246 (HotStuff) e 33630/71076 (Tendermint, formula do regime puro; as medias medidas, 33628,7 e
        // 71073,3, ficam um pouco abaixo: ver somaDaRedeTendermintBateComAFormulaEmN4EN7).
        assertEquals(35703L, analiticoHotStuff(4));
        assertEquals(75246L, analiticoHotStuff(7));
        assertEquals(33630L, analiticoTendermint(4));
        assertEquals(71076L, analiticoTendermint(7));
    }

    @Test
    void somaDaRedeHotStuffBateComAFormulaEmN4EN7(@TempDir Path dir) throws IOException {
        for (int n : new int[]{4, 7}) {
            Run r = hotstuff(n, dir);
            assertTrue(r.result.regimeHeights() > 100, "n=" + n + ": janela de regime pequena demais");
            for (int i = 0; i < r.result.regimeHeights(); i++) {
                assertEquals(analiticoHotStuff(n), (long) r.result.networkTotalPerHeight[i],
                        "n=" + n + " altura " + (CbRedeMedida.FIRST_REGIME_HEIGHT + i));
            }
            assertEquals((double) analiticoHotStuff(n), CbRedeMedida.mean(r.result.networkTotalPerHeight), 1e-9);
        }
    }

    @Test
    void somaDaRedeTendermintBateComAFormulaEmN4EN7(@TempDir Path dir) throws IOException {
        for (int n : new int[]{4, 7}) {
            Run r = tendermint(n, dir);
            double[] v = r.result.networkTotalPerHeight;
            assertTrue(v.length > 100, "n=" + n);
            // O regime puro e a moda (p50, p90, p99 coincidem com a formula)...
            assertEquals((double) analiticoTendermint(n), CbRedeMedida.percentile(v, 50), 1e-9);
            // ...e a media fica um pouco ABAIXO (medido: 33628,7 e 71073,3 contra 33630 e 71076): algumas alturas
            // da janela tem menos bytes que o regime puro. A causa NAO foi investigada nesta parte; o teste so
            // fixa que nunca passa da formula e que a diferenca e < 0,1%.
            double media = CbRedeMedida.mean(v);
            assertTrue(media <= analiticoTendermint(n) + 1e-9, "media " + media + " acima da formula");
            assertTrue(media >= analiticoTendermint(n) * 0.999, "media " + media + " > 0,1% abaixo da formula");
        }
    }

    // ---------------------------------------------------------------- (c) coluna do primeiro no

    @Test
    void colunaDoPrimeiroNoFicaIgualEmN4Semente4001(@TempDir Path dir) throws IOException {
        Run hs = hotstuff(4, dir);
        Run tm = tendermint(4, dir);
        // referencia do oraculo (todas as alturas finalizadas, duracao 30 s): 847,448 e 7250,996
        assertEquals(847.4, CbRedeMedida.mean(hs.result.firstNodeAllHeights), 0.05);
        assertEquals(7251.0, CbRedeMedida.mean(tm.result.firstNodeAllHeights), 0.05);
        // e e EXATAMENTE a coluna historica Cb_avg (MB), que o hook atual continua produzindo
        assertEquals(hs.metrics.getAverageTrafficPerBlock() * 1024.0 * 1024.0,
                CbRedeMedida.mean(hs.result.firstNodeAllHeights), 1e-6);
        assertEquals(tm.metrics.getAverageTrafficPerBlock() * 1024.0 * 1024.0,
                CbRedeMedida.mean(tm.result.firstNodeAllHeights), 1e-6);
    }

    // ---------------------------------------------------------------- convencoes provisorias (i) e (ii)

    @Test
    void trafegoSemAlturaEMedidoEForaDasAlturas(@TempDir Path dir) throws IOException {
        // HotStuff: os NEW_VIEW iniciais (sem QC) = n-1 pacotes de 'o' bytes ao lider; nunca descartados, fora das alturas
        for (int n : new int[]{4, 7}) {
            Run hs = hotstuff(n, dir);
            assertEquals((long) (n - 1), hs.result.unattributedPackets, "n=" + n);
            assertEquals((long) (n - 1) * O, hs.result.unattributedBytes, "n=" + n);
        }
        // Tendermint honesto: nenhum voto nil -> 0 MEDIDO (nao -1)
        Run tm = tendermint(4, dir);
        assertEquals(0L, tm.result.unattributedBytes);
        assertEquals(0L, tm.result.unattributedPackets);
    }

    @Test
    void pacotesAposFinalizacaoLocalSaoMedidosEEstaoIncluidosNaSoma(@TempDir Path dir) throws IOException {
        Run hs = hotstuff(4, dir);
        double pos = CbRedeMedida.mean(hs.result.postFinalBytesPerHeight);
        assertTrue(pos > 0.0, "no HotStuff os DECIDE/COMMIT chegam depois de alguns nos finalizarem");
        for (int i = 0; i < hs.result.regimeHeights(); i++) {
            assertTrue(hs.result.postFinalBytesPerHeight[i] <= hs.result.networkTotalPerHeight[i]);
        }
    }

    @Test
    void ledgerSeparaPosFinalSemAlturaEConsenso() {
        NetworkHeightLedger l = new NetworkHeightLedger();
        l.addBlockPacket(5, 100);
        l.addConsensusBytes(5, 60);
        l.markFinalized(5);
        l.addBlockPacket(5, 7);          // chegou depois de finalizar: entra na soma E em (i)
        l.addConsensusBytes(5, 7);
        l.addUnattributed(11);           // (ii)
        NetworkHeightLedger.Cell c = l.heights().get(5L);
        assertEquals(107, c.getTotalBytes());
        assertEquals(67, c.getConsensusBytes());
        assertEquals(7, c.getPostFinalBytes());
        assertEquals(1, c.getPostFinalPackets());
        assertEquals(2, c.getPackets());
        assertEquals(11, l.getUnattributedBytes());
        assertEquals(1, l.getUnattributedPackets());
        assertFalse(l.heights().containsKey(6L), "sem altura nao cria altura");
        assertThrows(IllegalArgumentException.class, () -> l.addBlockPacket(1, -1));
        assertThrows(IllegalArgumentException.class, () -> l.addUnattributed(-1));
    }

    // ---------------------------------------------------------------- (b) estatisticas

    @Test
    void mediaP50P90P99NearestRankEmVetorConhecido() {
        double[] v = {10, 3, 7, 1, 9, 2, 8, 4, 6, 5};            // 1..10 fora de ordem
        assertEquals(5.5, CbRedeMedida.mean(v), 1e-12);
        assertEquals(5.0, CbRedeMedida.percentile(v, 50), 0.0);   // ceil(0,5*10)=5 -> 5o = 5
        assertEquals(9.0, CbRedeMedida.percentile(v, 90), 0.0);   // ceil(9)=9 -> 9
        assertEquals(10.0, CbRedeMedida.percentile(v, 99), 0.0);  // ceil(9,9)=10 -> 10
        double[] c = new double[100];
        for (int i = 0; i < 100; i++) {
            c[99 - i] = i + 1;                                     // 100..1
        }
        assertEquals(50.5, CbRedeMedida.mean(c), 1e-12);
        assertEquals(50.0, CbRedeMedida.percentile(c, 50), 0.0);
        assertEquals(90.0, CbRedeMedida.percentile(c, 90), 0.0);
        assertEquals(99.0, CbRedeMedida.percentile(c, 99), 0.0);
        assertEquals(100.0, CbRedeMedida.percentile(c, 100), 0.0);
        assertEquals(42.0, CbRedeMedida.percentile(new double[]{42}, 99), 0.0);
        assertEquals(1.0, c[99], 0.0, "o vetor de entrada nao e reordenado");
        assertThrows(IllegalArgumentException.class, () -> CbRedeMedida.percentile(v, 0));
        assertThrows(IllegalArgumentException.class, () -> CbRedeMedida.percentile(v, 100.5));
    }

    // ---------------------------------------------------------------- (e) sentinela

    @Test
    void sentinelaNaoMedidoNuncaViraZero() {
        double[] vazio = new double[0];
        assertEquals(-1.0, CbRedeMedida.mean(vazio), 0.0);
        assertEquals(-1.0, CbRedeMedida.percentile(vazio, 50), 0.0);
        assertEquals("-1", CbRedeMedida.fmt(CbRedeMedida.NOT_MEASURED));
        assertEquals("0.000", CbRedeMedida.fmt(0.0), "zero MEDIDO continua zero");
        String linha = CbRedeMedida.csvLineNotMeasured("HOTSTUFF", 4, 1);
        String[] c = linha.split(";");
        assertEquals(CbRedeMedida.CSV_HEADER.split(";").length, c.length, "largura = cabecalho");
        for (int i = 3; i < c.length; i++) {
            assertEquals("-1", c[i], "coluna " + i);
        }
        // pool sem nenhuma altura de regime: tudo -1, inclusive SemAltura quando nao houve trial
        CbRedeMedida.Pool vazioPool = new CbRedeMedida.Pool();
        String l2 = CbRedeMedida.csvLine("TENDERMINT", 4, 1, vazioPool);
        String[] c2 = l2.split(";");
        assertEquals("-1", c2[4]);
        assertEquals("-1", c2[5]);
        assertEquals("-1", c2[16]);
    }

    @Test
    void janelaVaziaDeUmTrialCurtoDaMenosUmEnaoZero() {
        NetworkHeightLedger l = new NetworkHeightLedger();
        l.addBlockPacket(1, 100);
        l.markFinalized(1);
        CbRedeMedida.Result r = CbRedeMedida.medir(List.of(l), new TreeMap<Integer, Long>(Map.of(1, 5L)));
        assertEquals(0, r.regimeHeights());
        CbRedeMedida.Pool p = new CbRedeMedida.Pool();
        p.add(r);
        String[] c = CbRedeMedida.csvLine("HOTSTUFF", 4, 1, p).split(";");
        assertEquals("0", c[3], "0 alturas de regime (contagem)");
        assertEquals("-1", c[4], "media da soma da rede = nao medido");
        assertEquals("-1", c[5]);
        assertEquals("5.000", c[12], "o primeiro no (todas as alturas) tem 1 altura medida");
    }

    @Test
    void semNosHotStuffOuTendermintENaoMedidoEComNosTemResult(@TempDir Path dir) throws IOException {
        // sem nos HotStuff/Tendermint: nao medido (null), nao um Result zerado
        assertNull(CbRedeMedida.medir(List.of(), new SimulationMetrics()));
        // corrida real: Result existe e tem nos
        Run hs = hotstuff(4, dir);
        assertNotNull(hs.result);
        assertEquals(4, hs.result.nodes);
    }
}
