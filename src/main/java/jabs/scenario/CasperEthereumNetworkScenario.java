package jabs.scenario;

// import jabs.consensus.config.GhostProtocolConfig;
import jabs.consensus.config.CasperFFGConfig;
// import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.ledgerdata.ethereum.EthereumBlock;
// import jabs.network.networks.ethereum.EthereumGlobalProofOfWorkNetwork;
import jabs.network.networks.ethereum.CasperFFGGlobalBlockchainNetwork;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
// import jabs.network.stats.sixglobalregions.ethereum.EthereumNodeGlobalNetworkStats6Regions;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.config.ByzantineConfig;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.Node;
import java.io.IOException;
import java.nio.file.Paths;

public class CasperEthereumNetworkScenario extends AbstractScenario {
    private final double simulationStopTime;
    private final double averageBlockInterval;
    private final int checkpointSpace;
    private final int numOfMiners;
    private final int numOfStakeholders;
    private SimulationMetrics metrics;
    private ByzantineConfig byzantineConfig;
    private double injectedByzantinePercentage = 0.0;
    private ByzantineConfig.AttackType injectedAttackType = null;
    private long injectedSeed = 0L;

    /**
     * @param name
     * @param seed
     * @param simulationStopTime
     * @param averageBlockInterval
     */
    public CasperEthereumNetworkScenario(String name, long seed,
                                         double simulationStopTime, double averageBlockInterval, 
                                         int checkpointSpace, int numOfMiners, int numOfStakeholders) {
        super(name, seed);
        this.simulationStopTime = simulationStopTime;
        this.averageBlockInterval = averageBlockInterval;
        this.checkpointSpace = checkpointSpace;
        this.numOfMiners = numOfMiners;
        this.numOfStakeholders = numOfStakeholders;
    }

    /**
     * Variant that injects Byzantine validators
     */
    public CasperEthereumNetworkScenario(String name, long seed,
                                         double simulationStopTime, double averageBlockInterval,
                                         int checkpointSpace, int numOfMiners, int numOfStakeholders,
                                         double byzantinePercentage, ByzantineConfig.AttackType attackType) {
        this(name, seed, simulationStopTime, averageBlockInterval, checkpointSpace, numOfMiners, numOfStakeholders);
        // store parameters to build a proper ByzantineConfig after network population
        this.injectedByzantinePercentage = byzantinePercentage;
        this.injectedAttackType = attackType;
        this.injectedSeed = seed;
    }

    @Override
    public void createNetwork() {
        CasperFFGGlobalBlockchainNetwork<?> ethereumNetwork = new CasperFFGGlobalBlockchainNetwork<>(randomnessEngine, this.checkpointSpace,
                new EthereumProofOfWorkGlobalNetworkStats6Regions(randomnessEngine));
        this.network = ethereumNetwork;
        ethereumNetwork.populateNetwork(simulator, this.numOfMiners, this.numOfStakeholders,
                new CasperFFGConfig(EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022),
                        this.averageBlockInterval, this.checkpointSpace, this.numOfStakeholders));
        // Propagate ByzantineConfig if configured (map stakeholder-relative indices to global node IDs)
        if (this.injectedByzantinePercentage > 0.0) {
            // Stakeholder nodes are created after miners; their nodeIDs start at numOfMiners
            int numMiners = this.numOfMiners;
            int stakeholders = this.numOfStakeholders;
            int byzCount = (int) Math.ceil(stakeholders * (this.injectedByzantinePercentage / 100.0));
            java.util.Random rnd = new java.util.Random(this.injectedSeed);
            java.util.Set<Integer> byzGlobalIds = new java.util.HashSet<>();
            while (byzGlobalIds.size() < byzCount) {
                int idx = rnd.nextInt(stakeholders);
                byzGlobalIds.add(numMiners + idx);
            }
            // Create a config with explicit global node IDs
            this.byzantineConfig = new ByzantineConfig(this.network.getAllNodes().size(), byzGlobalIds, this.injectedAttackType.name(), this.injectedSeed);

            for (Object o : this.network.getAllNodes()) {
                if (o instanceof Node) {
                    Node n = (Node) o;
                    if (n instanceof PeerBlockchainNode) {
                        // sem try/catch: ataque nao implementado pelo protocolo deve falhar alto (criterio 4)
                        PeerBlockchainNode<?, ?> pbn = (PeerBlockchainNode<?, ?>) n;
                        pbn.getConsensusAlgorithm().setByzantineConfig(this.byzantineConfig);
                    }
                }
            }
            // Update metrics if already created/attached
            if (this.metrics == null) this.metrics = new SimulationMetrics();
            this.metrics.setByzantineValidators(this.byzantineConfig.getByzantineCount());
            this.metrics.setTotalValidators(this.numOfStakeholders);
        }
    }

    @Override
    protected void insertInitialEvents() {
        ((CasperFFGGlobalBlockchainNetwork<?>) network).startAllMiningProcesses();
        
        // Schedule recurring transaction generation using the built-in TxGenerationProcess
        double txInterval = 0.5; // Generate transactions every 0.5 seconds
        jabs.simulator.event.TxGenerationProcessRandomNetworkNode txProcess = 
            new jabs.simulator.event.TxGenerationProcessRandomNetworkNode(
                simulator, network, randomnessEngine, txInterval);
        simulator.putEvent(txProcess, txProcess.timeToNextGeneration());
    }
    
    @Override
    public boolean simulationStopCondition() {
        return (simulator.getSimulationTime() > simulationStopTime);
    }

    public void addMetricsLogger(String outputPath, SimulationMetrics metrics) throws IOException {
        EnhancedBlockFinalizationLogger logger = new EnhancedBlockFinalizationLogger(Paths.get(outputPath), metrics);
        this.metrics = metrics;
        this.metrics.setTotalValidators(this.numOfStakeholders);
        this.AddNewLogger(logger);
    }

    public SimulationMetrics getMetrics() {
        return this.metrics;
    }
}
