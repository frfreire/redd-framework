package jabs.ledgerdata;

/**
 * Transaction type enumeration for Arq-REDD+ hybrid network
 * 
 * PUBLIC: Visible to all nodes in the network (standard blockchain behavior)
 * PRIVATE: Visible only to authorized participants (Constellation/Enclave pattern)
 * 
 * This enables the hybrid public/private architecture where:
 * - Carbon credit trading can be public (transparency)
 * - Sensitive governance decisions can be private (confidentiality)
 */
public enum TransactionType {
    /**
     * Public transaction - visible to all network participants
     */
    PUBLIC,
    
    /**
     * Private transaction - visible only to authorized participants
     * Content is encrypted, only hash visible to non-participants
     */
    PRIVATE
}
