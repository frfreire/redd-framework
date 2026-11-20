package jabs.consensus.config;

/**
 * Configuracao do PBFT. {@code maxFutureHorizon}: teto de retencao de votos PREPARE/COMMIT de blocos a mais de
 * {@code maxFutureHorizon} ALTURAS a frente da cabeca da cadeia do no (paridade com o {@code maxFutureViewHorizon} do
 * HotStuff; tarefa PARIDADE-teto-retencao-pbft-tendermint). Obrigatorio, {@code > 0}, SEM padrao: o valor do estudo
 * (1000) e decisao registrada no ADR-0015 e entra explicito em cada chamada.
 */
public class PBFTConsensusConfig implements ConsensusAlgorithmConfig {
    private final long maxFutureHorizon;

    public PBFTConsensusConfig(long maxFutureHorizon) {
        if (maxFutureHorizon <= 0L) {
            throw new IllegalArgumentException("maxFutureHorizon deve ser > 0: " + maxFutureHorizon);
        }
        this.maxFutureHorizon = maxFutureHorizon;
    }

    public long getMaxFutureHorizon() {
        return maxFutureHorizon;
    }
}
