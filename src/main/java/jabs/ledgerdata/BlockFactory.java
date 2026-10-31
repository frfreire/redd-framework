package jabs.ledgerdata;

import jabs.ledgerdata.bitcoin.BitcoinBlockWithoutTx;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.ledgerdata.ethereum.EthereumBlockWithTx;
import jabs.ledgerdata.ethereum.EthereumTx;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffBlockWithTx;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTBlockWithTx;
import jabs.ledgerdata.pbft.PBFTTx;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.ethereum.EthereumMinerNode;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;
import jabs.ledgerdata.snow.SnowBlock;
import jabs.network.node.nodes.snow.SnowNode;

import java.util.HashSet;
import java.util.Set;

public final class BlockFactory {
    public static final int ETHEREUM_BLOCK_HEADER_SIZE = 543; // A header could have variable size but mostly its really close this value
    public static final int ETHEREUM_BLOCK_HASH_SIZE = 36; // 32 byte hash + 4 byte network id
    public static final long ETHEREUM_MIN_DIFFICULTY = 17146335232L;

    public static final int BITCOIN_BLOCK_HEADER_SIZE = 80;
    public static final int BITCOIN_INV_SIZE = 36; // 4 byte type + 32 byte hash
    public static final int GET_DATA_OVERHEAD = 4;
    public static final int INV_MESSAGE_OVERHEAD = 1;
    public static final int ETHEREUM_HELLO_MESSAGE_SIZE = 16;

    private static final long[] BITCOIN_COMPACT_BLOCK_SIZE_2020_BINS = {
            30, 3624, 7668, 11910, 16644, 21828, 27558, 30672, 31662, 32544, 33420, 34878, 35544, 36198, 36840, 37476,
            38130, 38838, 39630, 40674, 42732, 72714
    };

    private static final long[] BITCOIN_BLOCK_SIZE_2020_BINS = {
            196, 119880, 254789, 396047, 553826, 726752, 917631, 1021479, 1054560, 1084003, 1113136, 1138722, 1161695,
            1183942, 1205734, 1227090, 1248408, 1270070, 1293647, 1320186, 1354939, 1423459, 2422858
    };

    private static final double[] BITCOIN_BLOCK_SIZE_2020 = {
            0.0000, 0.0482, 0.0422, 0.0422, 0.0421, 0.0422, 0.0421, 0.0445, 0.0455, 0.0458, 0.0461, 0.0468, 0.0472,
            0.0481, 0.0477, 0.0479, 0.0484, 0.0482, 0.0475, 0.0464, 0.0454, 0.0434, 0.0420
    };

    public static int sampleBitcoinBlockSize(RandomnessEngine randomnessEngine) {
        return (int) randomnessEngine.sampleDistributionWithBins(BITCOIN_BLOCK_SIZE_2020, BITCOIN_BLOCK_SIZE_2020_BINS);
    }

    public static int sampleBitcoinCompactBlockSize(RandomnessEngine randomnessEngine) {
        return (int) randomnessEngine.sampleDistributionWithBins(BITCOIN_BLOCK_SIZE_2020, BITCOIN_BLOCK_SIZE_2020_BINS);
    }

    public static BitcoinBlockWithoutTx sampleBitcoinBlock(Simulator simulator, RandomnessEngine randomnessEngine,
                                                           Node creator, BitcoinBlockWithoutTx parent,
                                                           double difficulty, double weight) {
        return new BitcoinBlockWithoutTx(sampleBitcoinBlockSize(randomnessEngine), parent.getHeight() + 1,
                simulator.getSimulationTime(), creator, parent, difficulty, weight);
    }

    public static PBFTBlock samplePBFTBlock(Simulator simulator, RandomnessEngine randomnessEngine, PBFTNode creator, PBFTBlock parent) {
        return new PBFTBlock(sampleBitcoinBlockSize(randomnessEngine), parent.getHeight() + 1,
                simulator.getSimulationTime(), creator, parent); // TODO: Size of PBFT Blocks
    }
    
    /**
     * Create a PBFT block WITH transactions for Tt metric support
     * @param simulator The simulation instance
     * @param randomnessEngine Random number generator
     * @param creator The node creating this block
     * @param parent The parent block
     * @param txCount Number of transactions to include (default ~50 if <= 0)
     * @return PBFTBlockWithTx instance
     */
    public static PBFTBlockWithTx samplePBFTBlockWithTx(Simulator simulator, RandomnessEngine randomnessEngine, 
                                                        PBFTNode creator, PBFTBlock parent, int txCount) {
        // Default to ~50 transactions per block if not specified
        if (txCount <= 0) {
            txCount = 20 + randomnessEngine.nextInt(60);
        }
        
        // Generate random transactions for this block
        Set<PBFTTx> txs = new HashSet<>();
        for (int i = 0; i < txCount; i++) {
            txs.add(TransactionFactory.samplePBFTTransaction(randomnessEngine));
        }
        
        return new PBFTBlockWithTx(sampleBitcoinBlockSize(randomnessEngine), parent.getHeight() + 1,
                                   simulator.getSimulationTime(), creator, parent, txs);
    }

    /**
     * Bloco HotStuff SEM transacoes, com o tamanho de um lote cheio: {@code config.getBatchSize() * txSizeBytes}
     * (com o spec oficial, 2048 x 512 B = 1 MiB = tamanho_bloco_kb 1024). Sem cabecalho extra: o spec nao
     * define um e o hash do bloco ja e contado a parte nas mensagens. Nao consome aleatoriedade.
     *
     * @param txSizeBytes tamanho da transacao do spec (tamanho_tx_bytes); com {@code blockSizeKb} dele deriva-se
     *                    o lote por {@link HotStuffConsensusConfig#batchSizeFor(int, int)}
     */
    public static HotStuffBlock sampleHotStuffBlock(Simulator simulator, HotStuffNode creator, HotStuffBlock parent,
                                                    HotStuffConsensusConfig config, int txSizeBytes) {
        requireHotStuffArgs(simulator, parent, config, txSizeBytes);
        return new HotStuffBlock(hotStuffBlockSize(config.getBatchSize(), txSizeBytes), parent.getHeight() + 1,
                simulator.getSimulationTime(), creator, parent);
    }

    /**
     * Bloco HotStuff COM transacoes, lote cheio ({@code config.getBatchSize()} transacoes de {@code txSizeBytes}).
     * Tamanho do bloco = soma dos tamanhos das transacoes. Nao consome aleatoriedade.
     */
    public static HotStuffBlockWithTx sampleHotStuffBlockWithTx(Simulator simulator, HotStuffNode creator,
                                                                HotStuffBlock parent, HotStuffConsensusConfig config,
                                                                int txSizeBytes) {
        requireHotStuffArgs(simulator, parent, config, txSizeBytes);
        return sampleHotStuffBlockWithTx(simulator, creator, parent, config, txSizeBytes, config.getBatchSize());
    }

    /**
     * Bloco HotStuff com {@code txCount} transacoes, 1 &lt;= txCount &lt;= {@code config.getBatchSize()} (lote parcial,
     * p.ex. fila de pendentes menor que o lote). Tamanho = {@code txCount * txSizeBytes}.
     */
    public static HotStuffBlockWithTx sampleHotStuffBlockWithTx(Simulator simulator, HotStuffNode creator,
                                                                HotStuffBlock parent, HotStuffConsensusConfig config,
                                                                int txSizeBytes, int txCount) {
        requireHotStuffArgs(simulator, parent, config, txSizeBytes);
        if (txCount < 1 || txCount > config.getBatchSize()) {
            throw new IllegalArgumentException("txCount deve estar em [1, " + config.getBatchSize() + "]: " + txCount);
        }
        Set<HotStuffTx> txs = new HashSet<>();
        for (int i = 0; i < txCount; i++) {
            txs.add(TransactionFactory.sampleHotStuffTransaction(txSizeBytes));
        }
        return new HotStuffBlockWithTx(hotStuffBlockSize(txCount, txSizeBytes), parent.getHeight() + 1,
                simulator.getSimulationTime(), creator, parent, txs);
    }

    private static int hotStuffBlockSize(int txCount, int txSizeBytes) {
        long size = (long) txCount * txSizeBytes;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tamanho do bloco excede int: " + size);
        }
        return (int) size;
    }

    private static void requireHotStuffArgs(Simulator simulator, HotStuffBlock parent,
                                            HotStuffConsensusConfig config, int txSizeBytes) {
        if (simulator == null || parent == null || config == null) {
            throw new IllegalArgumentException("simulator, parent e config obrigatorios");
        }
        if (txSizeBytes <= 0) {
            throw new IllegalArgumentException("txSizeBytes deve ser > 0: " + txSizeBytes);
        }
    }

    public static SnowBlock sampleSnowBlock(Simulator simulator, RandomnessEngine randomnessEngine, SnowNode creator, SnowBlock parent) {
        return new SnowBlock(sampleBitcoinBlockSize(randomnessEngine), parent.getHeight() + 1,
                simulator.getSimulationTime(), creator, parent); // TODO: Size of Snow Blocks
    }

    public static EthereumBlock sampleEthereumBlock(Simulator simulator, RandomnessEngine randomnessEngine, EthereumMinerNode creator, EthereumBlock parent,
                                                    Set<EthereumBlock> uncles, double weight) {
        return new EthereumBlock(sampleBitcoinBlockSize(randomnessEngine), parent.getHeight() + 1,
                simulator.getSimulationTime(), creator, parent, uncles, ETHEREUM_MIN_DIFFICULTY, weight); // TODO: Block Size
    }
    
    /**
     * Create an Ethereum block WITH transactions for more realistic simulations
     * @param simulator The simulation instance
     * @param randomnessEngine Random number generator
     * @param creator The miner creating this block
     * @param parent The parent block
     * @param uncles Set of uncle blocks
     * @param weight Block weight
     * @param txCount Number of transactions to include (default ~100 if <= 0)
     * @return EthereumBlockWithTx instance
     */
    public static EthereumBlockWithTx sampleEthereumBlockWithTx(Simulator simulator, RandomnessEngine randomnessEngine, 
                                                                EthereumMinerNode creator, EthereumBlock parent,
                                                                Set<EthereumBlock> uncles, double weight, int txCount) {
        // Default to ~100 transactions per block if not specified
        if (txCount <= 0) {
            txCount = 50 + randomnessEngine.nextInt(100);
        }
        
        // Generate random transactions for this block
        Set<EthereumTx> txs = new HashSet<>();
        for (int i = 0; i < txCount; i++) {
            txs.add(TransactionFactory.sampleEthereumTransaction(randomnessEngine));
        }
        
        return new EthereumBlockWithTx(parent.getHeight() + 1, simulator.getSimulationTime(), 
                                       creator, parent, uncles, txs, 
                                       (long)ETHEREUM_MIN_DIFFICULTY, weight);
    }
}
