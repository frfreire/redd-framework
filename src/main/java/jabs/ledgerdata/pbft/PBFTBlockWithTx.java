package jabs.ledgerdata.pbft;

import jabs.ledgerdata.BlockWithTx;
import jabs.network.node.nodes.Node;

import java.util.HashSet;
import java.util.Set;

/**
 * PBFT Block with transaction support for the Tt (Transaction Confirmation Latency) metric.
 * Extends PBFTBlock and implements BlockWithTx to enable transaction tracking.
 */
public class PBFTBlockWithTx extends PBFTBlock implements BlockWithTx<PBFTTx> {
    private final Set<PBFTTx> transactions;

    public PBFTBlockWithTx(int size, int height, double creationTime, Node creator, 
                           PBFTBlock parent, Set<PBFTTx> transactions) {
        super(size, height, creationTime, creator, parent);
        this.transactions = transactions != null ? new HashSet<>(transactions) : new HashSet<>();
    }

    @Override
    public Set<PBFTTx> getTxs() {
        return new HashSet<>(transactions);
    }
    
    public int getTransactionCount() {
        return transactions.size();
    }
}
