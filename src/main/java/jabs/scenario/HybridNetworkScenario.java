package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.network.access.AccessControlManager;
import jabs.network.node.NodeType;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;
import jabs.ledgerdata.TransactionFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Hybrid Network Scenario for Arq-REDD+ Architecture
 * 
 * Extends ArqReddVotingScenario to implement hybrid public/private transaction architecture:
 * - 70% public transactions (visible to all nodes)
 * - 30% private transactions (visible only to authorized participants)
 * - Three node types: SIMPLE, VALIDATOR, GENERATOR
 * - Access control via Constellation/Enclave pattern
 * 
 * This scenario validates the hybrid architecture proposal from the Arq-REDD+ design,
 * measuring impact on metrics (Tb, Cb, Bf, BFT, Pdv) compared to fully public scenarios.
 * 
 * Based on architecture diagram showing:
 * - Public Client (RPC) -> Nodes (public transactions)
 * - Full Client (Constellation/Enclave) -> Nodes (private transactions)
 */
public class HybridNetworkScenario extends ArqReddVotingScenario {
    /**
     * Percentage of transactions that should be private (0-100)
     */
    private final double privateTransactionPercentage;
    
    /**
     * Access control manager for private transactions
     */
    private final AccessControlManager accessControlManager;
    
    /**
     * Random generator for determining transaction privacy
     */
    private final Random privacyRandom;
    
    /**
     * Distribution of node types in the network
     * Format: [simpleNodes%, validatorNodes%, generatorNodes%]
     */
    private final double[] nodeTypeDistribution;
    
    /**
     * Create hybrid network scenario with default distribution:
     * - 30% private transactions
     * - Node distribution: 20% SIMPLE, 60% VALIDATOR, 20% GENERATOR
     * 
     * @param name Scenario name
     * @param seed Random seed
     * @param numNodes Number of nodes
     * @param simulationStopTime Stop time in seconds
     */
    public HybridNetworkScenario(String name, long seed, int numNodes, double simulationStopTime) {
        this(name, seed, numNodes, simulationStopTime, 30.0, new double[]{20.0, 60.0, 20.0});
    }
    
    /**
     * Create hybrid network scenario with Byzantine validators
     * 
     * @param name Scenario name
     * @param seed Random seed
     * @param numNodes Number of nodes
     * @param simulationStopTime Stop time in seconds
     * @param byzantinePercentage Percentage of Byzantine nodes
     * @param attackType Type of Byzantine attack
     */
    public HybridNetworkScenario(String name, long seed, int numNodes, double simulationStopTime,
                                 double byzantinePercentage, ByzantineConfig.AttackType attackType) {
        this(name, seed, numNodes, simulationStopTime, 30.0, new double[]{20.0, 60.0, 20.0},
             byzantinePercentage, attackType);
    }
    
    /**
     * Create hybrid network scenario with custom configuration
     * 
     * @param name Scenario name
     * @param seed Random seed
     * @param numNodes Number of nodes
     * @param simulationStopTime Stop time in seconds
     * @param privateTransactionPercentage Percentage of private transactions (0-100)
     * @param nodeTypeDistribution Array [simple%, validator%, generator%] must sum to 100
     */
    public HybridNetworkScenario(String name, long seed, int numNodes, double simulationStopTime,
                                 double privateTransactionPercentage, double[] nodeTypeDistribution) {
        super(name, seed, numNodes, simulationStopTime);
        
        validateDistribution(nodeTypeDistribution);
        
        this.privateTransactionPercentage = Math.max(0.0, Math.min(100.0, privateTransactionPercentage));
        this.nodeTypeDistribution = nodeTypeDistribution;
        this.accessControlManager = new AccessControlManager();
        this.privacyRandom = new Random(seed + 1000); // Different seed for privacy decisions
        
        // Update metrics to track hybrid architecture
        this.metrics.setHybridMode(true);
        this.metrics.setPrivateTransactionPercentage(this.privateTransactionPercentage);
    }
    
    /**
     * Create hybrid network scenario with Byzantine validators and custom configuration
     * 
     * @param name Scenario name
     * @param seed Random seed
     * @param numNodes Number of nodes
     * @param simulationStopTime Stop time in seconds
     * @param privateTransactionPercentage Percentage of private transactions (0-100)
     * @param nodeTypeDistribution Array [simple%, validator%, generator%] must sum to 100
     * @param byzantinePercentage Percentage of Byzantine nodes
     * @param attackType Type of Byzantine attack
     */
    public HybridNetworkScenario(String name, long seed, int numNodes, double simulationStopTime,
                                 double privateTransactionPercentage, double[] nodeTypeDistribution,
                                 double byzantinePercentage, ByzantineConfig.AttackType attackType) {
        super(name, seed, numNodes, simulationStopTime, byzantinePercentage, attackType);
        
        validateDistribution(nodeTypeDistribution);
        
        this.privateTransactionPercentage = Math.max(0.0, Math.min(100.0, privateTransactionPercentage));
        this.nodeTypeDistribution = nodeTypeDistribution;
        this.accessControlManager = new AccessControlManager();
        this.privacyRandom = new Random(seed + 1000);
        
        // Update metrics to track hybrid architecture
        this.metrics.setHybridMode(true);
        this.metrics.setPrivateTransactionPercentage(this.privateTransactionPercentage);
    }
    
    /**
     * Validate that node type distribution sums to 100%
     */
    private void validateDistribution(double[] distribution) {
        if (distribution.length != 3) {
            throw new IllegalArgumentException("Node type distribution must have 3 values [simple%, validator%, generator%]");
        }
        
        double sum = distribution[0] + distribution[1] + distribution[2];
        if (Math.abs(sum - 100.0) > 0.01) {
            throw new IllegalArgumentException("Node type distribution must sum to 100%, got: " + sum);
        }
    }
    
    /**
     * Determine node type based on distribution
     * @param nodeIndex Index of node being created
     * @return NodeType for this node
     */
    protected NodeType determineNodeType(int nodeIndex) {
        double random = privacyRandom.nextDouble() * 100.0;
        
        if (random < nodeTypeDistribution[0]) {
            return NodeType.SIMPLE;
        } else if (random < nodeTypeDistribution[0] + nodeTypeDistribution[1]) {
            return NodeType.VALIDATOR;
        } else {
            return NodeType.GENERATOR;
        }
    }
    
    /**
     * Determine if a transaction should be private
     * @return true if transaction should be private
     */
    protected boolean shouldBePrivate() {
        return privacyRandom.nextDouble() * 100.0 < privateTransactionPercentage;
    }
    
    /**
     * Generate authorized participants for a private transaction
     * @param totalNodes Total number of nodes in network
     * @return Set of authorized node IDs
     */
    protected Set<Integer> generateAuthorizedParticipants(int totalNodes) {
        // Minimum 2 participants, maximum 50% of network
        int minParticipants = 2;
        int maxParticipants = Math.max(minParticipants, totalNodes / 2);
        
        // Random number of participants
        int numParticipants = minParticipants + 
            privacyRandom.nextInt(maxParticipants - minParticipants + 1);
        
        // Create list of all node indices and shuffle
        List<Integer> allNodes = new ArrayList<>(totalNodes);
        for (int i = 0; i < totalNodes; i++) {
            allNodes.add(i);
        }
        Collections.shuffle(allNodes, privacyRandom);
        
        // Return set of first numParticipants
        return new HashSet<>(allNodes.subList(0, numParticipants));
    }
    
    /**
     * Get access control manager
     * @return The access control manager for this scenario
     */
    public AccessControlManager getAccessControlManager() {
        return accessControlManager;
    }
    
    /**
     * Get hybrid network metrics including privacy overhead
     * @return Map of metric names to values
     */
    public java.util.Map<String, Object> getHybridMetrics() {
        java.util.Map<String, Object> hybridMetrics = new java.util.HashMap<>();
        
        // Standard metrics
        hybridMetrics.put("avg_block_finalization_time", metrics.getAverageBlockFinalizationTime());
        hybridMetrics.put("avg_traffic_per_block", metrics.getAverageTrafficPerBlock());
        hybridMetrics.put("byzantine_fault_tolerance", metrics.getByzantineFaultTolerance());
        hybridMetrics.put("double_spend_probability", metrics.getDoubleSpendSuccessProbability());
        
        // Hybrid-specific metrics
        hybridMetrics.put("private_transaction_percentage", privateTransactionPercentage);
        hybridMetrics.put("privacy_overhead", accessControlManager.calculatePrivacyOverhead(numNodes));
        hybridMetrics.put("access_denied_count", accessControlManager.getAccessDeniedCount());
        
        // Add access control metrics
        hybridMetrics.putAll(accessControlManager.getAccessControlMetrics());
        
        return hybridMetrics;
    }
    
    @Override
    public void run() throws java.io.IOException {
        System.out.println("🌐 Starting Hybrid Network Scenario");
        System.out.println("   Private Transactions: " + privateTransactionPercentage + "%");
        System.out.println("   Node Distribution: " + 
            String.format("%.0f%% SIMPLE, %.0f%% VALIDATOR, %.0f%% GENERATOR",
                nodeTypeDistribution[0], nodeTypeDistribution[1], nodeTypeDistribution[2]));
        
        // Start standard consensus rounds
        super.run();
        
        System.out.println("✅ Hybrid Network Scenario Complete");
        System.out.println("   Privacy Overhead: " + 
            String.format("%.2f%%", accessControlManager.calculatePrivacyOverhead(numNodes) * 100));
    }
    
    /**
     * Override to insert hybrid-specific events: generate simulated transactions periodically
     * This registers public/private transactions in metrics and registers private transactions
     * in the AccessControlManager so they appear in hybrid metrics.
     */
    @Override
    protected void insertInitialEvents() {
        super.insertInitialEvents();
        // Schedule transaction generation events starting at t=0.5s
        scheduleTransactionGeneration(this.simulator, 0.5);
    }
    
    private void scheduleTransactionGeneration(Simulator simulator, double delay) {
        double scheduledTime = simulator.getSimulationTime() + delay;
        if (scheduledTime > this.simulationStopTime) return;
        simulator.putEvent(new jabs.simulator.event.Event() {
            @Override
            public void execute() {
                if (simulator.getSimulationTime() >= simulationStopTime) return;

                // Generate a small burst of transactions (5-20)
                int numTx = 5 + privacyRandom.nextInt(16);
                RandomnessEngine rand = getNetwork().getRandom();
                for (int i = 0; i < numTx; i++) {
                    boolean isPrivate = shouldBePrivate();
                    // Use a generic transaction (EthereumTx) to simulate size
                    jabs.ledgerdata.ethereum.EthereumTx tx = TransactionFactory.sampleEthereumTransaction(rand);
                    if (isPrivate) {
                        // pick authorized participants and mark tx private
                        java.util.Set<Integer> authorized = generateAuthorizedParticipants(numNodes);
                        tx.setPrivate(authorized);
                        accessControlManager.registerPrivateTransaction(tx);
                        metrics.recordPrivateTransaction(tx.getSize());
                    } else {
                        metrics.recordPublicTransaction(tx.getSize());
                    }
                }

                // Periodically prune AccessControlManager to avoid unbounded growth
                accessControlManager.clearObsoleteData(10000);

                // Schedule next generation in 1 second
                scheduleTransactionGeneration(simulator, 1.0);
            }
        }, delay);
    }
}
