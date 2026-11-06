package jabs.consensus.algorithm.tendermint;

/**
 * {@code <PREVOTE, h, r, id(v)>} ou {@code <PRECOMMIT, h, r, id(v)>}. {@code value == null} e o voto nil.
 *
 * <p>Armadilha: o voto nil CONTA para "2f+1 de qualquer coisa" (linhas 34 e 47, que so armam temporizador) e para
 * "2f+1 nil" (linha 44), mas NUNCA para uma polka ou para o commit de um valor (linhas 28, 36 e 49).
 */
public record TendermintVote<V>(Type type, long height, int round, V value, int sender) {

    public enum Type { PREVOTE, PRECOMMIT }

    public TendermintVote {
        if (type == null) {
            throw new IllegalArgumentException("type obrigatorio");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height deve ser >= 0: " + height);
        }
        if (round < 0) {
            throw new IllegalArgumentException("round deve ser >= 0: " + round);
        }
    }

    public boolean isNil() {
        return value == null;
    }
}
