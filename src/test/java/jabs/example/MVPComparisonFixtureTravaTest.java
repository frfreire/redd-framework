package jabs.example;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.TendermintConsensusConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trava de fixture (HS-CB-fase2, B2; decisao D4): HOTSTUFF e TENDERMINT na MESMA execucao exigem o mesmo lote e
 * tx-size. Todos os valores sao FIXTURE DE TESTE (nao sao calibracao nem valor de pesquisa; a definir com o humano).
 * O par canonico vem de {@link FixtureCanonica} (um unico arquivo, compartilhado com os scripts).
 */
class MVPComparisonFixtureTravaTest {
    /** Demais flags --hs-* (sem lote/tx): FIXTURE DE TESTE. */
    private static final String[] HS_RESTO = {
            "--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-individual-signature-size=64",
            "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96", "--hs-message-overhead=10",
            "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING", "--hs-voter-encoding=BITMAP"};
    /** Demais flags --tm-* (sem lote/tx): FIXTURE DE TESTE. */
    private static final String[] TM_RESTO = {
            "--tm-propose-timeout=1.0", "--tm-propose-delta=0.5", "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
            "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5", "--tm-signature-size=64",
            "--tm-proposer-signature-size=80", "--tm-message-overhead=10"};

    private static HotStuffConsensusConfig hs(int batch, int tx) {
        return MVPComparison.parseHotStuffConfigIfRequested(List.of("HOTSTUFF"), mapa(batch, tx, "hs"));
    }

    private static TendermintConsensusConfig tm(int batch, int tx) {
        return MVPComparison.parseTendermintConfigIfRequested(List.of("TENDERMINT"), mapa(batch, tx, "tm"));
    }

    private static java.util.Map<String, String> mapa(int batch, int tx, String prefixo) {
        List<String> a = new ArrayList<>(List.of(prefixo.equals("hs") ? HS_RESTO : TM_RESTO));
        a.add("--" + prefixo + "-batch-size=" + batch);
        a.add("--" + prefixo + "-tx-size=" + tx);
        return MVPComparison.parseArgs(a.toArray(new String[0]));
    }

    private static String[] args(Path tmp, String protocolos, int hsBatch, int hsTx, int tmBatch, int tmTx,
            String... extra) {
        List<String> a = new ArrayList<>(List.of("--protocols=" + protocolos, "--validators=4", "--trials=1",
                "--duration=5", "--seed=1", "--output=" + tmp));
        a.addAll(List.of(HS_RESTO));
        a.addAll(List.of(TM_RESTO));
        a.add("--hs-batch-size=" + hsBatch);
        a.add("--hs-tx-size=" + hsTx);
        a.add("--tm-batch-size=" + tmBatch);
        a.add("--tm-tx-size=" + tmTx);
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    private static String stdoutDe(Runnable r) {
        PrintStream antigo = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(antigo);
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void main(String[] a) {
        try {
            MVPComparison.main(a);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void fixtureCanonicaVemDeUmUnicoArquivoEEIgualParaOsDoisProtocolos() {
        assertEquals(4, FixtureCanonica.flagsLoteETx().length);
        assertTrue(FixtureCanonica.BATCH_SIZE > 0 && FixtureCanonica.TX_SIZE > 0);
        assertEquals(FixtureCanonica.BATCH_SIZE, hs(FixtureCanonica.BATCH_SIZE, FixtureCanonica.TX_SIZE).getBatchSize());
        assertEquals(FixtureCanonica.TX_SIZE, tm(FixtureCanonica.BATCH_SIZE, FixtureCanonica.TX_SIZE).getTxSizeBytes());
    }

    @Test
    void loteDiferenteDisparaATravaComMensagemEmPortuguesEFlagDeOverride() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.fixtureGuard(hs(20, 512), tm(10, 512), null));
        assertTrue(e.getMessage().contains("a definir com o humano"), e.getMessage());
        assertTrue(e.getMessage().contains("--allow-fixture-mismatch"), e.getMessage());
        assertTrue(e.getMessage().contains("HOTSTUFF batch=20") && e.getMessage().contains("TENDERMINT batch=10"), e.getMessage());
    }

    @Test
    void txSizeDiferenteDisparaATrava() {
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.fixtureGuard(hs(20, 512), tm(20, 256), null));
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.fixtureGuard(hs(20, 512), tm(20, 256), "false"));
    }

    @Test
    void valoresIguaisNaoDisparamENaoDeixamLinha() {
        assertEquals(List.of(), MVPComparison.fixtureGuard(hs(20, 512), tm(20, 512), null));
        assertEquals(List.of(), MVPComparison.fixtureGuard(hs(7, 99), tm(7, 99), "true")); // override sem divergencia: nada a registrar
    }

    @Test
    void umSoProtocoloNaoEAfetado() {
        assertEquals(List.of(), MVPComparison.fixtureGuard(hs(20, 512), null, null));
        assertEquals(List.of(), MVPComparison.fixtureGuard(null, tm(10, 256), null));
        assertEquals(List.of(), MVPComparison.fixtureGuard(null, null, "true"));
    }

    @Test
    void overrideInvalidoFalhaAlto() {
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.fixtureGuard(hs(20, 512), tm(20, 512), "sim"));
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.parseAllowFixtureMismatch("TRUE"));
    }

    @Test
    void overridePassaEDeixaRastroNoStdoutENosDoisCabecalhos(@TempDir Path tmp) throws Exception {
        String[] a = args(tmp, "HOTSTUFF,TENDERMINT", 20, 512, 10, 512, "--allow-fixture-mismatch=true");
        String out = stdoutDe(() -> main(a));
        assertTrue(out.contains("AVISO: fixture-mismatch AUTORIZADO"), out);
        for (String csv : new String[] {"mvp-results.csv", "mvp-results-cb.csv"}) {
            String conteudo = Files.readString(tmp.resolve(csv));
            long linhas = conteudo.lines().filter(l -> l.startsWith("# fixture-mismatch AUTORIZADO")).count();
            assertEquals(1, linhas, csv);
            assertTrue(conteudo.contains("HOTSTUFF batch=20 tx-size=512 x TENDERMINT batch=10 tx-size=512"), csv);
        }
        // o rastro vem ANTES do cabecalho de colunas (linhas '#' do cabecalho)
        String agregado = Files.readString(tmp.resolve("mvp-results.csv"));
        assertTrue(agregado.indexOf("# fixture-mismatch") < agregado.indexOf("project;n;trials"), agregado);
    }

    @Test
    void semOverrideAExecucaoFalhaAltoAntesDeCriarSaida(@TempDir Path tmp) {
        Path saida = tmp.resolve("nao-deve-existir");
        String[] a = args(saida, "HOTSTUFF,TENDERMINT", 20, 512, 10, 512);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> main(a));
        assertTrue(e.getMessage().contains("a definir com o humano"), e.getMessage());
        assertFalse(Files.exists(saida), "a trava tem de falhar ANTES de criar a pasta de saida");
    }

    @Test
    void fixtureCanonicaNosDoisProtocolosRodaSemRastroNoCabecalho(@TempDir Path tmp) throws Exception {
        String[] a = args(tmp, "HOTSTUFF,TENDERMINT", FixtureCanonica.BATCH_SIZE, FixtureCanonica.TX_SIZE,
                FixtureCanonica.BATCH_SIZE, FixtureCanonica.TX_SIZE);
        main(a);
        for (String csv : new String[] {"mvp-results.csv", "mvp-results-cb.csv"}) {
            assertFalse(Files.readString(tmp.resolve(csv)).contains("fixture-mismatch"), csv);
        }
    }

    @Test
    void execucaoSoComTendermintIgnoraLoteDoHotStuffENaoDeixaRastro(@TempDir Path tmp) throws Exception {
        // flags --hs-* de lote divergente estao na linha de comando, mas HOTSTUFF nao esta em --protocols: sem trava
        main(args(tmp, "TENDERMINT", 20, 512, 10, 512));
        assertFalse(Files.readString(tmp.resolve("mvp-results.csv")).contains("fixture-mismatch"));
        assertFalse(Files.readString(tmp.resolve("mvp-results-cb.csv")).contains("fixture-mismatch"));
    }

    @Test
    void execucaoSoComHotStuffIgnoraLoteDoTendermintENaoDeixaRastro(@TempDir Path tmp) throws Exception {
        main(args(tmp, "HOTSTUFF", 20, 512, 10, 256));
        assertFalse(Files.readString(tmp.resolve("mvp-results.csv")).contains("fixture-mismatch"));
        assertFalse(Files.readString(tmp.resolve("mvp-results-cb.csv")).contains("fixture-mismatch"));
    }

    @Test
    void flagDeOverrideEConhecida() {
        assertTrue(MVPComparison.knownFlags().contains("allow-fixture-mismatch"));
    }
}
