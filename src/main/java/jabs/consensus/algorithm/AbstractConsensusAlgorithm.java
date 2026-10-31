package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.ledgerdata.Block;
import jabs.ledgerdata.Tx;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * @param <B>
 * @param <T>
 */
public abstract class AbstractConsensusAlgorithm<B extends Block<B>, T extends Tx<T>>
        implements ConsensusAlgorithm<B, T> {

    /**
     * All accepted blocks (received and agreed) for the consensus algorithm
     */
    protected HashSet<B> confirmedBlocks = new HashSet<>();

    /**
     * All accepted transactions (residing inside accepted blocks)
     */
    protected final HashSet<T> confirmedTxs = new HashSet<>();
    
    /**
     * Byzantine fault configuration for this node/consensus instance
     * Tracks which validators are Byzantine and their attack type
     */
    protected ByzantineConfig byzantineConfig;
    
    /**
     * Metrics tracking for Byzantine Fault Tolerance (BFT) analysis
     */
    protected int acceptedBlocksFromHonest = 0;
    protected int acceptedBlocksFromByzantine = 0;

    /**
     * When a new block is received by the node this function should be called.
     * The consensus algorithm should take actions required accordingly to
     * update the state.
     *
     * @param block Recently received block
     */
    @Override
    public abstract void newIncomingBlock(B block);

    /**
     * Check if the received block is valid according to the state of the chain.
     * This might include difficulty check or signature verification etc.
     *
     * @param block The block to check if it is valid or not
     * @return True if the block is valid according to the current state of the
     * chain
     */
    @Override
    public boolean isBlockValid(B block) { // TODO: This should check that the block meets required difficulty
        return true;
    } // for checking difficulty signature and etc

    /**
     * Check if this block is agreed by the consensus algorithm executed by node.
     *
     * @param block The block to check if it is agreed by consensus algorithm.
     * @return True if the block is accepted by the consensus algorithm.
     */
    @Override
    public boolean isBlockConfirmed(B block) {
        return confirmedBlocks.contains(block);
    }

    /**
     * Check if the provided transaction is inside a block that is agreed by the
     * node consensus algorithm.
     *
     * @param tx The transaction to check if it is included in a agreed block
     *           inside the consensus algorithm.
     * @return True if the transaction is inside a block that is agreed by the
     * consensus algorithm.
     */
    @Override
    public boolean isTxConfirmed(T tx) {
        return confirmedTxs.contains(tx);
    }

    /**
     * Returns the total number of blocks agreed by the consensus algorithm
     * executed by the node
     *
     * @return The total number of blocks agreed by consensus algorithm
     */
    @Override
    public int getNumOfConfirmedBlocks() {
        return confirmedBlocks.size();
    }

    /**
     * Returns the yotal number of accepted transactions by the consensus algorithm
     *
     * @return Total number of accepted transactions by the consensus algorithm
     */
    @Override
    public int getNumOfConfirmedTxs() {
        return confirmedTxs.size();
    }
    
    // ===== BYZANTINE FAULT TOLERANCE (BFT) SUPPORT =====
    
    /**
     * Set Byzantine configuration for this consensus instance
     * @param config ByzantineConfig with Byzantine validator IDs and attack type
     */
    public void setByzantineConfig(ByzantineConfig config) {
        if (config != null) {
            requireAttackImplemented(config.getAttackType());
        }
        this.byzantineConfig = config;
    }

    /**
     * Attacks (besides NONE, which is always accepted and means "no attack") that this protocol
     * implements. Default: none, so a new protocol fails loudly until it declares what it implements.
     * Overridden by each protocol that has real hooks for the attack (explicit failure model, bar
     * criterion 4): a configured attack the protocol does not implement must never be treated as honest
     * behavior in silence.
     */
    protected Set<ByzantineConfig.AttackType> supportedAttacks() {
        return EnumSet.noneOf(ByzantineConfig.AttackType.class);
    }

    private void requireAttackImplemented(String attack) {
        // validateAttackName: the name must be exactly an enum constant (also protects configs built elsewhere)
        ByzantineConfig.AttackType type = ByzantineConfig.AttackType.valueOf(ByzantineConfig.validateAttackName(attack));
        if (type != ByzantineConfig.AttackType.NONE && !supportedAttacks().contains(type)) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " does not implement attack "
                    + type + " (implemented: " + supportedAttacks() + "); refused instead of treating Byzantine"
                    + " nodes as honest in silence");
        }
    }

    /**
     * Expose the ByzantineConfig for external inspection (e.g., global vote aggregation).
     * @return the ByzantineConfig instance or null if not set
     */
    public ByzantineConfig getByzantineConfig() {
        return this.byzantineConfig;
    }
    
    /**
     * Check if a validator is Byzantine according to current config
     * @param validatorId ID of validator to check
     * @return true if validator is Byzantine, false otherwise
     */
    public boolean isByzantineValidator(int validatorId) {
        if (byzantineConfig == null) {
            return false;
        }
        return byzantineConfig.isByzantine(validatorId);
    }
    
    /**
     * Get the current attack type configured for Byzantine nodes
     * @return attack type string, or null if no config
     */
    public String getByzantineAttackType() {
        if (byzantineConfig == null) {
            return null;
        }
        return byzantineConfig.getAttackType();
    }
    
    /**
     * Record that a block was accepted (finalized by consensus)
     * Tracks whether it came from an honest or Byzantine validator
     * Used for calculating BFT metric
     * 
     * @param block The accepted block
     */
    protected void recordBlockAcceptance(B block) {
        // Get proposer/validator ID from block (implementation specific)
        // This is a placeholder - subclasses may override with actual extraction
        int proposerId = getBlockProposer(block);
        
        if (isByzantineValidator(proposerId)) {
            acceptedBlocksFromByzantine++;
            // Criterio 4: sem catch. BFTDebugAggregator.addPart nao tem falha legitima (ENABLED=false retorna;
            // ligado, so insere em colecao concorrente); falha real PROPAGA em vez de sumir.
            jabs.log.BFTDebugAggregator.addPart(block.getHeight(),
                    String.format("proposer=%d (BYZ) priorHonest=%d priorByz=%d", proposerId, acceptedBlocksFromHonest, acceptedBlocksFromByzantine - 1));
        } else {
            acceptedBlocksFromHonest++;
            // try {
            //     jabs.log.BFTDebugAggregator.addPart(block.getHeight(),
            //             String.format("proposer=%d (HONEST) priorHonest=%d priorByz=%d", proposerId, acceptedBlocksFromHonest - 1, acceptedBlocksFromByzantine));
            // } catch (Exception ignored) {}
        }
    }
    
    /**
     * Get the proposer/validator ID from a block
     * This method should be overridden by subclasses if needed
     * 
     * @param block The block to get proposer from
     * @return Proposer/validator ID (default: 0)
     */
    protected int getBlockProposer(B block) {
        // Default implementation - subclasses should override
        // This is a placeholder that can be implemented by specific consensus algorithms
        return 0;
    }
    
    /**
     * Get the Byzantine Fault Tolerance percentage
     * BFT % = (accepted_blocks_from_honest / total_accepted_blocks) * 100
     * 
     * High BFT % means consensus is resilient to Byzantine validators
     * Expected: ~99.9% for Arq-REDD+ with 33% Byzantine
     * 
     * @return BFT percentage (0-100)
     */
    public double getByzantineFaultTolerance() {
        int totalAccepted = acceptedBlocksFromHonest + acceptedBlocksFromByzantine;
        if (totalAccepted == 0) {
            return 100.0;  // No blocks yet, assume safe
        }
        return (acceptedBlocksFromHonest / (double) totalAccepted) * 100.0;
    }
    
    /**
     * Reset BFT tracking counters
     * Useful for running multiple simulations
     */
    public void resetBFTCounters() {
        acceptedBlocksFromHonest = 0;
        acceptedBlocksFromByzantine = 0;
    }
    
    /**
     * Get BFT summary for reporting
     */
    public String getBFTReport() {
        if (byzantineConfig == null) {
            return "No Byzantine configuration set";
        }
        
        int totalAccepted = acceptedBlocksFromHonest + acceptedBlocksFromByzantine;
        return String.format("BFT Report: %s | Honest: %d, Byzantine: %d, Total: %d, " +
                           "BFT%%: %.2f%%",
            byzantineConfig, acceptedBlocksFromHonest, acceptedBlocksFromByzantine, 
            totalAccepted, getByzantineFaultTolerance());
    }
}
