package jabs.network.access;

import jabs.ledgerdata.Tx;
import jabs.network.node.nodes.Node;

import java.util.*;

/**
 * Access Control Manager for Arq-REDD+ Hybrid Network Architecture
 * 
 * Manages visibility and access rights for private transactions in the hybrid network.
 * Implements the Constellation/Enclave pattern where:
 * - Public transactions are visible to all nodes
 * - Private transactions are visible only to authorized participants
 * - Non-authorized nodes see only transaction hashes
 * 
 * This class integrates with the gossip protocol to filter transaction
 * propagation based on access rights.
 */
public class AccessControlManager {
    /**
     * Map of private transaction hashes to their authorized node IDs
     */
    private final Map<String, Set<Integer>> transactionAccessControl;
    
    /**
     * Track which nodes have requested access to which transactions (for metrics)
     */
    private final Map<Integer, Set<String>> nodeAccessAttempts;
    
    /**
     * Count of access denials (for security metrics)
     */
    private long accessDeniedCount;

    // Limits to avoid unbounded memory growth in long-running simulations
    private static final int MAX_PRIVATE_TX_ENTRIES = 20000;
    private static final int MAX_NODE_ACCESS_ENTRIES = 20000;
    
    public AccessControlManager() {
        // Use insertion-ordered LinkedHashMap with automatic eviction to cap memory
        this.transactionAccessControl = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Set<Integer>> eldest) {
                return size() > MAX_PRIVATE_TX_ENTRIES;
            }
        };
        this.nodeAccessAttempts = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Set<String>> eldest) {
                return size() > MAX_NODE_ACCESS_ENTRIES;
            }
        };
        this.accessDeniedCount = 0;
    }
    
    /**
     * Register a private transaction with its authorized participants
     * @param tx The private transaction to register
     */
    public void registerPrivateTransaction(Tx<?> tx) {
        if (tx.isPrivate()) {
            String txHash = tx.getHash().toString();
            transactionAccessControl.put(txHash, tx.getAuthorizedParticipants());
        }
    }

    /**
     * Clear old entries based on conservative heuristics. This can be called
     * periodically by scenarios or loggers to reduce retained state.
     * @param keepMostRecent how many of the most recent transactions to keep (approx)
     */
    public void clearObsoleteData(int keepMostRecent) {
        if (keepMostRecent <= 0) return;
        if (transactionAccessControl.size() <= keepMostRecent) return;
        Iterator<String> it = transactionAccessControl.keySet().iterator();
        int toRemove = transactionAccessControl.size() - keepMostRecent;
        while (it.hasNext() && toRemove-- > 0) {
            it.next();
            it.remove();
        }
        // Also trim node access attempts map to stay proportional
        if (nodeAccessAttempts.size() > MAX_NODE_ACCESS_ENTRIES) {
            Iterator<Integer> nit = nodeAccessAttempts.keySet().iterator();
            int remove = nodeAccessAttempts.size() - MAX_NODE_ACCESS_ENTRIES;
            while (nit.hasNext() && remove-- > 0) {
                nit.next();
                nit.remove();
            }
        }
    }
    
    /**
     * Check if a node can access a specific transaction
     * @param node The node requesting access
     * @param tx The transaction to access
     * @return true if node is authorized (or transaction is public)
     */
    public boolean canAccess(Node node, Tx<?> tx) {
        // Public transactions are always visible
        if (!tx.isPrivate()) {
            return true;
        }
        
        // Track access attempt
        int nodeId = node.getNodeID();
        nodeAccessAttempts.computeIfAbsent(nodeId, k -> new HashSet<>())
                         .add(tx.getHash().toString());
        
        // Check authorization
        boolean authorized = tx.isVisibleTo(nodeId);
        
        if (!authorized) {
            accessDeniedCount++;
        }
        
        return authorized;
    }
    
    /**
     * Filter a list of transactions based on node's access rights
     * @param node The node to filter for
     * @param transactions List of transactions to filter
     * @return List of transactions visible to the node
     */
    public <T extends Tx<T>> List<T> filterVisibleTransactions(Node node, List<T> transactions) {
        List<T> visible = new ArrayList<>();
        int nodeId = node.getNodeID();
        
        for (T tx : transactions) {
            if (canAccess(node, tx)) {
                visible.add(tx);
            }
        }
        
        return visible;
    }
    
    /**
     * Get the content a node is allowed to see for a transaction
     * For public transactions: full content
     * For private transactions if authorized: full content
     * For private transactions if NOT authorized: only hash
     * 
     * @param node The node requesting content
     * @param tx The transaction
     * @return Content descriptor (FULL or HASH_ONLY)
     */
    public TransactionVisibility getVisibility(Node node, Tx<?> tx) {
        if (canAccess(node, tx)) {
            return TransactionVisibility.FULL;
        }
        return TransactionVisibility.HASH_ONLY;
    }
    
    /**
     * Get statistics on access control
     * @return Map of metric name to value
     */
    public Map<String, Object> getAccessControlMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total_private_transactions", transactionAccessControl.size());
        metrics.put("access_denied_count", accessDeniedCount);
        metrics.put("nodes_with_access_attempts", nodeAccessAttempts.size());
        
        // Calculate average authorized participants per private transaction
        double avgParticipants = transactionAccessControl.values().stream()
            .mapToInt(Set::size)
            .average()
            .orElse(0.0);
        metrics.put("avg_authorized_participants", avgParticipants);
        
        return metrics;
    }
    
    /**
     * Check if a transaction should be propagated to a specific node
     * Used by gossip protocol to filter transaction broadcast
     * 
     * @param node Destination node
     * @param tx Transaction to potentially propagate
     * @return true if transaction should be sent to this node
     */
    public boolean shouldPropagate(Node node, Tx<?> tx) {
        return canAccess(node, tx);
    }
    
    /**
     * Get access denied count (for security analysis)
     * @return Number of times access was denied
     */
    public long getAccessDeniedCount() {
        return accessDeniedCount;
    }
    
    /**
     * Calculate privacy overhead factor
     * Ratio of (nodes that could access) / (total nodes in network)
     * Lower = more privacy, Higher = more visibility
     * 
     * @param totalNodes Total number of nodes in network
     * @return Privacy overhead factor (0.0 to 1.0)
     */
    public double calculatePrivacyOverhead(int totalNodes) {
        if (transactionAccessControl.isEmpty() || totalNodes == 0) {
            return 0.0;
        }
        
        double totalParticipants = transactionAccessControl.values().stream()
            .mapToInt(Set::size)
            .sum();
        
        double maxPossibleParticipants = transactionAccessControl.size() * totalNodes;
        
        return totalParticipants / maxPossibleParticipants;
    }
    
    /**
     * Enum defining transaction visibility levels
     */
    public enum TransactionVisibility {
        FULL,       // Node can see full transaction content
        HASH_ONLY   // Node can see only transaction hash
    }
}
