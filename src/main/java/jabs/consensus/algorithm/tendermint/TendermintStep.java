package jabs.consensus.algorithm.tendermint;

/**
 * Passo dentro de um round (Algoritmo 1, linha 2). A ordem das constantes e a do artigo e e usada nas comparacoes
 * {@code step >= PREVOTE} (linha 36): nao reordenar.
 */
public enum TendermintStep {
    PROPOSE, PREVOTE, PRECOMMIT;

    public boolean atLeast(TendermintStep other) {
        return compareTo(other) >= 0;
    }
}
