package jabs.ledgerdata;

import java.util.*;

/**
 * Tracker for double-spending attacks in blockchain simulations.
 * 
 * Double-spending occurs when the same transaction is confirmed in multiple
 * conflicting blocks. This is a critical vulnerability metric for evaluating
 * consensus algorithm security.
 * 
 * Metric: Pdv (Probabilidade de dupla-venda / Double-spending Probability)
 * Formula: Pdv = (successful_double_spends / total_double_spend_attempts) * 100%
 * 
 * Expected values:
 * - Arq-REDD+ (voting-based): ~0.1% (very secure)
 * - pBFT (voting-based): ~0.1% (very secure)
 * - Nakamoto (PoW): ~25% (vulnerable with network delays)
 */
public class DoubleSpendTracker {
    
    /**
     * Maps Transaction ID to set of block heights where it appears
     * If a Tx appears in 2+ different blocks, it's a double-spend attempt
     */
    private Map<Object, Set<Integer>> txToBlockHeights;
    
    /**
     * Maps Tx ID to whether both blocks were finalized
     * (finalization = inclusion in canonical chain)
     */
    private Map<Object, Boolean> txFinalizedInMultipleBlocks;
    
    private int doubleSpendAttempts;       // total detected attempts
    private int doubleSpendSuccesses;      // attempts that succeeded (both blocks finalized)
    
    // Detailed tracking for analysis
    private List<DoubleSpendEvent> doubleSpendEvents;
    
    public DoubleSpendTracker() {
        this.txToBlockHeights = new HashMap<>();
        this.txFinalizedInMultipleBlocks = new HashMap<>();
        this.doubleSpendAttempts = 0;
        this.doubleSpendSuccesses = 0;
        this.doubleSpendEvents = new ArrayList<>();
    }
    
    /**
     * Record a transaction in a block
     * Call this for each transaction in each finalized block
     * 
     * @param txId Unique transaction identifier
     * @param blockHeight Height of the block containing this transaction
     * @param blockHash Hash of the block (for identification)
     * @return true if this is a double-spend attempt, false if first occurrence
     */
    public boolean recordTransaction(Object txId, int blockHeight, String blockHash) {
        
        if (!txToBlockHeights.containsKey(txId)) {
            // First time seeing this transaction
            Set<Integer> heights = new HashSet<>();
            heights.add(blockHeight);
            txToBlockHeights.put(txId, heights);
            return false;
        }
        
        // Transaction already seen in another block!
        Set<Integer> previousHeights = txToBlockHeights.get(txId);
        
        // Check if it's the same block (different hash = different block)
        if (!previousHeights.contains(blockHeight)) {
            // Different block at same height = potential chain reorganization
            // OR different heights = definite double-spend
            previousHeights.add(blockHeight);
            
            // This is a double-spend attempt
            doubleSpendAttempts++;
            
            // Log event for analysis
            DoubleSpendEvent event = new DoubleSpendEvent(
                txId, previousHeights, blockHeight, blockHash
            );
            doubleSpendEvents.add(event);
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Confirm that a double-spend was successful
     * (both blocks containing the transaction were finalized)
     * 
     * @param txId Transaction ID that had double-spend
     */
    public void confirmDoubleSpendSuccess(Object txId) {
        if (txToBlockHeights.containsKey(txId) && 
            txToBlockHeights.get(txId).size() > 1) {
            
            Boolean alreadyRecorded = txFinalizedInMultipleBlocks.getOrDefault(txId, false);
            
            if (!alreadyRecorded) {
                doubleSpendSuccesses++;
                txFinalizedInMultipleBlocks.put(txId, true);
            }
        }
    }
    
    /**
     * Check if transaction appears in multiple blocks
     */
    public boolean isInMultipleBlocks(Object txId) {
        return txToBlockHeights.containsKey(txId) && 
               txToBlockHeights.get(txId).size() > 1;
    }
    
    /**
     * Get all block heights where a transaction appears
     */
    public Set<Integer> getBlockHeightsForTx(Object txId) {
        return txToBlockHeights.getOrDefault(txId, new HashSet<>());
    }
    
    /**
     * Get double-spending success probability (percentage)
     * Formula: Pdv = (successful_attempts / total_attempts) * 100
     * 
     * @return Percentage (0-100)
     */
    public double getDoubleSpendSuccessProbability() {
        if (doubleSpendAttempts == 0) {
            return 0.0;
        }
        return (doubleSpendSuccesses / (double) doubleSpendAttempts) * 100.0;
    }
    
    /**
     * Get summary of double-spend events
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== DOUBLE-SPEND ANALYSIS ==========\n");
        sb.append(String.format("Total Attempts: %d\n", doubleSpendAttempts));
        sb.append(String.format("Successful Attacks: %d\n", doubleSpendSuccesses));
        sb.append(String.format("Success Probability (Pdv): %.3f%%\n\n", 
            getDoubleSpendSuccessProbability()));
        
        if (!doubleSpendEvents.isEmpty()) {
            sb.append("Detailed Events:\n");
            for (DoubleSpendEvent event : doubleSpendEvents) {
                sb.append(String.format("  Tx %s: blocks %s (attempted in block %s)\n",
                    event.txId, event.blockHeights, event.newBlockHeight));
            }
        }
        
        sb.append("==========================================\n");
        return sb.toString();
    }
    
    /**
     * Export metrics for CSV analysis
     */
    public String[] exportAsCSV() {
        return new String[]{
            Integer.toString(doubleSpendAttempts),
            Integer.toString(doubleSpendSuccesses),
            Double.toString(getDoubleSpendSuccessProbability())
        };
    }
    
    // ===== GETTERS =====
    
    public int getDoubleSpendAttempts() {
        return doubleSpendAttempts;
    }
    
    public int getDoubleSpendSuccesses() {
        return doubleSpendSuccesses;
    }
    
    public List<DoubleSpendEvent> getDoubleSpendEvents() {
        return Collections.unmodifiableList(doubleSpendEvents);
    }
    
    public int getTotalTransactionsTracked() {
        return txToBlockHeights.size();
    }
    
    public int getTransactionsInMultipleBlocks() {
        return (int) txToBlockHeights.values().stream()
            .filter(heights -> heights.size() > 1)
            .count();
    }
    
    /**
     * Clear obsolete data to prevent memory leaks and performance degradation.
     * Removes transactions from blocks older than the specified height threshold.
     * 
     * @param currentHeight The current blockchain height
     */
    public void clearObsoleteData(int currentHeight) {
        int threshold = currentHeight - 1000;
        
        // Remove transactions that only appear in old blocks
        txToBlockHeights.entrySet().removeIf(entry -> 
            entry.getValue().stream().allMatch(height -> height < threshold));
        
        // Remove from finalized map if tx is no longer tracked
        txFinalizedInMultipleBlocks.keySet().removeIf(txId -> !txToBlockHeights.containsKey(txId));
        
        // Remove old events (keep only recent ones for analysis)
        doubleSpendEvents.removeIf(event -> 
            event.blockHeights.stream().allMatch(h -> h < threshold) && event.newBlockHeight < threshold);
    }
    
    // ===== INNER CLASS: DoubleSpendEvent =====
    
    /**
     * Records a single double-spend event for analysis
     */
    public static class DoubleSpendEvent {
        public Object txId;
        public Set<Integer> blockHeights;
        public int newBlockHeight;
        public String blockHash;
        public long timestamp;
        
        public DoubleSpendEvent(Object txId, Set<Integer> blockHeights, 
                               int newBlockHeight, String blockHash) {
            this.txId = txId;
            this.blockHeights = new HashSet<>(blockHeights);
            this.newBlockHeight = newBlockHeight;
            this.blockHash = blockHash;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("DoubleSpendEvent{tx=%s, blocks=%s, newBlock=%d, hash=%s}",
                txId, blockHeights, newBlockHeight, blockHash);
        }
    }
    
    @Override
    public String toString() {
        return String.format("DoubleSpendTracker{attempts=%d, successes=%d, " +
                           "probability=%.3f%%, txTracked=%d}",
            doubleSpendAttempts, doubleSpendSuccesses, 
            getDoubleSpendSuccessProbability(), getTotalTransactionsTracked());
    }
}
