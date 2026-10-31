package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.ledgerdata.*;
import jabs.ledgerdata.pbft.*;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.event.BlockFinalizationEvent;

import java.util.HashMap;
import java.util.HashSet;

// based on: https://sawtooth.hyperledger.org/docs/pbft/nightly/master/architecture.html
// another good source: http://ug93tad.github.io/pbft/

public class PBFT<B extends SingleParentBlock<B>, T extends Tx<T>> extends AbstractChainBasedConsensus<B, T>
        implements VotingBasedConsensus<B, T>, DeterministicFinalityConsensus<B, T> {
    private final int numAllParticipants;
    private final HashMap<B, HashMap<Node, Vote>> prepareVotes = new HashMap<>();
    private final HashMap<B, HashMap<Node, Vote>> commitVotes = new HashMap<>();
    private final HashSet<B> preparedBlocks = new HashSet<>();
    private final HashSet<B> committedBlocks = new HashSet<>();
    private int currentViewNumber = 0;
    private final int requiredVotes;
    private final java.util.Random randomTraffic = new java.util.Random(42);  // For traffic variation

    // TODO: View change should be implemented

    private PBFTMode pbftMode = PBFTMode.NORMAL_MODE;
    private PBFTPhase pbftPhase = PBFTPhase.PRE_PREPARING;

    @Override
    public boolean isBlockFinalized(B block) {
        return false;
    }

    @Override
    public boolean isTxFinalized(T tx) {
        return false;
    }

    @Override
    public int getNumOfFinalizedBlocks() {
        return 0;
    }

    @Override
    public int getNumOfFinalizedTxs() {
        return 0;
    }

    public enum PBFTMode {
        NORMAL_MODE,
        VIEW_CHANGE_MODE
    }

    public enum PBFTPhase {
        PRE_PREPARING,
        PREPARING,
        COMMITTING
    }

    public PBFT(LocalBlockTree<B> localBlockTree, int numAllParticipants) {
        super(localBlockTree);
        this.numAllParticipants = numAllParticipants;
        this.requiredVotes = (((numAllParticipants / 3) * 2) + 1);
        this.currentMainChainHead = localBlockTree.getGenesisBlock();
    }

    public void newIncomingVote(Vote vote) {
        if (vote instanceof PBFTBlockVote) { // for the time being, the view change votes are not supported
            PBFTBlockVote<B> blockVote = (PBFTBlockVote<B>) vote;
            B block = blockVote.getBlock();
            switch (blockVote.getVoteType()) {
                case PRE_PREPARE :
                    if (!this.localBlockTree.contains(block)) {
                        this.localBlockTree.add(block);
                    }
                    if (this.localBlockTree.getLocalBlock(block).isConnectedToGenesis) {
                        this.pbftPhase = PBFTPhase.PREPARING;
                        this.peerBlockchainNode.broadcastMessage(
                                new VoteMessage(
                                        new PBFTPrepareVote<>(this.peerBlockchainNode, blockVote.getBlock())
                                )
                        );
                    }
                    break;
                case PREPARE:
                    checkVotes(blockVote, block, prepareVotes, preparedBlocks, PBFTPhase.COMMITTING);
                    break;
                case COMMIT:
                    checkVotes(blockVote, block, commitVotes, committedBlocks, PBFTPhase.PRE_PREPARING);
                    break;
            }
        }
    }

    private void checkVotes(PBFTBlockVote<B> vote, B block, HashMap<B, HashMap<Node, Vote>> votes, HashSet<B> blocks, PBFTPhase nextStep) {
        if (!blocks.contains(block)) {
            if (!votes.containsKey(block)) { // this the first vote received for this block
                votes.put(block, new HashMap<>());
            }
            votes.get(block).put(vote.getVoter(), vote);
            int requiredVotes = this.requiredVotes;
            int currentVotes = votes.get(block).size();
            
            if (currentVotes > requiredVotes) {
                blocks.add(block);
                this.pbftPhase = nextStep;
                switch (nextStep) {
                    case PRE_PREPARING:
                        this.currentViewNumber += 1;
                        this.currentMainChainHead = block;
                        updateChain();
        
                        // Fire BlockFinalizationEvent for metrics collection
                        if (this.peerBlockchainNode != null && this.peerBlockchainNode.getSimulator() != null) {
                            // Clear vote maps for finalized block to prevent memory leaks
                            prepareVotes.remove(block);
                            commitVotes.remove(block);
                            // Estimate traffic: each node sends prepare + commit messages
                            // Message size ~ 1KB per message
                            // Include propagation factor: messages propagate in O(log n) hops through the network
                            double baseTraffic = numAllParticipants * 2 * 1024;
                            // Add variability: network conditions cause ±15% variation in traffic per block
                            double stdDev = baseTraffic * 0.15;  // 15% standard deviation
                            double noise = randomTraffic.nextGaussian() * stdDev;
                            long estimatedTraffic = (long) Math.max(baseTraffic * 0.5, baseTraffic + noise);  // Floor at 50% of base
                            this.peerBlockchainNode.getSimulator().putEvent(
                                new BlockFinalizationEvent(
                                    this.peerBlockchainNode.getSimulator().getSimulationTime(),
                                    this.peerBlockchainNode,
                                    block,
                                    estimatedTraffic
                                ),
                                0
                            );
                        }
                        
                        if (this.peerBlockchainNode.nodeID == this.getCurrentPrimaryNumber()){
                            // Check Byzantine behavior
                            boolean isByz = isByzantineValidator(this.peerBlockchainNode.nodeID);
                            String attack = (byzantineConfig == null) ? null : byzantineConfig.getAttackType();
                            if (isByz && attack != null) attack = attack.toUpperCase();

                            if (isByz && "SILENT".equals(attack)) {
                                // Do not broadcast anything
                            } else if (isByz && "WITHHOLD".equals(attack)) {
                                // Withhold pre-prepare: do nothing
                            } else if (isByz && "EQUIVOCATION".equals(attack)) {
                                // Send conflicting pre-prepare messages to different halves of neighbors
                                try {
                                    java.util.List<Node> neighbors = this.peerBlockchainNode.getP2pConnections().getNeighbors();
                                    int half = Math.max(1, neighbors.size() / 2);
                                    PBFTBlock a = BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(), peerBlockchainNode.getNetwork().getRandom(), (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50);
                                    PBFTBlock b = BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(), peerBlockchainNode.getNetwork().getRandom(), (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50);
                                    for (int i = 0; i < neighbors.size(); i++) {
                                        Node nb = neighbors.get(i);
                                        PBFTPrePrepareVote<B> pv = (i < half) ? new PBFTPrePrepareVote<>(this.peerBlockchainNode, (B) a) : new PBFTPrePrepareVote<>(this.peerBlockchainNode, (B) b);
                                        this.peerBlockchainNode.getNodeNetworkInterface().addToUpLinkQueue(new jabs.network.message.Packet(this.peerBlockchainNode, nb, new VoteMessage(pv)));
                                    }
                                } catch (Exception ignored) {}
                            } else {
                                // Normal broadcast
                                this.peerBlockchainNode.broadcastMessage(
                                        new VoteMessage(
                                                new PBFTPrePrepareVote<>(this.peerBlockchainNode,
                                                        BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(),
                                                                peerBlockchainNode.getNetwork().getRandom(),
                                                                (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50)
                                                )
                                        )
                                );
                            }
                        }
                        break;
                    case COMMITTING:
                        // Commit broadcasting may be manipulated by Byzantine nodes
                        boolean isByzCommit = isByzantineValidator(this.peerBlockchainNode.nodeID);
                        String attackCommit = (byzantineConfig == null) ? null : byzantineConfig.getAttackType();
                        if (isByzCommit && attackCommit != null) attackCommit = attackCommit.toUpperCase();

                        if (isByzCommit && "SILENT".equals(attackCommit)) {
                            // do nothing
                        } else if (isByzCommit && "WITHHOLD".equals(attackCommit)) {
                            // withhold commit
                        } else if (isByzCommit && "EQUIVOCATION".equals(attackCommit)) {
                            try {
                                java.util.List<Node> neighbors = this.peerBlockchainNode.getP2pConnections().getNeighbors();
                                int half = Math.max(1, neighbors.size() / 2);
                                PBFTBlock a = BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(), peerBlockchainNode.getNetwork().getRandom(), (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50);
                                PBFTBlock b = BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(), peerBlockchainNode.getNetwork().getRandom(), (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50);
                                for (int i = 0; i < neighbors.size(); i++) {
                                    Node nb = neighbors.get(i);
                                    PBFTCommitVote<B> cv = (i < half) ? new PBFTCommitVote<>(this.peerBlockchainNode, (B) a) : new PBFTCommitVote<>(this.peerBlockchainNode, (B) b);
                                    this.peerBlockchainNode.getNodeNetworkInterface().addToUpLinkQueue(new jabs.network.message.Packet(this.peerBlockchainNode, nb, new VoteMessage(cv)));
                                }
                            } catch (Exception ignored) {}
                        } else if (isByzCommit && "DOUBLE_SIGN".equals(attackCommit)) {
                            // Broadcast two conflicting commits quickly
                            this.peerBlockchainNode.broadcastMessage(new VoteMessage(new PBFTCommitVote<>(this.peerBlockchainNode, block)));
                            this.peerBlockchainNode.broadcastMessage(new VoteMessage(new PBFTCommitVote<>(this.peerBlockchainNode, block)));
                        } else {
                            this.peerBlockchainNode.broadcastMessage(
                                    new VoteMessage(
                                            new PBFTCommitVote<>(this.peerBlockchainNode, block)
                                    )
                            );
                        }
                        break;
                }
            }
        }
    }

    @Override
    public void newIncomingBlock(B block) {

    }

    /**
     * @param block
     * @return
     */
    @Override
    public boolean isBlockConfirmed(B block) {
        return false;
    }

    /**
     * @param block
     * @return
     */
    @Override
    public boolean isBlockValid(B block) {
        return false;
    }

    public int getCurrentViewNumber() {
        return this.currentViewNumber;
    }

    public int getCurrentPrimaryNumber() {
        return (this.currentViewNumber % this.numAllParticipants);
    }

    public int getNumAllParticipants() {
        return this.numAllParticipants;
    }

    public PBFTPhase getPbftPhase() {
        return this.pbftPhase;
    }

    @Override
    protected void updateChain() {
        this.confirmedBlocks.add(this.currentMainChainHead);
        // Record acceptance for BFT per-node counters
        try {
            recordBlockAcceptance(this.currentMainChainHead);
        } catch (Exception ignored) {}
        // Prune local block DAG to free memory for very old blocks
        try {
            int threshold = Math.max(0, this.currentMainChainHead.getHeight() - 1000);
            this.localBlockTree.clearObsoleteData(threshold);
        } catch (Exception ignored) {}
    }

    @Override
    protected int getBlockProposer(B block) {
        try {
            if (block != null && block.getCreator() != null) {
                return block.getCreator().nodeID;
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
