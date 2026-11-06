package jabs.consensus.algorithm.tendermint;

/**
 * Duracao dos temporizadores: {@code base + round * delta}, por tipo. O artigo exige apenas que cresca com o round
 * (para que, apos o GST, o timeout ultrapasse a latencia real); a forma linear e a do CometBFT.
 *
 * <p><b>Sem valores padrao de proposito:</b> os valores afetam a latencia medida e precisam ser fixados e
 * justificados na pre-registracao (ADR-0002), nao aqui. Todos devem ser &gt; 0 (base) e &gt;= 0 (delta).
 */
public record TendermintTimeouts(double proposeBaseS, double proposeDeltaS,
                                 double prevoteBaseS, double prevoteDeltaS,
                                 double precommitBaseS, double precommitDeltaS) {

    public TendermintTimeouts {
        requireBase("proposeBaseS", proposeBaseS);
        requireBase("prevoteBaseS", prevoteBaseS);
        requireBase("precommitBaseS", precommitBaseS);
        requireDelta("proposeDeltaS", proposeDeltaS);
        requireDelta("prevoteDeltaS", prevoteDeltaS);
        requireDelta("precommitDeltaS", precommitDeltaS);
    }

    /** Duracao em segundos do temporizador {@code kind} no round {@code round} (&gt;= 0). */
    public double durationSeconds(TimeoutKind kind, int round) {
        if (kind == null) {
            throw new IllegalArgumentException("kind obrigatorio");
        }
        if (round < 0) {
            throw new IllegalArgumentException("round deve ser >= 0: " + round);
        }
        switch (kind) {
            case PROPOSE:
                return proposeBaseS + round * proposeDeltaS;
            case PREVOTE:
                return prevoteBaseS + round * prevoteDeltaS;
            case PRECOMMIT:
                return precommitBaseS + round * precommitDeltaS;
            default:
                throw new IllegalStateException("tipo desconhecido: " + kind);
        }
    }

    private static void requireBase(String name, double v) {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " deve ser finito e > 0: " + v);
        }
    }

    private static void requireDelta(String name, double v) {
        if (!(v >= 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " deve ser finito e >= 0: " + v);
        }
    }
}
