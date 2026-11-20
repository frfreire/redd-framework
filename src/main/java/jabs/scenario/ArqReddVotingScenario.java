package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;

import java.io.IOException;
import java.nio.file.Paths;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;

/**
 * Scenario for Arq-REDD+ Voting-Based Consensus with Byzantine Validators
 * 
 * This scenario creates a network of voting-based consensus nodes (using pBFT
 * as base)
 * and optionally injects Byzantine validators for fault tolerance testing.
 * 
 * Used for MVP validation of 3 new metrics:
 * 1. Tb - Block Finalization Time
 * 2. Cb - Network Traffic
 * 4. BFT - Byzantine Fault Tolerance (NEW)
 * 5. Pdv - Double-spending Probability (NEW)
 */
public class ArqReddVotingScenario extends AbstractScenario {
    protected int numNodes;
    protected double simulationStopTime;
    protected ByzantineConfig byzantineConfig;
    protected SimulationMetrics metrics;

    /**
     * Create Arq-REDD+ voting scenario WITHOUT Byzantine validators
     * 
     * @param name               Scenario name
     * @param seed               Random seed
     * @param numNodes           Number of consensus nodes
     * @param simulationStopTime Stop condition (seconds)
     */
    public ArqReddVotingScenario(String name, long seed, int numNodes, double simulationStopTime) {
        super(name, seed);
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
        this.byzantineConfig = null;
        this.metrics = new SimulationMetrics();
    }

    /**
     * Create Arq-REDD+ voting scenario WITH Byzantine validators
     * 
     * @param name                Scenario name
     * @param seed                Random seed
     * @param numNodes            Number of consensus nodes
     * @param simulationStopTime  Stop condition (seconds)
     * @param byzantinePercentage Percentage of Byzantine nodes (0-100). LEGACY path: f = EXACT ceiling of the received
     *                            double ({@link ByzantineConfig#countFromPercent}), no tolerance; a caller that HAS the
     *                            integer f must use the {@link ByzantineConfig}-taking constructor
     *                            ({@link ByzantineConfig#withExactCount}), never pct = 100.0 * f / n.
     * @param attackType          Type of Byzantine attack
     */
    public ArqReddVotingScenario(String name, long seed, int numNodes, double simulationStopTime,
            double byzantinePercentage, ByzantineConfig.AttackType attackType) {
        super(name, seed);
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;

        // Create Byzantine configuration
        this.byzantineConfig = new ByzantineConfig(numNodes, byzantinePercentage, attackType, seed);

        // Initialize metrics with Byzantine info
        this.metrics = new SimulationMetrics();
        this.metrics.setByzantineValidators(byzantineConfig.getByzantineCount());
        this.metrics.setTotalValidators(numNodes);
    }

    /**
     * Create Arq-REDD+ voting scenario WITH an already-built Byzantine configuration (BYZ-UNICA, ADR-0015): the caller
     * draws the set with the single generator ({@code ByzantineConfig.selectNested}) from an integer f, with no
     * round trip through a percentage. {@code byzantineConfig.getTotalValidators()} must equal {@code numNodes}.
     */
    public ArqReddVotingScenario(String name, long seed, int numNodes, double simulationStopTime,
            ByzantineConfig byzantineConfig) {
        super(name, seed);
        if (byzantineConfig == null) {
            throw new IllegalArgumentException("ByzantineConfig obrigatorio (use o construtor sem ataque para o honesto)");
        }
        if (byzantineConfig.getTotalValidators() != numNodes) {
            throw new IllegalArgumentException("ByzantineConfig.totalValidators=" + byzantineConfig.getTotalValidators()
                    + " difere de numNodes=" + numNodes);
        }
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
        this.byzantineConfig = byzantineConfig;
        this.metrics = new SimulationMetrics();
        this.metrics.setByzantineValidators(byzantineConfig.getByzantineCount());
        this.metrics.setTotalValidators(numNodes);
    }

    private Long maxFutureHorizon;

    /**
     * Teto de retencao do PBFT (paridade com o {@code maxFutureViewHorizon} do HotStuff). OBRIGATORIO e sem padrao:
     * chame antes de {@code run()}; sem ele {@code createNetwork()} falha alto. Valor do estudo: 1000 (ADR-0015).
     */
    public void setMaxFutureHorizon(long maxFutureHorizon) {
        if (maxFutureHorizon <= 0L) {
            throw new IllegalArgumentException("maxFutureHorizon deve ser > 0: " + maxFutureHorizon);
        }
        this.maxFutureHorizon = maxFutureHorizon;
    }

    @Override
    public void createNetwork() {
        if (maxFutureHorizon == null) {
            throw new IllegalStateException("maxFutureHorizon e obrigatorio e nao tem padrao: chame setMaxFutureHorizon(long) "
                    + "antes de run() (PARIDADE-teto-retencao-pbft-tendermint; valor do estudo 1000)");
        }
        // Create pBFT-based network (voting consensus substrate)
        network = new PBFTLocalLANNetwork(randomnessEngine);
        network.populateNetwork(this.simulator, this.numNodes, new PBFTConsensusConfig(maxFutureHorizon));

        // If Byzantine config exists, mark nodes as Byzantine
        if (byzantineConfig != null) {
            for (int i = 0; i < network.getAllNodes().size(); i++) {
                Node node = (Node) network.getAllNodes().get(i);
                if (byzantineConfig.isByzantine(i)) {
                    // Attach Byzantine configuration to node's consensus algorithm when possible
                    if (node instanceof PeerBlockchainNode) {
                        // sem try/catch: ataque nao implementado pelo protocolo deve falhar alto (criterio 4)
                        PeerBlockchainNode<?, ?> pbn = (PeerBlockchainNode<?, ?>) node;
                        pbn.getConsensusAlgorithm().setByzantineConfig(byzantineConfig);
                    }

                    // Apply immediate network-level effects for certain attack types
                    String attack = byzantineConfig.getAttackType();
                    if ("SILENT".equalsIgnoreCase(attack)) {
                        // Silent attackers: take node offline (no packet delivery)
                        try { node.crash(); } catch (Exception ignored) {}
                    }
                }
            }
        }
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

    /**
     * Add metrics logger to scenario
     */
    public void addMetricsLogger(String outputPath) throws IOException {
        EnhancedBlockFinalizationLogger logger = new EnhancedBlockFinalizationLogger(
                Paths.get(outputPath),
                this.metrics);
        this.AddNewLogger(logger);
    }

    /**
     * Add metrics logger to scenario
     */
    public void addMetricsLogger(String outputPath, SimulationMetrics metrics) throws IOException {
        EnhancedBlockFinalizationLogger logger = new EnhancedBlockFinalizationLogger(
                Paths.get(outputPath),
                metrics);
        this.metrics = metrics;
        this.AddNewLogger(logger);
    }

    /**
     * Get collected metrics
     */
    public SimulationMetrics getMetrics() {
        return this.metrics;
    }

    /**
     * Get Byzantine configuration
     */
    public ByzantineConfig getByzantineConfig() {
        return this.byzantineConfig;
    }
}
