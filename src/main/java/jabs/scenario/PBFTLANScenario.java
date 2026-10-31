package jabs.scenario;

import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.network.message.VoteMessage;
import jabs.ledgerdata.BlockFactory;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;

public class PBFTLANScenario extends AbstractScenario {
    protected int numNodes;
    protected double simulationStopTime;

    public PBFTLANScenario(String name, long seed, int numNodes, double simulationStopTime) {
        super(name, seed);
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
    }

    @Override
    public void createNetwork() {
        network = new PBFTLocalLANNetwork(randomnessEngine);
        network.populateNetwork(this.simulator, this.numNodes, new PBFTConsensusConfig());
    }

    @Override
    protected void insertInitialEvents() {
        // Start continuous consensus rounds via the network with stop time
        ((PBFTLocalLANNetwork) network).startConsensusRound(simulator, this.simulationStopTime);
    }

    @Override
    public boolean simulationStopCondition() {
        return (simulator.getSimulationTime() > this.simulationStopTime);
    }
}
