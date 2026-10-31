package jabs.consensus.algorithm;

import jabs.consensus.config.NakamotoConsensusConfig;
import jabs.ledgerdata.SingleParentBlock;
import jabs.ledgerdata.Tx;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockConfirmationEvent;
import jabs.simulator.event.BlockFinalizationEvent;

import java.util.HashMap;
import java.util.Map;

public class NakamotoConsensus<B extends SingleParentBlock<B>, T extends Tx<T>>
        extends AbstractChainBasedConsensus<B, T> {
    private int longestChainLen = 0;
    private final double averageBlockMiningInterval;
    private final int confirmationDepth;
    // Track traffic per block (in bytes) until finalization
    private final Map<B, Long> blockTraffic = new HashMap<>();

    public NakamotoConsensus(LocalBlockTree<B> localBlockTree, NakamotoConsensusConfig nakamotoConsensusConfig) {
        super(localBlockTree);
        this.averageBlockMiningInterval = nakamotoConsensusConfig.averageBlockMiningInterval();
        this.confirmationDepth = nakamotoConsensusConfig.getConfirmationDepth();
        this.currentMainChainHead = localBlockTree.getGenesisBlock();
    }

    /**
     * Ataques implementados no Nakamoto (o gancho fica em BitcoinNode#processNewBlock): WITHHOLD (retem o bloco
     * minerado) e EQUIVOCATION, que HOJE e tratado como WITHHOLD (lacuna L-xx da auditoria, decisao do humano:
     * mantido sem alteracao). SILENT e DOUBLE_SIGN NAO tem ramo e antes caiam em comportamento honesto sem erro:
     * agora sao recusados em setByzantineConfig.
     */
    @Override
    protected java.util.Set<jabs.config.ByzantineConfig.AttackType> supportedAttacks() {
        return java.util.EnumSet.of(jabs.config.ByzantineConfig.AttackType.WITHHOLD,
                jabs.config.ByzantineConfig.AttackType.EQUIVOCATION);
    }

    @Override
    public void newIncomingBlock(B block) {
        if (block.getHeight() > longestChainLen) {
            this.longestChainLen = block.getHeight();
            this.currentMainChainHead = block;
            this.updateChain();
        }
    }

    @Override
    protected void updateChain() {
        if (currentMainChainHead.getHeight() > confirmationDepth) {
            int heightOfConfirmedBlocks = currentMainChainHead.getHeight() - confirmationDepth;
            B highestConfirmedBlock =  localBlockTree.getAncestorOfHeight(currentMainChainHead, heightOfConfirmedBlocks);
            this.confirmedBlocks = this.localBlockTree.getAllAncestors(highestConfirmedBlock);
            Simulator simulator = this.peerDLTNode.getSimulator();
            double currentTime = simulator.getSimulationTime();
            simulator.putEvent(
                    new BlockConfirmationEvent(currentTime, this.peerDLTNode, highestConfirmedBlock),
                    0);
            // Also emit a BlockFinalizationEvent so metrics collectors receive finalization notifications
            long traffic = blockTraffic.getOrDefault(highestConfirmedBlock, 0L);
            simulator.putEvent(
                    new BlockFinalizationEvent(currentTime, this.peerDLTNode, highestConfirmedBlock, traffic),
                    0);
            // Record acceptance for BFT metrics
            recordBlockAcceptance(highestConfirmedBlock);
        }
    }

    // Allow network/node code to report bytes observed for a block
    public void addBlockTraffic(B block, long bytes) {
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    public double getAverageBlockMiningInterval() {
        return averageBlockMiningInterval;
    }

    @Override
    protected int getBlockProposer(B block) {
        if (block == null || block.getCreator() == null) return 0;
        return block.getCreator().nodeID;
    }
}
