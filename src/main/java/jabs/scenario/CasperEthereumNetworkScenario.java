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
     * Variant that injects Byzantine validators by PERCENTAGE (legacy path: f = EXACT ceiling of the received double,
     * {@link ByzantineConfig#countFromPercent}, no tolerance). A caller that HAS the integer f must use
     * {@link #setByzantineConfig(ByzantineConfig)} with {@link ByzantineConfig#forUniverse}, never pct = 100.0 * f / n.
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
        // BYZ-UNICA (ADR-0015): contagem inteira e conjunto do gerador unico. Universo = todos os nos (Q32, opcao A: no
        // Casper todos votam; ids 0..miners+stakeholders-1). Antes: ceil(stakeholders*pct/100.0) em double, sorteio por
        // java.util.Random(semente) so entre os stakeholders (ids a partir de numMiners).
        if (this.injectedByzantinePercentage > 0.0 && this.byzantineConfig == null) {
            int totalNodes = this.network.getAllNodes().size();
            int byzCount = ByzantineConfig.countFromPercent(totalNodes, this.injectedByzantinePercentage);
            this.byzantineConfig = ByzantineConfig.forUniverse(totalNodes, ByzantineConfig.range(0, totalNodes), byzCount,
                    this.injectedAttackType.name(), this.injectedSeed);
        }
        if (this.byzantineConfig != null) {
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

    /**
     * Injeta uma {@link ByzantineConfig} ja montada (gerador unico, f inteiro; BYZ-UNICA). Chamar antes de {@code run()}.
     * {@code getTotalValidators()} deve ser o total de nos da rede (mineradores + stakeholders).
     */
    public void setByzantineConfig(ByzantineConfig byzantineConfig) {
        if (byzantineConfig == null) {
            throw new IllegalArgumentException("ByzantineConfig obrigatorio");
        }
        int total = this.numOfMiners + this.numOfStakeholders;
        if (byzantineConfig.getTotalValidators() != total) {
            throw new IllegalArgumentException("ByzantineConfig.totalValidators=" + byzantineConfig.getTotalValidators()
                    + " difere do total de nos " + total + " (" + this.numOfMiners + " mineradores + "
                    + this.numOfStakeholders + " stakeholders)");
        }
        this.byzantineConfig = byzantineConfig;
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
