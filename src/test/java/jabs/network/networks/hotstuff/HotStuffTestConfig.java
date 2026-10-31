package jabs.network.networks.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.HotStuffConsensusConfig.VoterEncoding;

/** Auxiliar de testes do HotStuff. Valores de teste, NAO de pesquisa (mesmos do HotStuffConsensusConfigTest). */
public final class HotStuffTestConfig {
    private HotStuffTestConfig() { }

    /** Lote derivado do spec oficial (1024 KB / 512 B = 2048); tamanhos de assinatura e timeout sao de teste. */
    public static HotStuffConsensusConfig config() {
        return HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0)
                .batchSize(HotStuffConsensusConfig.batchSizeFor(1024, 512))
                .individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96)
                .build();
    }
}
