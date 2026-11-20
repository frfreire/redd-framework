package jabs.consensus.config;

/**
 * Configuracao do PBFT. {@code maxFutureHorizon}: teto de retencao de votos PREPARE/COMMIT de blocos a mais de
 * {@code maxFutureHorizon} ALTURAS a frente da cabeca da cadeia do no (paridade com o {@code maxFutureViewHorizon} do
 * HotStuff; tarefa PARIDADE-teto-retencao-pbft-tendermint). Obrigatorio, {@code > 0}, SEM padrao: o valor do estudo
 * (1000) e decisao registrada no ADR-0015 e entra explicito em cada chamada.
 *
 * <p>{@code leaderPolicy} (PBFT-politica-de-lideranca; ADR-0010 PI-14 e ADR-0007): quem propoe cada altura. Obrigatorio e SEM
 * padrao. {@link LeaderPolicy#STABLE} = modo do estudo (o no 0 propoe toda altura; "troca so por falha" NAO existe sem view
 * change, entao um lider que nao propoe ou equivoca trava a cadeia). {@link LeaderPolicy#ROTATING} = ADR-0007 opcao 2 (T6):
 * primario {@code (altura decidida) % n}, modo alternativo.
 */
public class PBFTConsensusConfig implements ConsensusAlgorithmConfig {
    /** Politica de lideranca do PBFT (PI-14). Sem padrao: cada chamada escolhe. */
    public enum LeaderPolicy {
        /** O mesmo no ({@link #STABLE_LEADER_ID}) propoe toda altura. Modo do estudo (PI-14). */
        STABLE,
        /** Primario por altura, {@code (altura decidida) % n} (ADR-0007 opcao 2, T6). Modo alternativo. */
        ROTATING;

        /** Valor da flag {@code --pbft-leader-policy}: exatamente {@code STABLE} ou {@code ROTATING}; outro valor falha alto. */
        public static LeaderPolicy fromFlag(String valor) {
            if ("STABLE".equals(valor)) {
                return STABLE;
            }
            if ("ROTATING".equals(valor)) {
                return ROTATING;
            }
            throw new IllegalArgumentException("--pbft-leader-policy='" + valor + "' invalido. Validos: STABLE, ROTATING (sem padrao)");
        }
    }

    /** Id do lider no modo {@link LeaderPolicy#STABLE}. */
    public static final int STABLE_LEADER_ID = 0;

    private final long maxFutureHorizon;
    private final LeaderPolicy leaderPolicy;

    public PBFTConsensusConfig(long maxFutureHorizon, LeaderPolicy leaderPolicy) {
        if (leaderPolicy == null) {
            throw new IllegalArgumentException("leaderPolicy e obrigatorio e nao tem padrao (STABLE ou ROTATING)");
        }
        this.leaderPolicy = leaderPolicy;
        if (maxFutureHorizon <= 0L) {
            throw new IllegalArgumentException("maxFutureHorizon deve ser > 0: " + maxFutureHorizon);
        }
        this.maxFutureHorizon = maxFutureHorizon;
    }

    public long getMaxFutureHorizon() {
        return maxFutureHorizon;
    }

    public LeaderPolicy getLeaderPolicy() {
        return leaderPolicy;
    }
}
