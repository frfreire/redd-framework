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

    private Long maxFutureHorizon;

    /** Teto de retencao do PBFT: obrigatorio, sem padrao; chame antes de {@code run()} (valor do estudo 1000). */
    public void setMaxFutureHorizon(long maxFutureHorizon) {
        if (maxFutureHorizon <= 0L) {
            throw new IllegalArgumentException("maxFutureHorizon deve ser > 0: " + maxFutureHorizon);
        }
        this.maxFutureHorizon = maxFutureHorizon;
    }

    @Override
    public void createNetwork() {
        if (maxFutureHorizon == null) {
            throw new IllegalStateException("maxFutureHorizon e obrigatorio e nao tem padrao: chame setMaxFutureHorizon(long)");
        }
        network = new PBFTLocalLANNetwork(randomnessEngine);
        network.populateNetwork(this.simulator, this.numNodes, new PBFTConsensusConfig(maxFutureHorizon));
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
