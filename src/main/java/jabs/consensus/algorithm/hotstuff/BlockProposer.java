package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;

/**
 * Ponto de extensao do {@link LeaderCore}: o {@code createLeaf(parent, cmd)} do artigo (Yin et al., PODC 2019,
 * alg. 2, fase PREPARE do lider). O nucleo e puro e nao cria bloco; quem tem simulador (o {@code HotStuff}, na
 * tarefa 2b-2) implementa isto com {@code BlockFactory.sampleHotStuffBlockWithTx}.
 *
 * <p>Contrato (conferido por {@link LeaderCore}; violacao lanca {@link IllegalStateException}): o bloco devolvido
 * nao e nulo, tem {@link HotStuffBlock#getFixedParent()} IGUAL (identidade) a {@code parent} e altura
 * {@code parent.getHeight() + 1}. Chamado no maximo UMA vez por visao.
 */
@FunctionalInterface
public interface BlockProposer {
    /**
     * @param view visao que o lider conduz
     * @param parent bloco a estender (o do highQC; o genesis se nao ha highQC)
     * @return novo bloco, filho direto de {@code parent}
     */
    HotStuffBlock propose(long view, HotStuffBlock parent);
}
