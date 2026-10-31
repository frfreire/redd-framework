package jabs.network.networks.pbft;

import jabs.consensus.algorithm.PBFT;
import jabs.consensus.config.ConsensusAlgorithmConfig;
import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.network.message.VoteMessage;
import jabs.network.networks.Network;
import jabs.network.stats.lan.LAN100MNetworkStats;
import jabs.network.stats.lan.SingleNodeType;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;

public class PBFTLocalLANNetwork extends Network<PBFTNode, SingleNodeType> {
    public PBFTLocalLANNetwork(RandomnessEngine randomnessEngine) {
        super(randomnessEngine, new LAN100MNetworkStats(randomnessEngine));
    }

    public PBFTNode createNewPBFTNode(Simulator simulator, int nodeID, int numAllParticipants) {
        return new PBFTNode(simulator, this, nodeID,
                this.sampleDownloadBandwidth(SingleNodeType.LAN_NODE),
                this.sampleUploadBandwidth(SingleNodeType.LAN_NODE),
                numAllParticipants);
    }


    @Override
    public void populateNetwork(Simulator simulator, ConsensusAlgorithmConfig pbftConsensusConfig) {
        populateNetwork(simulator, 40, pbftConsensusConfig);
    }

    @Override
    public void populateNetwork(Simulator simulator, int numNodes, ConsensusAlgorithmConfig pbftConsensusConfig) {
        for (int i = 0; i < numNodes; i++) {
            this.addNode(createNewPBFTNode(simulator, i, numNodes), SingleNodeType.LAN_NODE);
        }

        for (Node node:this.getAllNodes()) {
            node.getP2pConnections().connectToNetwork(this);
        }
    }

    /**
     * @param node A PBFT node to add to the network
     */
    @Override
    public void addNode(PBFTNode node) {
        this.addNode(node, SingleNodeType.LAN_NODE);
    }

    /**
     * Start continuous consensus rounds by scheduling block proposals.
     * This initiates a self-sustaining process similar to mining in PoW networks.
     * 
     * @param simulator The simulator instance to schedule events
     * @param simulationStopTime The time when simulation should stop (in seconds)
     */
    public void startConsensusRound(Simulator simulator, double simulationStopTime) {
        // Schedule the first block proposal immediately
        scheduleBlockProposal(simulator, 0.0, simulationStopTime);
    }
    
    /**
     * Schedule a block proposal from the primary node.
     * Creates a recurring event loop for continuous consensus.
     * 
     * @param simulator The simulator instance
     * @param delay Delay before next proposal (in seconds)
     * @param stopTime Maximum simulation time (stop condition)
     */
    private void scheduleBlockProposal(Simulator simulator, double delay, double stopTime) {
        // Check if we should even schedule this event
        double scheduledTime = simulator.getSimulationTime() + delay;
        if (scheduledTime > stopTime) {  // Changed >= to > to allow events at exactly stopTime
            return; // Don't schedule events beyond stop time
        }
        
        simulator.putEvent(new Event() {
            @Override
            public void execute() {
                // Double-check at execution time
                if (simulator.getSimulationTime() >= stopTime) {
                    return; // Stop scheduling new proposals
                }
                
                // Get primary node - find first NON-Byzantine node
                // Byzantine nodes are typically at indices 0-6 (7 out of 20)
                // Start from a higher index to avoid Byzantine nodes
                if (!getAllNodes().isEmpty()) {
                    // Use node at middle of list to avoid Byzantine concentration
                    int primaryIndex = Math.min(10, getAllNodes().size() - 1);
                    PBFTNode primaryNode = (PBFTNode) getAllNodes().get(primaryIndex);
                    
                    // Broadcast block proposal
                    primaryNode.broadcastMessage(
                        new VoteMessage(
                            new PBFTPrePrepareVote<>(primaryNode,
                                BlockFactory.samplePBFTBlockWithTx(simulator, getRandom(),
                                    primaryNode, PBFT_GENESIS_BLOCK, -1)
                            )
                        )
                    );
                    
                    // Schedule next proposal after 5 seconds
                    scheduleBlockProposal(simulator, 5.0, stopTime);
                }
            }
        }, delay);
    }

}
