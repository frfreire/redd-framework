package jabs.scenario;

import jabs.consensus.config.ConsensusAlgorithmConfig;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.networks.bsc.ParliaGlobalBlockchainNetwork;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.ethereum.EthereumMinerNode;
import jabs.network.node.nodes.MinerNode;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.consensus.config.ChainBasedConsensusConfig;
import jabs.consensus.algorithm.Parlia;
import jabs.ledgerdata.ethereum.EthereumTx;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.config.ByzantineConfig;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.Node;
import java.io.IOException;
import java.nio.file.Paths;

import java.util.List;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;

public class ParliaBSCNetworkScenario extends AbstractScenario {
    private final double simulationStopTime;
    private final double averageBlockInterval;
    private final int turnLength;
    private final int epochLength;
    private final int numOfMiners;
    private final int numOfStakeholders;
    private SimulationMetrics metrics;
    private ByzantineConfig byzantineConfig;

    /**
     * @param name                 scenario name
     * @param seed                 randomness seed
     * @param simulationStopTime   simulation stop time (seconds)
     * @param averageBlockInterval average block interval (seconds)
     * @param turnLength           number of blocks per validator turn
     * @param epochLength          number of blocks per epoch
     */
    public ParliaBSCNetworkScenario(String name, long seed,
            double simulationStopTime, double averageBlockInterval, int turnLength, int epochLength, int numOfMiners,
            int numOfStakeholders) {
        super(name, seed);
        this.simulationStopTime = simulationStopTime;
        this.averageBlockInterval = averageBlockInterval;
        this.turnLength = turnLength;
        this.epochLength = epochLength;
        this.numOfMiners = numOfMiners;
        this.numOfStakeholders = numOfStakeholders;
        this.metrics = new SimulationMetrics();
        this.metrics.setTotalValidators(this.numOfStakeholders);
    }

    @Override
    public void createNetwork() {
        ParliaGlobalBlockchainNetwork<?> parliaNetwork = new ParliaGlobalBlockchainNetwork<>(
                randomnessEngine,
                new EthereumProofOfWorkGlobalNetworkStats6Regions(randomnessEngine),
                turnLength,
                epochLength);
        this.network = parliaNetwork;
        parliaNetwork.populateNetwork(simulator, this.numOfMiners, this.numOfStakeholders,
                new ChainBasedConsensusConfig(
                        EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022),
                        this.averageBlockInterval));
        // Propagate Byzantine config if provided
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
        }
        // After all miners are created:
        List<EthereumMinerNode> allMiners = new java.util.ArrayList<>();
        for (MinerNode miner : parliaNetwork.getAllMiners()) {
            allMiners.add((EthereumMinerNode) miner);
        }
        for (EthereumMinerNode miner : allMiners) {
            Parlia<EthereumBlock, EthereumTx> consensus = (Parlia<EthereumBlock, EthereumTx>) miner.getConsensusAlgorithm();
            for (EthereumMinerNode validator : allMiners) {
                consensus.addValidator(validator);
            }
        }
    }

    @Override
    protected void insertInitialEvents() {
        // In Parlia, blocks are proposed by validators in turn.
        // Start the first proposal by the first validator node.
        List<Node> nodes = network.getAllNodes();
        if (!nodes.isEmpty()) {
            Node firstValidator = nodes.get(0);
            // Assuming ParliaGlobalBlockchainNetwork has a method to trigger the first
            // proposal,
            // or you can directly schedule a block proposal event for the first validator.
            ((ParliaGlobalBlockchainNetwork<?>) network).startBlockProposal(simulator, firstValidator);
        }
        
        // Schedule recurring transaction generation (BSC has high throughput)
        double txInterval = 0.1; // Generate transactions every 0.1 seconds
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
        this.AddNewLogger(logger);
    }

    public SimulationMetrics getMetrics() {
        return this.metrics;
    }

    public void setByzantineConfig(ByzantineConfig byzantineConfig) {
        this.byzantineConfig = byzantineConfig;
        if (this.metrics == null) this.metrics = new SimulationMetrics();
        this.metrics.setByzantineValidators(byzantineConfig.getByzantineCount());
        this.metrics.setTotalValidators(this.numOfStakeholders);
    }
}