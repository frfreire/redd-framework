package jabs.consensus.algorithm.hotstuff;

/**
 * Motivo pelo qual uma replica NAO agiu sobre uma mensagem do HotStuff. Toda mensagem ignorada incrementa o
 * contador do seu motivo (nunca ha descarte em silencio). Aqui so entram descartes que NAO mudam o resultado do
 * protocolo (mensagem que nao pode produzir efeito, duplicada ou invalida); perder uma proposta honesta valida
 * (retencao cheia) nao e descarte: {@code ReplicaCore} lanca {@link IllegalStateException}. O estado da replica (visao, prepareQC, lockedQC, bloco
 * finalizado, arvore local) e os votos NAO mudam por causa de uma mensagem descartada.
 */
public enum DiscardReason {
    /** Mensagem de visao menor que a visao corrente da replica (ou retida e ultrapassada pela visao). */
    OLD_VIEW,
    /** Remetente da proposta/DECIDE nao e o lider da visao da mensagem. */
    WRONG_LEADER,
    /** QC de fase errada, de visao errada para a fase, de outro bloco ou de outro tamanho de rede (n). */
    INCOHERENT_QC,
    /**
     * PREPARE com highQC cujo bloco proposto nao estende o bloco do QC (artigo, alg. 2: "m.node extends from
     * m.justify.node"; a conferir com o artigo).
     */
    NOT_EXTENDING_JUSTIFY,
    /** PREPARE reprovado por {@code safeNode} (nem estende o travado, nem QC mais novo que o travamento). */
    SAFE_NODE_FAILED,
    /**
     * PREPARE cujo bloco tem pai desconhecido (ou desconectado do genesis) na arvore local. LIMITE DECLARADO:
     * a sincronizacao de blocos faltantes nao existe nesta parte; no caminho honesto com malha completa todo
     * bloco proposto chega a todos, entao o contador deve ficar em zero.
     */
    UNKNOWN_PARENT,
    /** PRE_COMMIT/COMMIT/DECIDE para um bloco que esta replica nunca admitiu (nao viu a proposta PREPARE dele). */
    UNKNOWN_BLOCK,
    /** A replica ja respondeu a esta fase nesta visao (segunda proposta da mesma fase e visao). */
    DUPLICATE_PHASE,
    /** Mensagem retida (visao futura ou bloco ainda desconhecido) ja ocupada para a mesma (visao, fase). */
    DUPLICATE_PENDING,
    /** Voto ou NEW_VIEW entregue a um no que nao e o lider correspondente. */
    NOT_VIEW_LEADER,
    /** Votante ou remetente com id fora de [0, n). */
    SENDER_OUT_OF_RANGE,
    /** Voto repetido do mesmo no no mesmo balde (fase, visao, bloco) do agregador do lider. */
    DUPLICATE_VOTE,
    /** NEW_VIEW repetido do mesmo remetente para a mesma visao de destino. */
    DUPLICATE_NEW_VIEW
}
