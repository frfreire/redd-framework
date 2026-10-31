package jabs.scenario;

import jabs.consensus.config.NakamotoConsensusConfig;
import jabs.ledgerdata.bitcoin.BitcoinBlockWithoutTx;
import jabs.log.BlockFinalizationLogger;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.config.ByzantineConfig;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.Node;
import jabs.network.networks.bitcoin.BitcoinGlobalProofOfWorkNetworkWithoutTx;
import jabs.network.networks.bitcoin.BitcoinGlobalProofOfWorkNetwork;
import jabs.network.stats.eightysixcountries.bitcoin.BitcoinProofOfWorkGlobalNetworkStats86Countries;

import java.io.IOException;
import java.nio.file.Paths;

import static jabs.network.stats.eightysixcountries.bitcoin.BitcoinProofOfWorkGlobalNetworkStats86Countries.BITCOIN_DIFFICULTY_2022;

public class BitcoinGlobalNetworkScenario extends AbstractScenario {
    public final double stopTime;
    public final double averageBlockInterval;
    public final int confirmationDepth;
    public final int numMiners;
    public final int numNodes;

    // whether this scenario should generate transactions (enables Tt metric)
    private final boolean enableTransactions;
    private final double txIntervalSeconds; // average interval between tx submissions

    private SimulationMetrics metrics;
    private ByzantineConfig byzantineConfig;
    private double injectedByzantinePercentage = 0.0;
    private ByzantineConfig.AttackType injectedAttackType;
    private long injectedSeed;

    /**
     * creates a Bitcoin network scenario with parameters close to real-world but excluding transaction simulation for
     * better simulation speed
     *
     * @param name                 determines the name of simulation scenario
     * @param seed                 this value gives the simulation seed value for randomness engine
     * @param stopTime             this determines how many seconds of simulation world time should it last.
     * @param averageBlockInterval This determines the interval between two block generations in seconds.
     * @param numMiners            Number of miner nodes to populate
     * @param numNodes             Number of non-miner nodes to populate
     * @param confirmationDepth    The depth at which a block is considered confirmed (eg. 6)
     */
    public BitcoinGlobalNetworkScenario(String name, long seed, long stopTime,
                                        double averageBlockInterval, int numMiners, int numNodes, int confirmationDepth) {
        this(name, seed, stopTime, averageBlockInterval, numMiners, numNodes, confirmationDepth,
                false, 1.0);
    }

    /**
     * Constructor that allows enabling transaction generation (for Tt metric) and custom interval.
     */
    public BitcoinGlobalNetworkScenario(String name, long seed, long stopTime,
                                        double averageBlockInterval, int numMiners, int numNodes, int confirmationDepth,
                                        boolean enableTransactions, double txIntervalSeconds) {
        super(name, seed);
        this.stopTime = stopTime;
        this.averageBlockInterval = averageBlockInterval;
        this.numMiners = numMiners;
        this.numNodes = numNodes;
        this.confirmationDepth = confirmationDepth;
        this.enableTransactions = enableTransactions;
        this.txIntervalSeconds = txIntervalSeconds;
    }


    /**
     * Variant with both transaction generation and Byzantine injection
     */
    public BitcoinGlobalNetworkScenario(String name, long seed, long stopTime,
                                        double averageBlockInterval, int numMiners, int numNodes, 
                                        int confirmationDepth,
                                        boolean enableTransactions, double txIntervalSeconds,
                                        double byzantinePercentage, ByzantineConfig.AttackType attackType) {
        this(name, seed, stopTime, averageBlockInterval, numMiners, numNodes, confirmationDepth,
                enableTransactions, txIntervalSeconds);
        this.injectedByzantinePercentage = byzantinePercentage;
        this.injectedAttackType = attackType;
        this.injectedSeed = seed;
    }

    /**
     * Variant that injects Byzantine miners (for PoW attack simulation)
     * Byzantine percentage represents the proportion of total hashpower controlled by attackers
     */
    public BitcoinGlobalNetworkScenario(String name, long seed, long stopTime,
                                        double averageBlockInterval, int numMiners, int numNodes, 
                                        int confirmationDepth,
                                        double byzantinePercentage, ByzantineConfig.AttackType attackType) {
        this(name, seed, stopTime, averageBlockInterval, numMiners, numNodes, confirmationDepth);
        this.injectedByzantinePercentage = byzantinePercentage;
        this.injectedAttackType = attackType;
        this.injectedSeed = seed;
    }

    /**
     * Creates the network and populates it with miners and nodes almost equal to the real world.
     */
    @Override
    protected void createNetwork() {
        if (enableTransactions) {
            // use full network variant with transaction-supporting miners
            BitcoinGlobalProofOfWorkNetwork<?> bitcoinNetwork = new BitcoinGlobalProofOfWorkNetwork<>
                    (randomnessEngine, new BitcoinProofOfWorkGlobalNetworkStats86Countries(randomnessEngine));
            this.network = bitcoinNetwork;
            bitcoinNetwork.populateNetwork(simulator, this.numMiners, this.numNodes,
                    new NakamotoConsensusConfig(BitcoinBlockWithoutTx.generateGenesisBlock(BITCOIN_DIFFICULTY_2022),
                            this.averageBlockInterval, this.confirmationDepth));
        } else {
            BitcoinGlobalProofOfWorkNetworkWithoutTx<?> bitcoinNetwork = new BitcoinGlobalProofOfWorkNetworkWithoutTx<>
                    (randomnessEngine, new BitcoinProofOfWorkGlobalNetworkStats86Countries(randomnessEngine));
            this.network = bitcoinNetwork;
            bitcoinNetwork.populateNetwork(simulator, this.numMiners, this.numNodes,
                    new NakamotoConsensusConfig(BitcoinBlockWithoutTx.generateGenesisBlock(BITCOIN_DIFFICULTY_2022),
                            this.averageBlockInterval, this.confirmationDepth));
        }

        // Propagate ByzantineConfig if configured
        // For PoW: byzantinePercentage represents % of hashpower (distributed among miners)
        if (this.injectedByzantinePercentage > 0.0) {
            // Bitcoin miners are the first numMiners nodes created
            int totalMiners = this.numMiners;
            int byzCount = (int) Math.ceil(totalMiners * (this.injectedByzantinePercentage / 100.0));
            java.util.Random rnd = new java.util.Random(this.injectedSeed);
            java.util.Set<Integer> byzGlobalIds = new java.util.HashSet<>();
            // Select random miners to be Byzantine (node IDs 0..numMiners-1)
            while (byzGlobalIds.size() < byzCount) {
                int idx = rnd.nextInt(totalMiners);
                byzGlobalIds.add(idx);
            }
            // Create config with explicit global node IDs
            this.byzantineConfig = new ByzantineConfig(
                this.network.getAllNodes().size(), 
                byzGlobalIds, 
                this.injectedAttackType.name(), 
                this.injectedSeed
            );

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
            this.metrics.setTotalValidators(totalMiners);
        } else if (this.byzantineConfig != null) {
            // Legacy path: byzantineConfig set externally via setByzantineConfig()
            for (Object o : this.network.getAllNodes()) {
                if (o instanceof Node) {
                    Node n = (Node) o;
                    if (n instanceof PeerBlockchainNode) {
                        PeerBlockchainNode<?, ?> pbn = (PeerBlockchainNode<?, ?>) n;
                        pbn.getConsensusAlgorithm().setByzantineConfig(this.byzantineConfig);
                    }
                }
            }
        }
    }

    /**
     * Starts mining in the network by creating mining processes for each node.
     */
    @Override
    protected void insertInitialEvents() {
        // start mining regardless of network type
        if (network instanceof BitcoinGlobalProofOfWorkNetwork) {
            ((BitcoinGlobalProofOfWorkNetwork<?>) network).startAllMiningProcesses();
        } else if (network instanceof BitcoinGlobalProofOfWorkNetworkWithoutTx) {
            ((BitcoinGlobalProofOfWorkNetworkWithoutTx<?>) network).startAllMiningProcesses();
        }

        // optionally schedule transaction generation so that Tt metric can be computed
        if (enableTransactions) {
            double interval = txIntervalSeconds;
            jabs.simulator.event.TxGenerationProcessRandomNetworkNode txProcess =
                    new jabs.simulator.event.TxGenerationProcessRandomNetworkNode(
                            simulator, network, randomnessEngine, interval);
            simulator.putEvent(txProcess, txProcess.timeToNextGeneration());
        }
    }

    /**
     * Stops simulation after `stopTime`
     * @return true if stopTime is passed
     */
    @Override
    protected boolean simulationStopCondition() {
        return simulator.getSimulationTime() > stopTime;
    }

    public void addMetricsLogger(String outputPath, SimulationMetrics metrics) throws IOException {
        EnhancedBlockFinalizationLogger logger = new EnhancedBlockFinalizationLogger(Paths.get(outputPath), metrics);
        this.metrics = metrics;
        this.metrics.setTotalValidators(this.numMiners + this.numNodes);
        this.AddNewLogger(logger);
    }

    public SimulationMetrics getMetrics() {
        return metrics;
    }

    public void setByzantineConfig(ByzantineConfig byzantineConfig) {
        this.byzantineConfig = byzantineConfig;
        if (this.metrics == null) this.metrics = new SimulationMetrics();
        this.metrics.setByzantineValidators(byzantineConfig.getByzantineCount());
        // total validators might be numMiners + numNodes (approx)
        this.metrics.setTotalValidators(this.numMiners + this.numNodes);
    }
}
