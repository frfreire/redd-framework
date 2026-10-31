package jabs.ledgerdata.hotstuff;

import jabs.ledgerdata.BlockWithTx;
import jabs.network.node.nodes.Node;

import java.util.HashSet;
import java.util.Set;

/** Bloco HotStuff com transacoes (necessario para a metrica Tt, como no PBFT). */
public final class HotStuffBlockWithTx extends HotStuffBlock implements BlockWithTx<HotStuffTx> {
    private final Set<HotStuffTx> transactions;

    public HotStuffBlockWithTx(int size, int height, double creationTime, Node creator,
                               HotStuffBlock parent, Set<HotStuffTx> transactions) {
        super(size, height, creationTime, creator, parent);
        this.transactions = transactions != null ? new HashSet<>(transactions) : new HashSet<>();
    }

    @Override
    public Set<HotStuffTx> getTxs() {
        return new HashSet<>(transactions);
    }

    public int getTransactionCount() {
        return transactions.size();
    }
}
