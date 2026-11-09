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
                .batchSize(20)
                .txSizeBytes(512)
                .signatureSizeBytes(64); // FIXTURE somente de teste, nao e valor de pesquisa
    }

    private static TendermintConsensusConfig.Builder semAssinatura() {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .batchSize(20)
                .txSizeBytes(512)
                .messageOverheadBytes(10); // FIXTURE somente de teste, nao e valor de pesquisa
    }

    @Test
    void buildSemMessageOverheadFalhaAlto() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> semOverhead().build());
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
}
