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
    STALE_TIMEOUT,
    /** Proposta cujo valor falha {@code valid(h, v)}: nao e ignorada, o no prevota nil (linhas 22-33). */
    INVALID_VALUE,
    /**
     * Altura ou round alem do teto de retencao ({@code maxFutureHorizon}): descartada SEM armazenar (nao ocupa a
     * retencao). Paridade com o {@code maxFutureViewHorizon} do HotStuff (tarefa PARIDADE-teto-retencao-pbft-tendermint).
     */
    FUTURE_BEYOND_HORIZON
}
