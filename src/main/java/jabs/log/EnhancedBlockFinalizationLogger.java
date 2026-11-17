package jabs.log;

import jabs.ledgerdata.Block;
import jabs.ledgerdata.Tx;
import jabs.ledgerdata.TxSequencer;
import jabs.ledgerdata.DoubleSpendTracker;
import jabs.ledgerdata.TransactionSubmissionRegistry;
import jabs.network.node.nodes.Node;
import jabs.simulator.event.AbstractLogEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.BlockProposalEvent;
import jabs.metrics.SimulationMetrics;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.consensus.algorithm.AbstractConsensusAlgorithm;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Enhanced block finalization logger that tracks all 5 metrics:
 * 1. Tb  - Average block finalization time (s/block)
 * 2. Cb  - Average network traffic for finalization (MB/block)
 * 4. BFT - Byzantine Fault Tolerance (percentage)
 * 5. Pdv - Double-spending success probability (percentage)
 */
public class EnhancedBlockFinalizationLogger extends AbstractCSVLogger {
    private final SimulationMetrics metrics;
    private final DoubleSpendTracker doubleSpendTracker;
    private final TransactionSubmissionRegistry transactionRegistry;
    
    // Chave estavel por valor das transacoes DESTA simulacao: id sequencial (Long) por instancia de logger, nunca
    // estatico (duas simulacoes na mesma JVM numeram do zero e de forma independente). Substitui o Hash da transacao
    // (sem hashCode/equals: identidade) como chave de confirmedTransactionIds, blockTransactions e
    // DoubleSpendTracker.txToBlockHeights; com -XX:hashCode=2 o Hash cai todo no mesmo balde e o custo vira O(n^2) por bloco.
    private final TxSequencer txSequencer = new TxSequencer();

    // Track finalized blocks by height for double-spend detection
    private final Map<Integer, Set<Object>> blockTransactions; // height -> set of tx ids (Long, ver txSequencer)
    
    // Track all proposed blocks
    private final Map<Integer, Set<Object>> blocksByHeight;   // height -> set of block ids
    
    // Track previously finalized blocks for reorg detection
    private final Set<Object> finalizedBlockIds;
    private final Map<Object, Integer> finalizedBlockHeights;
    
    // Track confirmed transactions to avoid duplicate Tt measurements
    private final Set<Object> confirmedTransactionIds;
    
    // Track processed block heights to avoid duplicate metrics from multi-node finalization
    private final Set<Integer> processedHeights;
    
    // Cache for reflection methods to improve performance
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> getTransactionsMethods = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> getHashMethods = new java.util.HashMap<>();
    
    /**
     * Creates an enhanced CSV logger with metrics tracking
     * @param writer this is output CSV of the logger
     * @param metrics the metrics collector
     */
    public EnhancedBlockFinalizationLogger(Writer writer, SimulationMetrics metrics) {
        super(writer);
        this.metrics = metrics;
        this.doubleSpendTracker = new DoubleSpendTracker();
        this.transactionRegistry = new TransactionSubmissionRegistry();
        this.blockTransactions = new HashMap<>();
        this.blocksByHeight = new HashMap<>();
        this.finalizedBlockIds = new HashSet<>();
        this.finalizedBlockHeights = new HashMap<>();
        this.confirmedTransactionIds = new HashSet<>();
        this.processedHeights = new HashSet<>();
        // Register this instance's registry globally for transaction tracking
        TransactionSubmissionTracker.setRegistry(this.transactionRegistry);
    }

    /**
     * Creates an enhanced CSV logger with metrics tracking
     * @param path this is output path of CSV file
     * @param metrics the metrics collector
     */
    public EnhancedBlockFinalizationLogger(Path path, SimulationMetrics metrics) throws IOException {
        super(path);
        this.metrics = metrics;
        this.doubleSpendTracker = new DoubleSpendTracker();
        this.transactionRegistry = new TransactionSubmissionRegistry();
        this.blockTransactions = new HashMap<>();
        this.blocksByHeight = new HashMap<>();
        this.finalizedBlockIds = new HashSet<>();
        this.finalizedBlockHeights = new HashMap<>();
        this.confirmedTransactionIds = new HashSet<>();
        this.processedHeights = new HashSet<>();
        // Register this instance's registry globally for transaction tracking
        TransactionSubmissionTracker.setRegistry(this.transactionRegistry);
    }

    @Override
    protected String csvStartingComment() {
        // Granularidade de C_B declarada no cabecalho (ADR-0006): bytes RECEBIDOS pelo no que finalizou
        // primeiro cada altura (nao e agregado de rede). TrafficUntilFinalization = C_B_total;
        // TrafficConsensusUntilFinalization = C_B_consenso, e -1 significa "nao medido" (nao e zero).
        String base = String.format("Simulation name: %s      Number of nodes: %d      Network type: %s      "
                + "Cb granularity: bytes received by first-finalizing node per height; "
                + "TrafficUntilFinalization=C_B_total, TrafficConsensusUntilFinalization=C_B_consenso (-1=not measured)",
            scenario.getName(),
            this.scenario.getNetwork().getAllNodes().size(), 
            this.scenario.getNetwork().getClass().getSimpleName());
        if (selfDeliveryColumn()) {
            // D2: so quando a coluna existe (PBFT, Parlia, Casper, Nakamoto); os demais protocolos mantem o comentario de antes
            // String.concat (nao '+'): nenhum callsite de concatenacao por invokedynamic novo (consome hash de identidade)
            base = base.concat("      Cb_autoentrega_B=bytes the node delivered to itself (no network hop counted), "
                    .concat("EXCLUDED from TrafficUntilFinalization/TrafficConsensusUntilFinalization (D2, ADR-0015); same node and "
                    .concat("height as the Cb columns; -1=not measured (never zero)")));
        }
        return base;
    }

    private Boolean selfDeliveryColumn;

    /**
     * D2: a coluna de autoentrega existe so se o consenso dos nos separa a autoentrega
     * ({@link jabs.consensus.algorithm.AbstractChainBasedConsensus#separatesSelfDelivery}). Decidida uma vez, depois do
     * cenario montado (initialLog roda apos createNetwork). Sem cenario/rede/no peer: sem coluna (saida de antes).
     */
    private boolean selfDeliveryColumn() {
        if (selfDeliveryColumn == null) {
            boolean on = false;
            if (this.scenario != null && this.scenario.getNetwork() != null) {
                for (Object o : this.scenario.getNetwork().getAllNodes()) {
                    if (o instanceof PeerBlockchainNode) {
                        on = ((PeerBlockchainNode<?, ?>) o).getConsensusAlgorithm().separatesSelfDelivery();
                        break;
                    }
                }
            }
            selfDeliveryColumn = on;
        }
        return selfDeliveryColumn;
    }

    @Override
    protected boolean csvOutputConditionBeforeEvent(Event event) {
        return false;
    }

    @Override
    protected boolean csvOutputConditionAfterEvent(Event event) {
        if (event instanceof BlockFinalizationEvent) {
            BlockFinalizationEvent finalizationEvent = (BlockFinalizationEvent) event;
            Block block = finalizationEvent.getBlock();
            int height = block.getHeight();
            
            // In Byzantine consensus (PBFT), all nodes finalize the same block at each height
            // Only process the first finalization event per height to avoid duplicate metrics
            if (processedHeights.contains(height)) {
                return false; // Skip: already processed this height
            }
            processedHeights.add(height);
            
            Object blockId = block.hashCode();
            
            // Track this block by height
            Set<Object> blocksAtHeight = blocksByHeight.computeIfAbsent(height, k -> new HashSet<>());
            blocksAtHeight.add(blockId);
            
            // Mark as finalized
            finalizedBlockIds.add(blockId);
            finalizedBlockHeights.put(blockId, height);
            
            // Collect Metric 1: Block finalization time (Tb)
            double finalizationTime = this.scenario.getSimulator().getSimulationTime() - block.getCreationTime();
            metrics.recordBlockFinalizationTime(finalizationTime);
            
            // Collect Metric 2: Network traffic for finalization (Cb)
            long traffic = finalizationEvent.getTrafficUntilFinalization();
            metrics.recordBlockTraffic(traffic);
            metrics.recordFirstNodeCb(height, traffic); // HS-CB-fase2: mesmo valor, guardado por altura (aditivo)
            // D2: autoentrega do mesmo no/altura, fora do Cb; sentinela (-1) e ignorada (protocolo nao a separa)
            metrics.recordSelfDelivery(finalizationEvent.getSelfDeliveryTraffic(),
                    finalizationEvent.getSelfDeliveryConsensusTraffic());
            
            metrics.recordBlockGenerated();
            
            // Collect Metric 5: Double-spending (Pdv)
            // Extract and track transactions from this finalized block
            Map<Object, Object> txIds = extractTransactionsFromBlock(block);
            
            if (!txIds.isEmpty()) {
                blockTransactions.put(block.getHeight(), new LinkedHashSet<Object>(txIds.keySet()));
                double finalizationTimestamp = this.scenario.getSimulator().getSimulationTime();
                int confirmedCount = 0;
                int registeredCount = 0;
                int fallbackCount = 0;
                int skippedDuplicates = 0;
                
                for (Map.Entry<Object, Object> txEntry : txIds.entrySet()) {
                    // txId: chave por valor (id sequencial desta simulacao); registryKey: a chave ORIGINAL (Hash da
                    // transacao), usada so para achar o instante de submissao que BitcoinNode/EthereumNode registram
                    // com tx.getHash() (a semantica do Tt nao muda).
                    Object txId = txEntry.getKey();
                    Object registryKey = txEntry.getValue();
                    // Skip if this transaction was already confirmed (avoid duplicate measurements)
                    if (confirmedTransactionIds.contains(txId)) {
                        skippedDuplicates++;
                        continue;
                    }
                    confirmedTransactionIds.add(txId);
                    
                    // Collect Metric 3: Transaction confirmation latency (Tt)
                    Double submissionTime = transactionRegistry.getSubmissionTime(registryKey);
                    if (submissionTime != null) {
                        // If we registered the submission time, use it
                        double confirmationLatency = finalizationTimestamp - submissionTime;
                        metrics.recordTransactionConfirmation(confirmationLatency);
                        confirmedCount++;
                        registeredCount++;
                    } else {
                        // If no submission time was registered, estimate based on block creation time
                        // This is a fallback for nodes that didn't explicitly register transactions
                        double blockCreationTime = block.getCreationTime();
                        double estimatedConfirmationLatency = finalizationTimestamp - blockCreationTime;
                        if (estimatedConfirmationLatency > 0) {
                            metrics.recordTransactionConfirmation(estimatedConfirmationLatency);
                            confirmedCount++;
                            fallbackCount++;
                        }
                    }
                    
                    boolean isDoubleSpend = doubleSpendTracker.recordTransaction(
                        txId,
                        block.getHeight(),
                        block.hashCode() + ""
                    );
                    if (isDoubleSpend) {
                        metrics.recordDoubleSpendAttempt();
                        // Mark as confirmed successful since both blocks finalized
                        doubleSpendTracker.confirmDoubleSpendSuccess(txId);
                        metrics.recordDoubleSpendSuccess();
                        // Add console debug log
                        System.out.printf("Double-spend detected for Tx %s in Block Height %d at time %.2f%n",
                            txId.toString(), block.getHeight(), this.scenario.getSimulator().getSimulationTime());
                    }
                }
            }
            
            // Clear obsolete data periodically to prevent memory leaks
            if (block.getHeight() % 100 == 0) {
                clearObsoleteData(block.getHeight());
            }

            // Collect empirical BFT by aggregating votes across the whole network when possible (Casper PoS)
            Node finalizingNode = finalizationEvent.getNode();
            boolean setFromVotes = false;
            try {
                // Attempt to aggregate votes for this block from all nodes
                java.util.Set<Integer> globalVoterIds = new java.util.HashSet<>();
                java.util.Set<Integer> globalByzantineIds = null;

                for (Object o : this.scenario.getNetwork().getAllNodes()) {
                    if (!(o instanceof PeerBlockchainNode)) continue;
                    PeerBlockchainNode<?, ?> pbn = (PeerBlockchainNode<?, ?>) o;
                    try {
                        AbstractConsensusAlgorithm ca = pbn.getConsensusAlgorithm();
                        if (ca != null && globalByzantineIds == null) {
                            try {
                                if (ca.getByzantineConfig() != null) {
                                    globalByzantineIds = new java.util.HashSet<>(ca.getByzantineConfig().getByzantineValidatorIds());
                                }
                            } catch (Exception ignored) {}
                        }

                        // Try to reflectively access a 'votes' field (CasperFFG, Parlia)
                        try {
                            java.lang.reflect.Field votesField = ca.getClass().getDeclaredField("votes");
                            votesField.setAccessible(true);
                            Object votesObj = votesField.get(ca);
                            if (votesObj instanceof java.util.Map) {
                                java.util.Map<?,?> votesMap = (java.util.Map<?,?>) votesObj;
                                for (java.util.Map.Entry<?,?> entry : votesMap.entrySet()) {
                                    Object key = entry.getKey();
                                    Object votersObj = entry.getValue();
                                    boolean matchesBlock = false;

                                    if (key != null && key.equals(block)) {
                                        matchesBlock = true;
                                    } else if (key != null) {
                                        try {
                                            java.lang.reflect.Method getToBeFinalized = key.getClass().getMethod("getToBeFinalized");
                                            Object toBeFinalized = getToBeFinalized.invoke(key);
                                            if (toBeFinalized != null && toBeFinalized.equals(block)) {
                                                matchesBlock = true;
                                            }
                                        } catch (Exception ignored) {}
                                    }

                                    if (!matchesBlock) continue;

                                    if (votersObj instanceof java.util.Map) {
                                        java.util.Map<?,?> votersMap = (java.util.Map<?,?>) votersObj;
                                        for (Object voter : votersMap.keySet()) {
                                            if (voter instanceof Node) {
                                                globalVoterIds.add(((Node) voter).nodeID);
                                            }
                                        }
                                    } else if (votersObj instanceof java.util.Set) {
                                        java.util.Set<?> votersSet = (java.util.Set<?>) votersObj;
                                        for (Object voter : votersSet) {
                                            if (voter instanceof Node) {
                                                globalVoterIds.add(((Node) voter).nodeID);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (NoSuchFieldException nsf) {
                            // not a vote-based instance; skip
                        } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                }

                if (!globalVoterIds.isEmpty()) {
                    int totalVotes = globalVoterIds.size();
                    int byzVotes = 0;
                    if (globalByzantineIds != null && !globalByzantineIds.isEmpty()) {
                        for (int vid : globalVoterIds) if (globalByzantineIds.contains(vid)) byzVotes++;
                    }
                    double empiricalRatio = (totalVotes - byzVotes) / (double) Math.max(1, totalVotes);
                    metrics.recordEmpiricalVotes(byzVotes, totalVotes);
                    try {
                        jabs.log.BFTDebugAggregator.addPart(block.getHeight(),
                                String.format("aggregatedVotes=byz:%d/total:%d empirical=%.6f", byzVotes, totalVotes, empiricalRatio));
                    } catch (Exception ignored) {}
                    setFromVotes = true;
                }
            } catch (Exception ignored) {}

            // Fallback: if we couldn't aggregate votes, use local algorithm's BFT value as before
            if (!setFromVotes && finalizingNode instanceof PeerBlockchainNode) {
                try {
                    AbstractConsensusAlgorithm algo = ((PeerBlockchainNode) finalizingNode).getConsensusAlgorithm();
                    double empiricalBFT = algo.getByzantineFaultTolerance();
                    if (empiricalBFT > 1.0) empiricalBFT = empiricalBFT / 100.0;
                    metrics.setEmpiricalByzantineFaultTolerance(empiricalBFT);
                    try {
                        jabs.log.BFTDebugAggregator.addPart(block.getHeight(),
                                String.format("localAlgoBFT=%.6f node=%d", empiricalBFT, finalizingNode.nodeID));
                    } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }

            // Emit consolidated debug line for this block (if any parts exist)
            try {
                jabs.log.BFTDebugAggregator.emitAndClear(block.getHeight());
            } catch (Exception ignored) {}

            return true;
        } else if (event instanceof BlockProposalEvent) {
            BlockProposalEvent proposal = (BlockProposalEvent) event;
            Block block = proposal.getBlock();
            recordProposedBlock(block);
            
            return false;
        }
        return false;
    }
    
    /**
     * Extract the transactions of a block using reflection.
     * Attempts to find and invoke getTxs() or getTransactions() or equivalent method
     * Falls back to reflection on private fields if needed
     *
     * <p>A chave do mapa devolvido e o id sequencial por valor ({@link Long}) da transacao nesta simulacao
     * ({@link TxSequencer}); o valor e a chave original ({@link #getTxId}), so para consultar o registro de submissao.
     * A ordem e a de iteracao da colecao do bloco. Item que nao e {@link Tx} (colecao nao tipada ou ramo de campos)
     * nao tem id sequencial: a chave continua a original, como antes (comportamento preservado).
     *
     * @param block The block to extract transactions from
     * @return map chave de contagem/duplicidade -> chave original
     */
    private Map<Object, Object> extractTransactionsFromBlock(Block block) {
        Map<Object, Object> txIds = new LinkedHashMap<>();
        
        try {
            Class<?> blockClass = block.getClass();
            java.lang.reflect.Method method = getTransactionsMethods.get(blockClass);
            if (method == null) {
                // Try getTxs() first (BlockWithTx interface)
                try {
                    method = blockClass.getMethod("getTxs");
                    getTransactionsMethods.put(blockClass, method);
                } catch (NoSuchMethodException e1) {
                    // Try getTransactions() as fallback
                    try {
                        method = blockClass.getMethod("getTransactions");
                        getTransactionsMethods.put(blockClass, method);
                    } catch (NoSuchMethodException e2) {
                        method = null;
                    }
                }
            }
            if (method != null) {
                Object result = method.invoke(block);
                if (result instanceof Collection) {
                    Collection<?> txCollection = (Collection<?>) result;
                    for (Object tx : txCollection) {
                        if (tx != null) {
                            addTx(txIds, tx);
                        }
                    }
                }
            } else {
                // Fallback to field iteration
                for (Field field : blockClass.getDeclaredFields()) {
                    if (Collection.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object fieldValue = field.get(block);
                        if (fieldValue instanceof Collection) {
                            for (Object item : (Collection<?>) fieldValue) {
                                if (item != null && item.getClass().getSimpleName().contains("Tx")) {
                                    addTx(txIds, item);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore extraction errors
        }
        
        return txIds;
    }

    /** Acrescenta {@code tx} ao mapa com o id sequencial desta simulacao (ou, se nao for {@link Tx}, a chave original). */
    private void addTx(Map<Object, Object> txIds, Object tx) {
        Object original = getTxId(tx);
        if (tx instanceof Tx) {
            txIds.put(txSequencer.idOf((Tx<?>) tx), original);
        } else {
            // sem id sequencial possivel: a chave continua a original, como antes (um Long nunca e igual a ela)
            txIds.put(original, original);
        }
    }
    
    /**
     * Chave ORIGINAL da transacao: o resultado de getHash() (o {@code Hash}, comparado por identidade) ou o hashCode do
     * objeto. Ja NAO e a chave de contagem/duplicidade (essa e o id sequencial por valor); serve so para achar, no
     * {@link TransactionSubmissionRegistry}, o instante de submissao registrado por {@code BitcoinNode}/{@code EthereumNode}.
     */
    private Object getTxId(Object tx) {
        try {
            Class<?> txClass = tx.getClass();
            java.lang.reflect.Method hashMethod = getHashMethods.get(txClass);
            if (hashMethod == null) {
                try {
                    hashMethod = txClass.getMethod("getHash");
                    getHashMethods.put(txClass, hashMethod);
                } catch (Exception e) {
                    hashMethod = null;
                }
            }
            if (hashMethod != null) {
                return hashMethod.invoke(tx);
            } else {
                return tx.hashCode();
            }
        } catch (Exception e) {
            return tx.hashCode();
        }
    }
    
    /**
     * Get the double-spend tracker for external usage
     */
    public DoubleSpendTracker getDoubleSpendTracker() {
        return doubleSpendTracker;
    }
    
    /**
     * Get tracked transactions by block height
     */
    public Map<Integer, Set<Object>> getBlockTransactions() {
        return blockTransactions;
    }

    @Override
    protected boolean csvOutputConditionFinalPerNode() {
        return false;
    }

    @Override
    protected String[] csvHeaderOutput() {
        String[] cols = new String[]{
            "Time", 
            "NodeID", 
            "BlockHeight", 
            "BlockHashCode", 
            "BlockSize", 
            "BlockCreationTime", 
            "BlockCreator", 
            "BlockFinalizationTime",
            "TrafficUntilFinalization",
            "Tb_AvgFinalizationTime_s",
            "Cb_AvgTraffic_MB",
            "BFT_ByzantineTolerance_pct",
            "Pdv_DoubleSend_SuccessProbability_pct",
            "TrafficConsensusUntilFinalization"
        };
        if (selfDeliveryColumn()) { // D2: coluna ADITIVA no fim; nome e posicao das existentes intactos
            cols = java.util.Arrays.copyOf(cols, cols.length + 1);
            cols[cols.length - 1] = "Cb_autoentrega_B";
        }
        return cols;
    }

    @Override
    protected String[] csvEventOutput(Event event) {
        // Handle different event types safely to avoid ClassCastException
        if (event instanceof BlockFinalizationEvent) {
            BlockFinalizationEvent finalizationEvent = (BlockFinalizationEvent) event;
            Node node = finalizationEvent.getNode();
            Block block = finalizationEvent.getBlock();

            String[] row = new String[]{
                Double.toString(this.scenario.getSimulator().getSimulationTime()),
                Integer.toString(node.nodeID),
                Integer.toString(block.getHeight()),
                Integer.toString(block.hashCode()),
                Integer.toString(block.getSize()),
                Double.toString(block.getCreationTime()),
                Integer.toString(block.getCreator().nodeID),
                Double.toString(this.scenario.getSimulator().getSimulationTime() - block.getCreationTime()),
                Long.toString(finalizationEvent.getTrafficUntilFinalization()),
                // Metric 1: Tb - Average block finalization time
                Double.toString(metrics.getAverageBlockFinalizationTime()),
                // Metric 2: Cb - Average traffic per block in MB
                Double.toString(metrics.getAverageTrafficPerBlock()),
                // Metric 4: BFT - Byzantine fault tolerance percentage (prefer empirical when available)
                // Report BFT as percentage in CSV (prefer empirical when available)
                Double.toString(metrics.getEmpiricalByzantineFaultTolerance() > 0 ?
                    metrics.getEmpiricalByzantineFaultTolerance() * 100.0 : metrics.getByzantineFaultTolerance()),
                // Metric 5: Pdv - Double-spending success probability
                Double.toString(metrics.getDoubleSpendSuccessProbability()),
                // C_B_consenso (ADR-0006); -1 = protocolo nao mede a decomposicao
                Long.toString(finalizationEvent.getConsensusMessageTraffic())
            };
            if (selfDeliveryColumn()) { // D2: autoentrega do mesmo no e bloco; -1 = nao medido
                row = java.util.Arrays.copyOf(row, row.length + 1);
                row[row.length - 1] = Long.toString(finalizationEvent.getSelfDeliveryTraffic());
            }
            return row;
        }

        // Default: return empty row matching header length
        return new String[csvHeaderOutput().length];
    }
    
    /**
     * Register a newly proposed block
     * Call this when a block is created/proposed but not yet finalized
     * 
     * @param block The proposed block
     */
    public void recordProposedBlock(Block block) {
        if (block != null) {
            Object blockId = block.hashCode();
            Set<Object> blocksAtHeight = blocksByHeight.computeIfAbsent(block.getHeight(), k -> new HashSet<>());
            blocksAtHeight.add(blockId);
        }
    }
    
    /**
     * Register a transaction submission time for Tt metric calculation
     * @param txId Unique transaction identifier
     * @param submissionTime Simulation time when transaction was created (seconds)
     */
    public void recordTransactionSubmission(Object txId, double submissionTime) {
        transactionRegistry.registerSubmission(txId, submissionTime);
    }
    
    /**
     * Get the transaction submission registry for external access
     * @return The TransactionSubmissionRegistry
     */
    public TransactionSubmissionRegistry getTransactionRegistry() {
        return transactionRegistry;
    }
    
    /**
     * Clear obsolete data to prevent memory leaks and performance degradation.
     * Removes data for blocks older than the specified height threshold.
     * 
     * @param currentHeight The current blockchain height
     */
    public void clearObsoleteData(int currentHeight) {
        int threshold = currentHeight - 1000;
        
        // Clear old block transactions
        blockTransactions.entrySet().removeIf(entry -> entry.getKey() < threshold);
        
        // Clear old blocks by height
        blocksByHeight.entrySet().removeIf(entry -> entry.getKey() < threshold);
        
        // Clear old finalized block IDs (keep only recent ones)
        finalizedBlockIds.removeIf(id -> {
            Integer height = finalizedBlockHeights.get(id);
            if (height != null && height < threshold) {
                finalizedBlockHeights.remove(id);
                return true;
            }
            return false;
        });
        
        // Clear double-spend tracker
        doubleSpendTracker.clearObsoleteData(currentHeight);
    }
}