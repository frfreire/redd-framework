package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;

/**
 * Acesso do {@link ReplicaCore} aos blocos da arvore local do no, sem que o nucleo conheca rede ou simulador.
 * Quem implementa (o {@code HotStuff}) mantem a {@code LocalBlockTree} e o indice de pais.
 */
public interface BlockAccess {
    /** O bloco foi ADMITIDO por uma proposta PREPARE aceita (ou e o genesis)? */
    boolean contains(HotStuffBlock block);

    /**
     * O bloco pode ser admitido na arvore local? Verdadeiro se ja esta nela, ou se o pai esta na arvore local e
     * conectado ao genesis. Falso = pai desconhecido (nao ha sincronizacao de blocos faltantes nesta parte).
     */
    boolean parentKnown(HotStuffBlock candidate);

    /**
     * {@link ParentLookup} que enxerga a arvore local E o bloco candidato (ainda nao admitido), sem alterar a
     * arvore: a validacao de uma proposta que sera descartada nao deixa rastro.
     */
    ParentLookup lookupFor(HotStuffBlock candidate);

    /** Admite o bloco na arvore local (idempotente). Chamado so para propostas ACEITAS, com {@link #parentKnown}. */
    void admit(HotStuffBlock block);
}
