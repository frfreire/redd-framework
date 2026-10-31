package jabs.ledgerdata;

import java.util.HashSet;
import java.util.Set;

public abstract class Tx<T extends Tx<T>> extends Data {
    /**
     * Transaction type (PUBLIC or PRIVATE) for hybrid network architecture
     */
    protected TransactionType transactionType;
    
    /**
     * Set of authorized node IDs that can view this private transaction
     * Only used when transactionType == PRIVATE
     */
    protected Set<Integer> authorizedParticipants;
    
    /**
     * Hash of encrypted payload for private transactions
     * Public nodes see only this hash, authorized nodes see full content
     */
    protected String payloadHash;
    
    protected Tx(int size, int hashSize) {
        super(size, hashSize);
        this.transactionType = TransactionType.PUBLIC;
        this.authorizedParticipants = new HashSet<>();
        this.payloadHash = null;
    }
    
    /**
     * Check if this transaction is visible to a specific node
     * @param nodeID The node ID to check visibility for
     * @return true if node can see full transaction content
     */
    public boolean isVisibleTo(int nodeID) {
        if (transactionType == TransactionType.PUBLIC) {
            return true;
        }
        return authorizedParticipants.contains(nodeID);
    }
    
    /**
     * Get transaction type
     * @return PUBLIC or PRIVATE
     */
    public TransactionType getTransactionType() {
        return transactionType;
    }
    
    /**
     * Set transaction as private with authorized participants
     * @param authorizedNodeIds Set of node IDs that can view this transaction
     */
    public void setPrivate(Set<Integer> authorizedNodeIds) {
        this.transactionType = TransactionType.PRIVATE;
        this.authorizedParticipants = new HashSet<>(authorizedNodeIds);
        // In real implementation, would encrypt payload here
        // For simulation, we just mark it and track visibility
    }
    
    /**
     * Check if transaction is private
     * @return true if transaction type is PRIVATE
     */
    public boolean isPrivate() {
        return transactionType == TransactionType.PRIVATE;
    }
    
    /**
     * Get authorized participants for private transactions
     * @return Set of authorized node IDs (empty for public transactions)
     */
    public Set<Integer> getAuthorizedParticipants() {
        return new HashSet<>(authorizedParticipants);
    }
    
    /**
     * Get payload hash for private transactions
     * @return Hash string visible to non-authorized nodes
     */
    public String getPayloadHash() {
        if (payloadHash == null && isPrivate()) {
            // Generate deterministic hash from transaction data
            payloadHash = "ENCRYPTED_" + this.getHash().toString();
        }
        return payloadHash;
    }
}

