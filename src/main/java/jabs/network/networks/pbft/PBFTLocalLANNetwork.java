package jabs.network.networks.pbft;

import jabs.consensus.algorithm.PBFT;
import jabs.consensus.config.ConsensusAlgorithmConfig;
import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTBlock;
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

    public PBFTNode createNewPBFTNode(Simulator simulator, int nodeID, int numAllParticipants, long maxFutureHorizon) {
        return new PBFTNode(simulator, this, nodeID,
                this.sampleDownloadBandwidth(SingleNodeType.LAN_NODE),
                this.sampleUploadBandwidth(SingleNodeType.LAN_NODE),
                numAllParticipants, maxFutureHorizon);
    }


    @Override
    public void populateNetwork(Simulator simulator, ConsensusAlgorithmConfig pbftConsensusConfig) {
        populateNetwork(simulator, 40, pbftConsensusConfig);
    }

    @Override
    public void populateNetwork(Simulator simulator, int numNodes, ConsensusAlgorithmConfig pbftConsensusConfig) {
        for (int i = 0; i < numNodes; i++) {
            this.addNode(createNewPBFTNode(simulator, i, numNodes, ((PBFTConsensusConfig) pbftConsensusConfig).getMaxFutureHorizon()), SingleNodeType.LAN_NODE);
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
                
                // T6 / ADR-0007 (opcao 2): UM unico bloco inicial, proposto pelo primario da altura 1, pelo mesmo
                // caminho de proposta do protocolo (honesto ou bizantino). As proximas alturas sao propostas pelo
                // primario de cada altura depois de decidir a anterior (PBFT.checkVotes). SEM proposta periodica de
                // filho do genesis (cada uma abria uma cadeia nova e concorrente: ~5 a 10 blocos decididos por altura).
                if (!getAllNodes().isEmpty()) {
                    PBFTNode any = (PBFTNode) getAllNodes().get(0);
                    @SuppressWarnings("unchecked")
                    PBFT<PBFTBlock, ?> anyPbft = (PBFT<PBFTBlock, ?>) any.getConsensusAlgorithm();
                    PBFTNode primaryNode = (PBFTNode) getAllNodes().get(anyPbft.primaryForHeight(1L));
                    @SuppressWarnings("unchecked")
                    PBFT<PBFTBlock, ?> primaryPbft = (PBFT<PBFTBlock, ?>) primaryNode.getConsensusAlgorithm();
                    primaryPbft.proposeChildOf(PBFT_GENESIS_BLOCK);
                }
            }
        }, delay);
    }

}
