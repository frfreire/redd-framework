package jabs.ledgerdata.tendermint;

import jabs.ledgerdata.BlockWithTx;
import jabs.network.node.nodes.Node;

import java.util.HashSet;
import java.util.Set;

/** Bloco Tendermint com transacoes (necessario para a metrica Tt, como no PBFT e no HotStuff). */
public final class TendermintBlockWithTx extends TendermintBlock implements BlockWithTx<TendermintTx> {
    private final Set<TendermintTx> transactions;

    public TendermintBlockWithTx(int size, int height, double creationTime, Node creator,
                                 TendermintBlock parent, Set<TendermintTx> transactions) {
        super(size, height, creationTime, creator, parent);
        this.transactions = transactions != null ? new HashSet<>(transactions) : new HashSet<>();
    }

    @Override
    public Set<TendermintTx> getTxs() {
        return new HashSet<>(transactions);
    }

    public int getTransactionCount() {
        return transactions.size();
    }
}
