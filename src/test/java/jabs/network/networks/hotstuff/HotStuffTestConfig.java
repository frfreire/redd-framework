package jabs.network.networks.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.HotStuffConsensusConfig.VoterEncoding;

/** Auxiliar de testes do HotStuff. Valores de teste, NAO de pesquisa (mesmos do HotStuffConsensusConfigTest). */
public final class HotStuffTestConfig {
    private HotStuffTestConfig() { }

    /**
     * FIXTURE DE TESTE do horizonte de visoes futuras. NAO e valor de producao nem calibrado (a definir com o humano);
     * grande o bastante para nao ser atingido nas execucoes honestas e de crash dos testes.
     */
    public static final long FIXTURE_MAX_FUTURE_VIEW_HORIZON = 1000L;

    /** Lote derivado do spec oficial (1024 KB / 512 B = 2048); tamanhos de assinatura e timeout sao de teste. */
    public static HotStuffConsensusConfig config() {
        return config(10.0);
    }

    /** Igual a {@link #config()} com o timeout de visao dado (valor de TESTE, nao de pesquisa). */
    public static HotStuffConsensusConfig config(double viewTimeoutSeconds) {
        return HotStuffConsensusConfig.builder()
                /* FIXTURE de teste: antigo padrao do builder, escrito porque nao ha mais padrao (ADR-0015 Q3-A, Q8-B, Q10-B) */ 
                .leaderPolicy(HotStuffConsensusConfig.LeaderPolicy.ROTATING).signatureMode(HotStuffConsensusConfig.SignatureMode.INDIVIDUAL).messageOverheadBytes(10)
                .voterEncoding(VoterEncoding.BITMAP)
                .viewTimeoutSeconds(viewTimeoutSeconds)
                .maxFutureViewHorizon(FIXTURE_MAX_FUTURE_VIEW_HORIZON)
                .batchSize(HotStuffConsensusConfig.batchSizeFor(1024, 512)).txSizeBytes(512)
                .individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96)
                .build();
    }
}
