package jabs.ledgerdata.hotstuff;

import jabs.ledgerdata.SingleParentBlock;
import jabs.network.node.nodes.Node;

/**
 * Bloco HotStuff. Nao pode ser {@code final} porque {@link HotStuffBlockWithTx} o estende de fato; por isso e
 * {@code sealed}: a unica subclasse permitida e essa (final), entao ninguem mais altera tamanho ou hash.
 */
public sealed class HotStuffBlock extends SingleParentBlock<HotStuffBlock> permits HotStuffBlockWithTx {
    public static final int HOTSTUFF_BLOCK_HASH_SIZE = 32;

    /**
     * Pai fixado na construcao. {@code Block#getParents()} e uma lista mutavel (divida da base); as decisoes do
     * HotStuff ({@code TreeBlockAccess}, {@code SafetyRule}, {@code LeaderCore}) usam ESTE campo, que nada altera
     * depois. Nulo so no genesis. Mesmo desenho de {@code TendermintBlock#getFixedParent}.
     */
    private final HotStuffBlock fixedParent;

    public HotStuffBlock(int size, int height, double creationTime, Node creator, HotStuffBlock parent) {
        super(size, height, creationTime, creator, parent, HOTSTUFF_BLOCK_HASH_SIZE);
        this.fixedParent = parent;
    }

    /** Pai imutavel (identidade da referencia recebida no construtor); nulo no genesis. */
    public HotStuffBlock getFixedParent() {
        return fixedParent;
    }
}
