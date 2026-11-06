package jabs.consensus.algorithm.tendermint;

/**
 * Motivos pelos quais o nucleo ignora uma entrada. Nada e descartado sem contar. Equivocacao NAO e descarte: a
 * mensagem e guardada e contada a parte ({@link TendermintCore#equivocationsObserved()}).
 */
public enum TendermintDiscardReason {
    /** Mensagem de altura ja decidida. */
    OLD_HEIGHT,
    /** Remetente fora de [0, n). */
    SENDER_OUT_OF_RANGE,
    /** Proposta de quem nao e o proponente de (h, r). */
    WRONG_PROPOSER,
    /** Mensagem identica a uma ja registrada do mesmo remetente. */
    DUPLICATE,
    /** Timeout de (h, r, passo) que o no ja ultrapassou (funcoes 57-65). */
    STALE_TIMEOUT
}
