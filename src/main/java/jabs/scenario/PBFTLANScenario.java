package jabs.scenario;

import jabs.consensus.config.PBFTConsensusConfig;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.network.message.VoteMessage;
import jabs.ledgerdata.BlockFactory;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;

import java.io.IOException;
import java.nio.file.Paths;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;

public class PBFTLANScenario extends AbstractScenario {
    protected int numNodes;
    protected double simulationStopTime;
    private final SimulationMetrics metrics = new SimulationMetrics();

    public PBFTLANScenario(String name, long seed, int numNodes, double simulationStopTime) {
        super(name, seed);
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
        this.metrics.setTotalValidators(numNodes);
        this.metrics.setConsensusType(SimulationMetrics.ConsensusType.VOTING);
    }

    /** Logger de metricas por bloco gravando em {@code outputPath} (mesmo do Tendermint/HotStuff). */
    public void addMetricsLogger(String outputPath) throws IOException {
        this.AddNewLogger(new EnhancedBlockFinalizationLogger(Paths.get(outputPath), this.metrics));
    }

    public SimulationMetrics getMetrics() {
        return metrics;
    }

    public jabs.network.networks.pbft.PBFTLocalLANNetwork getNetwork() {
        return (jabs.network.networks.pbft.PBFTLocalLANNetwork) network;
    }

    private Long maxFutureHorizon;
    private PBFTConsensusConfig.LeaderPolicy leaderPolicy;

    /** Politica de lideranca do PBFT (PI-14): obrigatoria, sem padrao; chame antes de {@code run()}. */
    public void setLeaderPolicy(PBFTConsensusConfig.LeaderPolicy leaderPolicy) {
        if (leaderPolicy == null) {
            throw new IllegalArgumentException("leaderPolicy e obrigatorio e nao tem padrao (STABLE ou ROTATING)");
        }
        this.leaderPolicy = leaderPolicy;
    }

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
        if (leaderPolicy == null) {
            throw new IllegalStateException("leaderPolicy e obrigatorio e nao tem padrao: chame setLeaderPolicy(...)");
        }
        network.populateNetwork(this.simulator, this.numNodes, new PBFTConsensusConfig(maxFutureHorizon, leaderPolicy));
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
