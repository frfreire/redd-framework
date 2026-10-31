package jabs.network.node;

/**
 * Enumeration defining types of nodes in Arq-REDD+ hybrid network architecture
 * 
 * Based on architecture diagram:
 * - SIMPLE: Regular nodes that participate in network but don't validate
 * - VALIDATOR: Nodes that participate in consensus voting (pBFT validators)
 * - GENERATOR: Nodes that can propose/generate new blocks
 * 
 * This supports the hybrid public/private transaction architecture where
 * different node types have different visibility and access rights.
 */
public enum NodeType {
    /**
     * Simple node - can send/receive transactions but doesn't participate in consensus
     */
    SIMPLE,
    
    /**
     * Validator node - participates in pBFT voting consensus
     */
    VALIDATOR,
    
    /**
     * Generator node - can propose new blocks (typically also a validator)
     */
    GENERATOR
}
