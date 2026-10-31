package jabs.ledgerdata.hotstuff;

import jabs.ledgerdata.SingleParentBlock;
import jabs.network.node.nodes.Node;

/**
 * Bloco HotStuff. Nao pode ser {@code final} porque {@link HotStuffBlockWithTx} o estende de fato; por isso e
 * {@code sealed}: a unica subclasse permitida e essa (final), entao ninguem mais altera tamanho ou hash.
 */
public sealed class HotStuffBlock extends SingleParentBlock<HotStuffBlock> permits HotStuffBlockWithTx {
    public static final int HOTSTUFF_BLOCK_HASH_SIZE = 32;

    public HotStuffBlock(int size, int height, double creationTime, Node creator, HotStuffBlock parent) {
        super(size, height, creationTime, creator, parent, HOTSTUFF_BLOCK_HASH_SIZE);
    }
}
