package jabs.consensus.algorithm.tendermint;

/**
 * {@code <PROPOSAL, h, r, v, vr>} (linha 17). {@code value} nunca e nulo; {@code validRound} e -1 ou um round
 * anterior ({@code -1 <= vr < round}).
 *
 * @param <V> tipo do valor (no adaptador, o bloco; a igualdade do valor e o {@code id(v)} do artigo)
 */
public record TendermintProposal<V>(long height, int round, V value, int validRound, int sender) {

    public TendermintProposal {
        if (height < 0) {
            throw new IllegalArgumentException("height deve ser >= 0: " + height);
        }
        if (round < 0) {
            throw new IllegalArgumentException("round deve ser >= 0: " + round);
        }
        if (value == null) {
            throw new IllegalArgumentException("proposta sem valor");
        }
        if (validRound < -1 || validRound >= round) {
            throw new IllegalArgumentException("validRound deve estar em [-1, round): vr=" + validRound
                    + ", round=" + round);
        }
    }
}
