package jabs.ledgerdata;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tracking transaction submission times in the simulation.
 * 
 * Used to calculate Transaction Confirmation Latency (Tt):
 * Tt = (timestamp when Tx is finalized in a block) - (timestamp when Tx was submitted)
 * 
 * This class maintains a thread-safe map of transaction IDs to their submission times,
 * supporting efficient lookups during block finalization processing.
 */
public class TransactionSubmissionRegistry {
    
    /**
     * Maps transaction ID (typically hashCode()) to submission time in simulation seconds
     */
    private final Map<Object, Double> txSubmissionTimes;
    
    /**
     * Maximum entries to keep in registry to prevent unbounded growth
     * When exceeded, oldest entries are removed
     */
    private static final int MAX_REGISTRY_SIZE = 50000;
    
    /**
     * Ordered list to track insertion order for LRU eviction
     */
    private final List<Object> insertionOrder;
    
    public TransactionSubmissionRegistry() {
        this.txSubmissionTimes = new ConcurrentHashMap<>();
        this.insertionOrder = Collections.synchronizedList(new ArrayList<>());
    }
    
    /**
     * Register a transaction submission at the current simulation time
     * @param txId Unique transaction identifier (typically tx.hashCode())
     * @param submissionTime Simulation time when transaction was created (seconds)
     */
    public void registerSubmission(Object txId, double submissionTime) {
        if (txId == null) {
            return;
        }
        
        // Add if not already present
        if (!txSubmissionTimes.containsKey(txId)) {
            txSubmissionTimes.put(txId, submissionTime);
            insertionOrder.add(txId);
            
            // Evict oldest entry if registry is too large
            if (insertionOrder.size() > MAX_REGISTRY_SIZE) {
                Object oldestTxId = insertionOrder.remove(0);
                txSubmissionTimes.remove(oldestTxId);
            }
        }
    }
    
    /**
     * Retrieve the submission time for a transaction
     * @param txId Unique transaction identifier
     * @return Submission time in seconds, or null if not found
     */
    public Double getSubmissionTime(Object txId) {
        if (txId == null) {
            return null;
        }
        return txSubmissionTimes.get(txId);
    }
    
    /**
     * Check if a transaction submission time is recorded
     * @param txId Unique transaction identifier
     * @return true if transaction is in registry, false otherwise
     */
    public boolean hasSubmissionTime(Object txId) {
        if (txId == null) {
            return false;
        }
        return txSubmissionTimes.containsKey(txId);
    }
    
    /**
     * Retrieve and remove the submission time for a transaction
     * Useful for cleaning up after processing
     * @param txId Unique transaction identifier
     * @return Submission time in seconds, or null if not found
     */
    public Double removeSubmissionTime(Object txId) {
        if (txId == null) {
            return null;
        }
        insertionOrder.remove(txId);
        return txSubmissionTimes.remove(txId);
    }
    
    /**
     * Clear all entries in the registry
     */
    public void clear() {
        txSubmissionTimes.clear();
        insertionOrder.clear();
    }
    
    /**
     * Get the number of transactions currently in registry
     * @return Registry size
     */
    public int size() {
        return txSubmissionTimes.size();
    }
    
    /**
     * Get all transaction IDs in the registry
     * @return Set of transaction IDs
     */
    public Set<Object> getAllTransactionIds() {
        return new HashSet<>(txSubmissionTimes.keySet());
    }
}
