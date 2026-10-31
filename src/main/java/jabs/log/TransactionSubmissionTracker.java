package jabs.log;

import jabs.ledgerdata.TransactionSubmissionRegistry;

/**
 * Global transaction submission tracker for the current simulation
 * Provides thread-safe access to the transaction registry from anywhere in the codebase
 * 
 * Usage:
 *   TransactionSubmissionTracker.getInstance().registerSubmission(txId, timeSeconds);
 */
public class TransactionSubmissionTracker {
    private static final ThreadLocal<TransactionSubmissionRegistry> THREAD_LOCAL_REGISTRY = 
        new ThreadLocal<>();
    
    /**
     * Set the current simulation's transaction registry
     * Call this at the start of a simulation (or in the scenario)
     */
    public static void setRegistry(TransactionSubmissionRegistry registry) {
        THREAD_LOCAL_REGISTRY.set(registry);
    }
    
    /**
     * Get the current thread's transaction registry
     */
    public static TransactionSubmissionRegistry getInstance() {
        TransactionSubmissionRegistry registry = THREAD_LOCAL_REGISTRY.get();
        if (registry == null) {
            // Create a default registry if none is set
            registry = new TransactionSubmissionRegistry();
            THREAD_LOCAL_REGISTRY.set(registry);
        }
        return registry;
    }
    
    /**
     * Register a transaction submission with the current registry
     */
    public static void registerSubmission(Object txId, double submissionTime) {
        getInstance().registerSubmission(txId, submissionTime);
    }
    
    /**
     * Clear the current registry
     */
    public static void clear() {
        TransactionSubmissionRegistry registry = THREAD_LOCAL_REGISTRY.get();
        if (registry != null) {
            registry.clear();
        }
        THREAD_LOCAL_REGISTRY.set(null);
    }
}
