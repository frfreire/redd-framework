package jabs.consensus.config;

import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assinatura e overhead do voto: parametros obrigatorios sem padrao (ADR-0010 PI-08/PI-16). Valores: so de teste. */
class TendermintConsensusConfigTest {
    private static TendermintConsensusConfig.Builder semOverhead() {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .maxFutureHorizon(1000L)
                .batchSize(20)
                .txSizeBytes(512)
                .signatureSizeBytes(64) // FIXTURE somente de teste, nao e valor de pesquisa
                .proposerSignatureSizeBytes(80); // FIXTURE somente de teste, nao e valor de pesquisa
    }

    private static TendermintConsensusConfig.Builder semAssinaturaProponente() {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .maxFutureHorizon(1000L)
                .batchSize(20)
                .txSizeBytes(512)
                .messageOverheadBytes(10) // FIXTURE somente de teste, nao e valor de pesquisa
                .signatureSizeBytes(64); // FIXTURE somente de teste, nao e valor de pesquisa
    }

    private static TendermintConsensusConfig.Builder semAssinatura() {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .maxFutureHorizon(1000L)
                .batchSize(20)
                .txSizeBytes(512)
                .messageOverheadBytes(10) // FIXTURE somente de teste, nao e valor de pesquisa
                .proposerSignatureSizeBytes(80); // FIXTURE somente de teste, nao e valor de pesquisa
    }

    @Test
    void buildSemMessageOverheadFalhaAlto() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> semOverhead()
                .maxFutureHorizon(1000L).build());
        assertTrue(e.getMessage().contains("messageOverheadBytes"), e.getMessage());
    }

    @Test
    void messageOverheadNegativoRecusado() {
        assertThrows(IllegalArgumentException.class, () -> semOverhead().messageOverheadBytes(-1).build());
    }

    @Test
    void messageOverheadZeroAdmitidoSoSeInformadoEPreservaValor() {
        assertEquals(0, semOverhead().messageOverheadBytes(0).build().getMessageOverheadBytes());
        // FIXTURE somente de teste, nao e valor de pesquisa
        assertEquals(17, semOverhead().messageOverheadBytes(17).build().getMessageOverheadBytes());
    }

    @Test
    void buildSemSignatureSizeFalhaAlto() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> semAssinatura().build());
        assertTrue(e.getMessage().contains("signatureSizeBytes"), e.getMessage());
    }

    @Test
    void zeroENegativoNaoSaoAdmitidos() {
        assertThrows(IllegalArgumentException.class, () -> semAssinatura().signatureSizeBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> semAssinatura().signatureSizeBytes(-1).build());
    }

    @Test
    void valorInformadoEPreservado() {
        // FIXTURE somente de teste, nao e valor de pesquisa
        assertEquals(33, semAssinatura().signatureSizeBytes(33).build().getSignatureSizeBytes());
    }

    @Test
    void buildSemProposerSignatureSizeFalhaAlto() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> semAssinaturaProponente().build());
        assertTrue(e.getMessage().contains("proposerSignatureSizeBytes"), e.getMessage());
        assertTrue(e.getMessage().contains("ADR-0010"), e.getMessage());
        assertTrue(e.getMessage().contains("a definir com o humano"), e.getMessage());
    }

    @Test
    void proposerSignatureZeroENegativoRecusados() {
        assertThrows(IllegalArgumentException.class, () -> semAssinaturaProponente().proposerSignatureSizeBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> semAssinaturaProponente().proposerSignatureSizeBytes(-1).build());
    }

    @Test
    void proposerSignatureEPreservadaESeparadaDaDoVoto() {
        // FIXTURES somente de teste, nao sao valores de pesquisa
        TendermintConsensusConfig c = semAssinaturaProponente().proposerSignatureSizeBytes(33).build();
        assertEquals(33, c.getProposerSignatureSizeBytes());
        assertEquals(64, c.getSignatureSizeBytes(), "assinatura do voto independe da do proponente");
        // So a assinatura do voto informada nao basta: a do proponente continua obrigatoria
        assertThrows(IllegalStateException.class, () -> semAssinaturaProponente().signatureSizeBytes(33).build());
    }
}
