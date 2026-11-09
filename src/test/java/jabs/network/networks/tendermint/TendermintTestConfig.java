package jabs.network.networks.tendermint;

import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import jabs.consensus.config.TendermintConsensusConfig;

/** Auxiliar de testes do Tendermint. Valores de teste, NAO de pesquisa. */
public final class TendermintTestConfig {
    private TendermintTestConfig() { }

    /** FIXTURE somente de teste, nao e valor de pesquisa (o valor real e a definir com o humano; ADR-0010 PI-16). */
    public static final int TEST_FIXTURE_SIGNATURE_SIZE_BYTES = 64;

    /**
     * FIXTURE somente de teste, nao e valor de pesquisa (o valor real e a definir com o humano; ADR-0010 PI-16).
     * Deliberadamente DIFERENTE de {@link #TEST_FIXTURE_SIGNATURE_SIZE_BYTES}, para que um teste detecte troca/reuso.
     */
    public static final int TEST_FIXTURE_PROPOSER_SIGNATURE_SIZE_BYTES = 80;

    /** FIXTURE somente de teste, nao e valor de pesquisa (o valor real e a definir com o humano; ADR-0010 PI-08). */
    public static final int TEST_FIXTURE_MESSAGE_OVERHEAD_BYTES = 10;

    public static TendermintConsensusConfig config() {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .batchSize(20)
                .txSizeBytes(512)
                .messageOverheadBytes(TEST_FIXTURE_MESSAGE_OVERHEAD_BYTES)
                .signatureSizeBytes(TEST_FIXTURE_SIGNATURE_SIZE_BYTES)
                .proposerSignatureSizeBytes(TEST_FIXTURE_PROPOSER_SIGNATURE_SIZE_BYTES)
                .build();
    }
}
