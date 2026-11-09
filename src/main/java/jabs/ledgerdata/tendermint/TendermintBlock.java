package jabs.ledgerdata.tendermint;

import jabs.ledgerdata.SingleParentBlock;
import jabs.network.node.nodes.Node;

/**
 * Bloco Tendermint. O valor decidido e o bloco: a identidade do objeto e o {@code id(v)} do artigo (o
 * {@link jabs.consensus.algorithm.tendermint.TendermintCore} usa {@code equals}/{@code hashCode} do valor, que aqui
 * sao os de identidade, como no PBFT e no HotStuff).
 *
 * <p>{@code sealed}: a unica subclasse e {@link TendermintBlockWithTx}, necessaria para a metrica Tt.
 */
public sealed class TendermintBlock extends SingleParentBlock<TendermintBlock> permits TendermintBlockWithTx {
    public static final int TENDERMINT_BLOCK_HASH_SIZE = 32;

    /**
     * Pai fixado na construcao. {@code Block#getParents()} e uma lista mutavel (divida da base); a validade
     * {@code valid(h, v)} do Tendermint usa ESTE campo, que nada altera depois. Nulo so no genesis.
     */
    private final TendermintBlock fixedParent;

    public TendermintBlock(int size, int height, double creationTime, Node creator, TendermintBlock parent) {
        super(size, height, creationTime, creator, parent, TENDERMINT_BLOCK_HASH_SIZE);
        this.fixedParent = parent;
    }

    /** Pai imutavel (identidade da referencia recebida no construtor); nulo no genesis. */
    public TendermintBlock getFixedParent() {
        return fixedParent;
    }
}
