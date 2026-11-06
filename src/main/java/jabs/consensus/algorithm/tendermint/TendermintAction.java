package jabs.consensus.algorithm.tendermint;

/**
 * Efeito que o {@link TendermintCore} pede ao dono (adaptador JABS). Apenas os campos do tipo estao preenchidos.
 *
 * <ul>
 *   <li>{@link Kind#BROADCAST_PROPOSAL} / {@link Kind#BROADCAST_VOTE}: enviar a TODOS OS OUTROS validadores. O nucleo
 *       ja registrou a propria mensagem no seu log; o adaptador NAO deve reentrega-la a este no (autoentrega nao e
 *       trafego, convencao ADR-0006, e duplicaria o voto);</li>
 *   <li>{@link Kind#SCHEDULE_TIMEOUT}: agendar {@code onTimeout(timeout, height, round)} para daqui a
 *       {@link TendermintTimeouts#durationSeconds}. Timeout obsoleto e filtrado pelo nucleo; o adaptador nao precisa
 *       cancelar eventos;</li>
 *   <li>{@link Kind#DECIDE}: o valor {@code value} foi decidido na altura {@code height} (no round {@code round}).</li>
 * </ul>
 */
public record TendermintAction<V>(Kind kind, TendermintProposal<V> proposal, TendermintVote<V> vote,
                                  TimeoutKind timeout, long height, int round, V value) {

    public enum Kind { BROADCAST_PROPOSAL, BROADCAST_VOTE, SCHEDULE_TIMEOUT, DECIDE }

    static <V> TendermintAction<V> proposal(TendermintProposal<V> p) {
        return new TendermintAction<>(Kind.BROADCAST_PROPOSAL, p, null, null, p.height(), p.round(), p.value());
    }

    static <V> TendermintAction<V> vote(TendermintVote<V> v) {
        return new TendermintAction<>(Kind.BROADCAST_VOTE, null, v, null, v.height(), v.round(), v.value());
    }

    static <V> TendermintAction<V> timeout(TimeoutKind kind, long height, int round) {
        return new TendermintAction<>(Kind.SCHEDULE_TIMEOUT, null, null, kind, height, round, null);
    }

    static <V> TendermintAction<V> decide(long height, int round, V value) {
        return new TendermintAction<>(Kind.DECIDE, null, null, null, height, round, value);
    }
}
