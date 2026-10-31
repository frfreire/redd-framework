package jabs.metrics;

import java.util.*;

/**
 * Comprehensive metrics collection for consensus algorithm simulation.
 * 
 * Tracks 6 key metrics:
 * 1. Tb  - Average block finalization time (seconds/block)
 * 2. Cb  - Average network traffic for block finalization (MB/block)
 * 3. Tt  - Average transaction confirmation latency (seconds/transaction)
 * 4. BFT - Byzantine Fault Tolerance (attack threshold percentage)
 * 5. Pdv - Double-spending attack success probability (percentage)
 */
public class SimulationMetrics {
    
    /**
     * Consensus protocol types for BFT threshold calculation
     */
    public enum ConsensusType {
        VOTING,    // pBFT, Arq-REDD+, Xolph: floor((n-1)/3) + 1
        POS,       // Casper FFG, Parlia: ceil(n/2) + 1
        POW,       // Nakamoto, Ghost: ceil(n/2) + 1
        DAG        // Tangle, DAGsper: variable, typically lower
    }
    
    // Memory optimization: limit list sizes to prevent unbounded growth
    private static final int MAX_LIST_SIZE = 10000;
    
    // Metric 1: Block Finalization Time (Tb)
    // Metric 1: Block Finalization Time (Tb)
    private double totalFinalizationTime;  // in seconds
    private int blockCount;
    
    // Metric 2: Network Traffic for Finalization (Cb)
    private long totalTraffic;             // in bytes
    private int trafficBlockCount;
    
    private int totalBlocksGenerated;
    
    // Metric 3: Transaction Confirmation Latency (Tt)
    private double totalConfirmationTime;  // in seconds
    private int confirmationCount;         // number of confirmed transactions
    private List<Long> confirmationLatencies;  // in milliseconds (for percentile calculation)
    
    // Metric 4: Byzantine Fault Tolerance (BFT)
    private int byzantineValidators;       // malicious nodes
    private int totalValidators;
    private ConsensusType consensusType;   // for BFT threshold calculation
    // Empirical BFT measurement observed during simulation (ratio 0.0 - 1.0)
    private double empiricalByzantineFaultTolerance;
    // Aggregated vote counts observed during the run (used to compute empirical BFT more robustly)
    private long empiricalVotesByzantineCount;
    private long empiricalVotesTotalCount;
    
    // Metric 5: Double-spending Success Probability (Pdv)
    private int doubleSpendAttempts;       // total attacks injected
    private int doubleSpendSuccesses;      // attacks that succeeded
    
    // Additional tracking
    private List<Long> blockFinalizationTimes;
    private List<Long> blockTraffics;
    
    // Hybrid network metrics
    private boolean hybridMode;                    // true if running hybrid public/private scenario
    private double privateTransactionPercentage;    // % of private transactions (0-100)
    private int publicTransactionCount;
    private int privateTransactionCount;
    private long publicTraffic;                    // traffic from public transactions
    private long privateTraffic;                   // traffic from private transactions
    
    // ===== Byzantine sweep test results =====
    // Map: byzantineValidators -> secure (true if protocol remained secure)
    private SortedMap<Integer, Boolean> byzantineTestResults;
    
    public SimulationMetrics() {
        this.totalFinalizationTime = 0;
        this.blockCount = 0;
        this.totalTraffic = 0;
        this.trafficBlockCount = 0;
        this.totalBlocksGenerated = 0;
        this.totalConfirmationTime = 0;
        this.confirmationCount = 0;
        this.byzantineValidators = 0;
        this.totalValidators = 0;
        this.consensusType = ConsensusType.VOTING;  // default to voting-based
        this.doubleSpendAttempts = 0;
        this.doubleSpendSuccesses = 0;
        
        this.blockFinalizationTimes = new ArrayList<>();
        this.blockTraffics = new ArrayList<>();
        this.confirmationLatencies = new ArrayList<>();
        
        // Initialize hybrid metrics
        this.hybridMode = false;
        this.privateTransactionPercentage = 0;
        this.publicTransactionCount = 0;
        this.privateTransactionCount = 0;
        this.publicTraffic = 0;
        this.privateTraffic = 0;
        this.byzantineTestResults = new TreeMap<>();
    }
    
    // ===== METRIC 1: Block Finalization Time (Tb) =====
    /**
     * Record a block finalization time
     * @param finalizationTimeSeconds time in seconds
     */
    public void recordBlockFinalizationTime(double finalizationTimeSeconds) {
        this.totalFinalizationTime += finalizationTimeSeconds;
        this.blockFinalizationTimes.add((long)(finalizationTimeSeconds * 1000)); // store in ms
        while (this.blockFinalizationTimes.size() > MAX_LIST_SIZE) {
            this.blockFinalizationTimes.remove(0);
        }
        this.blockCount++;
    }
    
    /**
     * Get average block finalization time in seconds
     */
    public double getAverageBlockFinalizationTime() {
        return blockCount == 0 ? 0 : totalFinalizationTime / blockCount;
    }
    
    /**
     * Get percentile finalization time
     * @param percentile 50, 95, 99, 99.9
     */
    public double getPercentileFinalizationTime(double percentile) {
        if (blockFinalizationTimes.isEmpty()) return 0;
        Collections.sort(blockFinalizationTimes);
        int index = (int) Math.ceil((percentile / 100.0) * blockFinalizationTimes.size()) - 1;
        index = Math.max(0, Math.min(index, blockFinalizationTimes.size() - 1));
        return blockFinalizationTimes.get(index) / 1000.0; // convert to seconds
    }
    
    // ===== METRIC 2: Network Traffic for Finalization (Cb) =====
    /**
     * Record network traffic for a block finalization
     * @param trafficBytes total bytes transmitted
     */
    public void recordBlockTraffic(long trafficBytes) {
        this.totalTraffic += trafficBytes;
        this.blockTraffics.add(trafficBytes);
        while (this.blockTraffics.size() > MAX_LIST_SIZE) {
            this.blockTraffics.remove(0);
        }
        this.trafficBlockCount++;
    }
    
    /**
     * Get average traffic per block in MB
     */
    public double getAverageTrafficPerBlock() {
        if (trafficBlockCount == 0) return 0;
        // Convert bytes to MB: 1 MB = 1,048,576 bytes
        return (totalTraffic / (double)trafficBlockCount) / (1024.0 * 1024.0);
    }
    
    /**
     * Get percentile traffic per block in MB
     */
    public double getPercentileTraffic(double percentile) {
        if (blockTraffics.isEmpty()) return 0;
        Collections.sort(blockTraffics);
        int index = (int) Math.ceil((percentile / 100.0) * blockTraffics.size()) - 1;
        index = Math.max(0, Math.min(index, blockTraffics.size() - 1));
        return blockTraffics.get(index) / (1024.0 * 1024.0); // convert to MB
    }
    
    /**
     * Record total blocks generated
     */
    public void recordBlockGenerated() {
        this.totalBlocksGenerated++;
    }
    
    // ===== METRIC 3: Transaction Confirmation Latency (Tt) =====
    /**
     * Record a transaction confirmation latency
     * @param latencySeconds time in seconds from submission to finalization
     */
    public void recordTransactionConfirmation(double latencySeconds) {
        this.totalConfirmationTime += latencySeconds;
        this.confirmationLatencies.add((long)(latencySeconds * 1000)); // store in ms
        while (this.confirmationLatencies.size() > MAX_LIST_SIZE) {
            this.confirmationLatencies.remove(0);
        }
        this.confirmationCount++;
    }
    
    /**
     * Get average transaction confirmation latency in seconds
     */
    public double getAverageTransactionConfirmationLatency() {
        return confirmationCount == 0 ? 0 : totalConfirmationTime / confirmationCount;
    }
    
    /**
     * Get percentile transaction confirmation latency
     * @param percentile 50, 95, 99, 99.9
     */
    public double getPercentileConfirmationLatency(double percentile) {
        if (confirmationLatencies.isEmpty()) return 0;
        Collections.sort(confirmationLatencies);
        int index = (int) Math.ceil((percentile / 100.0) * confirmationLatencies.size()) - 1;
        index = Math.max(0, Math.min(index, confirmationLatencies.size() - 1));
        return confirmationLatencies.get(index) / 1000.0; // convert to seconds
    }
    
    // ===== METRIC 4: Byzantine Fault Tolerance (BFT) =====
    /**
     * Set the number of Byzantine validators in the network
     */
    public void setByzantineValidators(int count) {
        this.byzantineValidators = count;
    }
    
    /**
     * Set the total number of validators in the network
     */
    public void setTotalValidators(int count) {
        this.totalValidators = count;
    }
    
    /**
     * Set the consensus protocol type for BFT threshold calculation
     */
    public void setConsensusType(ConsensusType type) {
        this.consensusType = type;
    }

    /**
     * Store an empirical BFT value observed during the run (0.0 - 1.0)
     */
    public void setEmpiricalByzantineFaultTolerance(double value) {
        this.empiricalByzantineFaultTolerance = value;
    }

    /**
     * Record aggregated votes observed for finalizations (byzantine votes and total votes).
     * These are accumulated across the run and used to compute a robust empirical BFT ratio.
     */
    public synchronized void recordEmpiricalVotes(int byzantineVotes, int totalVotes) {
        if (totalVotes <= 0) return;
        this.empiricalVotesByzantineCount += Math.max(0, byzantineVotes);
        this.empiricalVotesTotalCount += totalVotes;
    }

    /**
     * Get the empirical BFT value observed during the run (0.0 - 1.0)
     */
    public double getEmpiricalByzantineFaultTolerance() {
        // If we have aggregated votes, prefer the ratio computed from those counts
        if (this.empiricalVotesTotalCount > 0) {
            double honest = (this.empiricalVotesTotalCount - this.empiricalVotesByzantineCount) / (double) this.empiricalVotesTotalCount;
            return honest;
        }
        return this.empiricalByzantineFaultTolerance;
    }
    
    /**
     * Get Byzantine Fault Tolerance percentage
     * BFT = (attack_threshold / total_validators) × 100
     * Attack threshold = minimum malicious nodes needed to commit fraudulent transaction
     * 
     * @return BFT percentage (0-100)
     */
    public double getByzantineFaultTolerance() {
        int threshold = getBFTAttackThreshold();
        if (totalValidators == 0) return 0;
        return (threshold / (double)totalValidators) * 100.0;
    }
    
    /**
     * Get the Byzantine Fault Tolerance attack threshold
     * Returns minimum number of malicious nodes needed to successfully commit
     * a fraudulent transaction with false data
     * 
     * Threshold depends on consensus type:
     * - VOTING (Arq-REDD+, pBFT): floor((n-1)/3) + 1
     * - POS/POW (Casper, Nakamoto): ceil(n/2) + 1
     * - DAG (Tangle): lower threshold, approximated as ceil(n/10) + 1
     * 
     * @return Minimum malicious nodes for successful attack
     */
    public int getBFTAttackThreshold() {
        if (totalValidators == 0) return 0;
        
        switch (consensusType) {
            case VOTING:
                return (totalValidators - 1) / 3 + 1;
            case POS:
                // PoS finality (e.g., Casper FFG) tolerates up to < n/3 Byzantine validators
                return (totalValidators - 1) / 3 + 1;
            case POW:
                // PoW requires majority for typical attacks
                return (int) Math.ceil(totalValidators / 2.0) + 1;
            case DAG:
                return (int) Math.ceil(totalValidators / 10.0) + 1;  // approximate for DAG
            default:
                return (totalValidators - 1) / 3 + 1;  // default to voting
        }
    }

    /**
     * Record the result of a Byzantine tolerance test for a specific byzantine validator count.
     * @param byzantineCount number of malicious validators used in the test
     * @param secure true if the protocol remained within security bounds for this test
     */
    public void recordByzantineTestResult(int byzantineCount, boolean secure) {
        this.byzantineTestResults.put(byzantineCount, secure);
    }

    /**
     * Return the maximum tolerable Byzantine percentage based on recorded test results.
     * This inspects all recorded tests and returns the highest tested percentage that
     * still met the security criteria (secure == true).
     */
    public double getMaxTolerableByzantinePercentage() {
        if (byzantineTestResults.isEmpty() || totalValidators == 0) return 0;
        int maxCount = -1;
        for (Map.Entry<Integer, Boolean> e : byzantineTestResults.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                maxCount = Math.max(maxCount, e.getKey());
            }
        }
        if (maxCount < 0) return 0;
        return (maxCount / (double) totalValidators) * 100.0;
    }

    /**
     * Return the first tested Byzantine percentage that was recorded as INSECURE.
     * Returns -1 if all tested percentages were secure or no tests were recorded.
     */
    public double getFirstInsecureByzantinePercentage() {
        if (byzantineTestResults.isEmpty() || totalValidators == 0) return -1;
        for (Map.Entry<Integer, Boolean> e : byzantineTestResults.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                return (e.getKey() / (double) totalValidators) * 100.0;
            }
        }
        return -1;
    }

    /**
     * Return a copy of the recorded Byzantine test results (byzantineCount -> secure)
     */
    public SortedMap<Integer, Boolean> getByzantineTestResults() {
        return new TreeMap<>(this.byzantineTestResults);
    }

    /**
     * Evaluate whether the current metrics meet the supplied security criteria.
     * This provides a simple, reusable check used during automated Byzantine sweeps.
     * @param maxPdvPct maximum acceptable double-spend success probability in percent (e.g. 0.5)
     */
    public boolean evaluateSecurity(double maxPdvPct) {
        // Basic sanity: at least one block finalized
        if (getBlockCount() == 0) {
            // System.out.println("[evaluateSecurity] no blocks finalized -> insecure");
            return false;
        }

        // Check double-spend probability threshold
        double pdv = getDoubleSpendSuccessProbability();
        if (pdv > maxPdvPct) {
            // System.out.println("[evaluateSecurity] pdv " + String.format("%.4f", pdv) + "% > maxPdv " + maxPdvPct + "% -> insecure");
            return false;
        }

        // If empirical BFT was recorded, use it as an additional signal
        // empiricalByzantineFaultTolerance is expected in [0.0, 1.0] where higher is better
        if (this.empiricalByzantineFaultTolerance > 0.0) {
            // System.out.println("[evaluateSecurity] empiricalBFT=" + String.format("%.4f", this.empiricalByzantineFaultTolerance));
            if (this.totalValidators > 0) {
                double thresholdNodes = getBFTAttackThreshold();
                double toleratedDishonestFraction = thresholdNodes / (double) this.totalValidators;
                double requiredEmpiricalBFT = 1.0 - toleratedDishonestFraction; // minimal honest share
                // System.out.println("[evaluateSecurity] totalValidators=" + this.totalValidators +
                //         ", thresholdNodes=" + thresholdNodes +
                //         ", toleratedDishonestFraction=" + String.format("%.4f", toleratedDishonestFraction) +
                //         ", requiredEmpiricalBFT=" + String.format("%.4f", requiredEmpiricalBFT));
                boolean ok = this.empiricalByzantineFaultTolerance >= requiredEmpiricalBFT;
                // System.out.println("[evaluateSecurity] result from empiricalBFT -> " + ok);
                return ok;
            } else {
                boolean ok = this.empiricalByzantineFaultTolerance >= 0.5;
                // System.out.println("[evaluateSecurity] no totalValidators available, empirical>0.5 -> " + ok);
                return ok;
            }
        }

        // Fallback: use configured byzantineValidators vs theoretical threshold
        if (this.totalValidators > 0) {
            int threshold = getBFTAttackThreshold();
            boolean ok = this.byzantineValidators <= threshold;
            // System.out.println("[evaluateSecurity] fallback: byzantineValidators=" + this.byzantineValidators +
            //         ", threshold=" + threshold + " -> " + ok);
            return ok;
        }

        // Default conservative answer
        // System.out.println("[evaluateSecurity] insufficient info -> insecure");
        return false;
    }

    /**
     * Evaluate security from aggregated average metrics across trials.
     * Uses the same decision logic as instance-level `evaluateSecurity` but
     * accepts averages computed externally.
     *
     * @param consensusType consensus family (VOTING/POS/POW/DAG)
     * @param maxPdvPct acceptable double-spend probability (percent)
     * @param avgPdvPct observed average double-spend probability (percent)
     * @param avgEmpiricalBft observed empirical BFT (ratio 0.0-1.0)
     * @param totalValidators total validators (n)
     * @param byzantineValidators configured byzantine count (f)
     * @return true if deemed secure based on averages
     */
    public static boolean evaluateSecurityFromAverages(ConsensusType consensusType,
                                                      double maxPdvPct,
                                                      double avgPdvPct,
                                                      double avgEmpiricalBft,
                                                      int totalValidators,
                                                      int byzantineValidators) {
        System.out.println("[evaluateSecurityFromAverages] entry: avgPdv=" + String.format("%.4f", avgPdvPct) + "%" +
                ", maxPdv=" + maxPdvPct + "%");

        // Check double-spend probability threshold
        if (avgPdvPct > maxPdvPct) {
            System.out.println("[evaluateSecurityFromAverages] avgPdv " + String.format("%.4f", avgPdvPct) + "% > maxPdv " + maxPdvPct + "% -> insecure");
            return false;
        }

        // Use empirical BFT if provided
        if (avgEmpiricalBft > 0.0) {
            System.out.println("[evaluateSecurityFromAverages] empiricalBFT=" + String.format("%.4f", avgEmpiricalBft));
            if (totalValidators > 0) {
                int thresholdNodes;
                switch (consensusType) {
                    case VOTING:
                        thresholdNodes = (totalValidators - 1) / 3 + 1;
                        break;
                    case POS:
                        thresholdNodes = (totalValidators - 1) / 3 + 1;
                        break;
                    case POW:
                        thresholdNodes = (int) Math.ceil(totalValidators / 2.0) + 1;
                        break;
                    case DAG:
                        thresholdNodes = (int) Math.ceil(totalValidators / 10.0) + 1;
                        break;
                    default:
                        thresholdNodes = (totalValidators - 1) / 3 + 1;
                }
                double toleratedDishonestFraction = thresholdNodes / (double) totalValidators;
                double requiredEmpiricalBFT = 1.0 - toleratedDishonestFraction;
                System.out.println("[evaluateSecurityFromAverages] totalValidators=" + totalValidators + ", thresholdNodes=" + thresholdNodes +
                        ", toleratedDishonestFraction=" + String.format("%.4f", toleratedDishonestFraction) +
                        ", requiredEmpiricalBFT=" + String.format("%.4f", requiredEmpiricalBFT));
                boolean ok = avgEmpiricalBft >= requiredEmpiricalBFT;
                System.out.println("[evaluateSecurityFromAverages] result from empiricalBFT -> " + ok);
                return ok;
            } else {
                boolean ok = avgEmpiricalBft >= 0.5;
                System.out.println("[evaluateSecurityFromAverages] no totalValidators available, empirical>0.5 -> " + ok);
                return ok;
            }
        }

        // Fallback: configured byzantineValidators vs theoretical threshold
        if (totalValidators > 0) {
            int threshold;
            switch (consensusType) {
                case VOTING:
                    threshold = (totalValidators - 1) / 3 + 1;
                    break;
                case POS:
                case POW:
                    threshold = (int) Math.ceil(totalValidators / 2.0) + 1;
                    break;
                case DAG:
                    threshold = (int) Math.ceil(totalValidators / 10.0) + 1;
                    break;
                default:
                    threshold = (totalValidators - 1) / 3 + 1;
            }
            boolean ok = byzantineValidators <= threshold;
            System.out.println("[evaluateSecurityFromAverages] fallback: byzantineValidators=" + byzantineValidators + ", threshold=" + threshold + " -> " + ok);
            return ok;
        }

        System.out.println("[evaluateSecurityFromAverages] insufficient info -> insecure");
        return false;
    }
    
    // ===== METRIC 5: Double-spending Success Probability (Pdv) =====
    /**
     * Record a double-spending attack attempt
     */
    public void recordDoubleSpendAttempt() {
        this.doubleSpendAttempts++;
    }
    
    /**
     * Record a successful double-spending attack
     */
    public void recordDoubleSpendSuccess() {
        this.doubleSpendSuccesses++;
        this.doubleSpendAttempts++;
    }
    
    /**
     * Get double-spending success probability as percentage
     * Pdv = (successful_attacks / total_attempts) * 100
     */
    public double getDoubleSpendSuccessProbability() {
        if (doubleSpendAttempts == 0) return 0;
        return (doubleSpendSuccesses / (double)doubleSpendAttempts) * 100.0;
    }
    
    // ===== SUMMARY METHODS =====
    
    /**
     * Get all metrics as a formatted report
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== SIMULATION METRICS REPORT ==========\n");
        sb.append(String.format("Metric 1 - Tb (Block Finalization Time):\n"));
        sb.append(String.format("  Average: %.3f seconds/block\n", getAverageBlockFinalizationTime()));
        sb.append(String.format("  p50: %.3f seconds\n", getPercentileFinalizationTime(50)));
        sb.append(String.format("  p95: %.3f seconds\n", getPercentileFinalizationTime(95)));
        sb.append(String.format("  p99: %.3f seconds\n\n", getPercentileFinalizationTime(99)));
        
        sb.append(String.format("Metric 2 - Cb (Network Traffic for Finalization):\n"));
        sb.append(String.format("  Average: %.6f MB/block\n", getAverageTrafficPerBlock()));
        sb.append(String.format("  p50: %.6f MB\n", getPercentileTraffic(50)));
        sb.append(String.format("  p95: %.6f MB\n", getPercentileTraffic(95)));
        sb.append(String.format("  p99: %.6f MB\n\n", getPercentileTraffic(99)));
        
        sb.append(String.format("Metric 3 - Tt (Transaction Confirmation Latency):\n"));
        sb.append(String.format("  Average: %.3f seconds/tx\n", getAverageTransactionConfirmationLatency()));
        sb.append(String.format("  p50: %.3f seconds\n", getPercentileConfirmationLatency(50)));
        sb.append(String.format("  p95: %.3f seconds\n", getPercentileConfirmationLatency(95)));
        sb.append(String.format("  p99: %.3f seconds\n\n", getPercentileConfirmationLatency(99)));
        
        sb.append(String.format("Metric 4 - BFT (Byzantine Fault Tolerance):\n"));
        sb.append(String.format("  Current: %.3f%% (%.0f Byzantine / %.0f Total)\n", 
            getByzantineFaultTolerance(), (double)byzantineValidators, (double)totalValidators));
        sb.append(String.format("  Threshold: %.3f%% (f < n/3)\n"));
        
        sb.append(String.format("Metric 5 - Pdv (Double-spending Success Probability):\n"));
        sb.append(String.format("  Probability: %.3f%% (%.0f successful / %.0f attempts)\n", 
            getDoubleSpendSuccessProbability(), (double)doubleSpendSuccesses, (double)doubleSpendAttempts));
        
        sb.append("=======================================\n");
        return sb.toString();
    }
    
    /**
     * Export metrics in CSV format for analysis
     */
    public String[] exportAsCSV() {
        return new String[]{
            Double.toString(getAverageBlockFinalizationTime()),
            Double.toString(getPercentileFinalizationTime(95)),
            Double.toString(getPercentileFinalizationTime(99)),
            Double.toString(getAverageTrafficPerBlock()),
            Double.toString(getPercentileTraffic(95)),
            Double.toString(getPercentileTraffic(99)),
            Double.toString(getAverageTransactionConfirmationLatency()),
            Double.toString(getPercentileConfirmationLatency(95)),
            Double.toString(getPercentileConfirmationLatency(99)),
            Double.toString(getByzantineFaultTolerance()),
            Integer.toString(byzantineValidators),
            Integer.toString(totalValidators),
            Double.toString(getDoubleSpendSuccessProbability()),
            Integer.toString(doubleSpendSuccesses),
            Integer.toString(doubleSpendAttempts)
        };
    }
    
    // ===== GETTERS =====
    public double getTotalFinalizationTime() { return totalFinalizationTime; }
    public int getBlockCount() { return blockCount; }
    public long getTotalTraffic() { return totalTraffic; }
    public int getTrafficBlockCount() { return trafficBlockCount; }
    public int getTotalBlocksGenerated() { return totalBlocksGenerated; }
    public double getTotalConfirmationTime() { return totalConfirmationTime; }
    public int getConfirmationCount() { return confirmationCount; }
    public int getByzantineValidators() { return byzantineValidators; }
    public int getTotalValidators() { return totalValidators; }
    public int getDoubleSpendAttempts() { return doubleSpendAttempts; }
    public int getDoubleSpendSuccesses() { return doubleSpendSuccesses; }
    public List<Long> getBlockFinalizationTimes() { return blockFinalizationTimes; }
    public List<Long> getBlockTraffics() { return blockTraffics; }
    public List<Long> getConfirmationLatencies() { return confirmationLatencies; }
    
    // ===== HYBRID NETWORK METRICS =====
    
    /**
     * Enable hybrid network mode tracking
     */
    public void setHybridMode(boolean enabled) {
        this.hybridMode = enabled;
    }
    
    /**
     * Set expected percentage of private transactions
     */
    public void setPrivateTransactionPercentage(double percentage) {
        this.privateTransactionPercentage = percentage;
    }
    
    /**
     * Record a public transaction
     */
    public void recordPublicTransaction(long trafficBytes) {
        this.publicTransactionCount++;
        this.publicTraffic += trafficBytes;
    }
    
    /**
     * Record a private transaction
     */
    public void recordPrivateTransaction(long trafficBytes) {
        this.privateTransactionCount++;
        this.privateTraffic += trafficBytes;
    }
    
    /**
     * Get total transaction count (public + private)
     */
    public int getTotalTransactionCount() {
        return publicTransactionCount + privateTransactionCount;
    }
    
    /**
     * Get actual percentage of private transactions
     */
    public double getActualPrivatePercentage() {
        int total = getTotalTransactionCount();
        return total == 0 ? 0 : (privateTransactionCount / (double)total) * 100.0;
    }
    
    /**
     * Get average traffic per public transaction (MB)
     */
    public double getAveragePublicTransactionTraffic() {
        return publicTransactionCount == 0 ? 0 : 
            (publicTraffic / (double)publicTransactionCount) / (1024.0 * 1024.0);
    }
    
    /**
     * Get average traffic per private transaction (MB)
     */
    public double getAveragePrivateTransactionTraffic() {
        return privateTransactionCount == 0 ? 0 : 
            (privateTraffic / (double)privateTransactionCount) / (1024.0 * 1024.0);
    }
    
    /**
     * Calculate privacy overhead ratio
     * How much extra traffic private transactions generate vs public
     * Ratio > 1.0 means private transactions cost more
     */
    public double getPrivacyOverheadRatio() {
        double publicAvg = getAveragePublicTransactionTraffic();
        double privateAvg = getAveragePrivateTransactionTraffic();
        return publicAvg == 0 ? 0 : privateAvg / publicAvg;
    }
    
    /**
     * Check if hybrid mode is enabled
     */
    public boolean isHybridMode() {
        return hybridMode;
    }
    
    /**
     * Get hybrid network metrics summary
     */
    public String getHybridMetricsSummary() {
        if (!hybridMode) {
            return "Hybrid mode not enabled";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("========== HYBRID NETWORK METRICS ==========\n");
        sb.append(String.format("Total Transactions: %d\n", getTotalTransactionCount()));
        sb.append(String.format("Public: %d (%.1f%%)\n", publicTransactionCount, 
            100.0 - getActualPrivatePercentage()));
        sb.append(String.format("Private: %d (%.1f%%)\n", privateTransactionCount, 
            getActualPrivatePercentage()));
        sb.append(String.format("\nTraffic Analysis:\n"));
        sb.append(String.format("Avg Public Tx: %.6f MB\n", getAveragePublicTransactionTraffic()));
        sb.append(String.format("Avg Private Tx: %.6f MB\n", getAveragePrivateTransactionTraffic()));
        sb.append(String.format("Privacy Overhead Ratio: %.2fx\n", getPrivacyOverheadRatio()));
        sb.append("===========================================\n");
        return sb.toString();
    }
    
    // ===== STANDARD DEVIATION CALCULATIONS =====
    /**
     * Calculate standard deviation for block finalization times (Tb)
     * @return Standard deviation in seconds
     */
    public double getStandardDeviationFinalizationTime() {
        if (blockFinalizationTimes.isEmpty() || blockFinalizationTimes.size() < 2) {
            return 0;
        }
        
        double mean = getAverageBlockFinalizationTime();
        double sumSquaredDiffs = 0;
        
        for (long time : blockFinalizationTimes) {
            double timeInSeconds = time / 1000.0;
            double diff = timeInSeconds - mean;
            sumSquaredDiffs += diff * diff;
        }
        
        double variance = sumSquaredDiffs / blockFinalizationTimes.size();
        return Math.sqrt(variance);
    }
    
    /**
     * Calculate standard deviation for network traffic (Cb)
     * @return Standard deviation in MB
     */
    public double getStandardDeviationTraffic() {
        if (blockTraffics.isEmpty() || blockTraffics.size() < 2) {
            return 0;
        }
        
        double mean = getAverageTrafficPerBlock();
        double sumSquaredDiffs = 0;
        
        for (long traffic : blockTraffics) {
            double trafficInMB = traffic / (1024.0 * 1024.0);
            double diff = trafficInMB - mean;
            sumSquaredDiffs += diff * diff;
        }
        
        double variance = sumSquaredDiffs / blockTraffics.size();
        return Math.sqrt(variance);
    }
    
    /**
     * Calculate standard deviation for transaction confirmation latency (Tt)
     * @return Standard deviation in seconds
     */
    public double getStandardDeviationConfirmationLatency() {
        if (confirmationLatencies.isEmpty() || confirmationLatencies.size() < 2) {
            return 0;
        }
        
        double mean = getAverageTransactionConfirmationLatency();
        double sumSquaredDiffs = 0;
        
        for (long latency : confirmationLatencies) {
            double latencyInSeconds = latency / 1000.0;
            double diff = latencyInSeconds - mean;
            sumSquaredDiffs += diff * diff;
        }
        
        double variance = sumSquaredDiffs / confirmationLatencies.size();
        return Math.sqrt(variance);
    }
}

