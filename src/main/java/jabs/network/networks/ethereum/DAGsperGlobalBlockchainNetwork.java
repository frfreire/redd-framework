package jabs.network.networks.ethereum;

import jabs.consensus.config.ChainBasedConsensusConfig;
import jabs.consensus.config.DAGsperConfig;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.networks.Network;
import jabs.network.stats.ProofOfWorkGlobalNetworkStats;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.ethereum.EthereumDAGsperMiner;
import jabs.network.node.nodes.ethereum.EthereumDAGsperNode;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;

public class DAGsperGlobalBlockchainNetwork<R extends Enum<R>> extends EthereumGlobalProofOfWorkNetwork<R> {
    private final int checkpointSpace;

    public DAGsperGlobalBlockchainNetwork(RandomnessEngine randomnessEngine, int checkpointSpace,
                                          ProofOfWorkGlobalNetworkStats<R> networkStats) {
        super(randomnessEngine, networkStats);
        this.checkpointSpace = checkpointSpace;
    }


    /**
     * ADR-0006, tarefa 5 (debito 7): antes, {@code populateNetwork} herdado criava {@code EthereumNode} com
     * {@code GhostProtocol} puro, entao a rede nunca instanciava o {@code DAGsper}. Estes overrides fazem
     * {@code populateNetwork} montar nos DAGsper de verdade. Mesmas amostragens de regiao/banda do pai
     * (miner: sampleMinerRegion; no: sampleRegion), para a ordem de consumo do RandomnessEngine ser a mesma
     * de {@link #createNewEthereumDAGsperNode} / {@link #createNewEthereumDAGsperMiner}.
     */
    @Override
    public EthereumDAGsperNode createSampleNode(Simulator simulator, int nodeID, EthereumBlock genesisBlock,
                                                ChainBasedConsensusConfig chainBasedConsensusConfig) {
        return createNewEthereumDAGsperNode(simulator, nodeID, genesisBlock, chainBasedConsensusConfig);
    }

    @Override
    public EthereumDAGsperMiner createSampleMiner(Simulator simulator, int nodeID, double hashPower,
                                                  EthereumBlock genesisBlock,
                                                  ChainBasedConsensusConfig chainBasedConsensusConfig) {
        R region = sampleMinerRegion();
        return new EthereumDAGsperMiner(simulator, this, nodeID, this.sampleDownloadBandwidth(region),
                this.sampleUploadBandwidth(region), hashPower, genesisBlock,
                (DAGsperConfig) chainBasedConsensusConfig);
    }

    public EthereumDAGsperNode createNewEthereumDAGsperNode(Simulator simulator, int nodeID, EthereumBlock genesisBlock,
                                                            ChainBasedConsensusConfig chainBasedConsensusConfig) {
        R region = sampleRegion();
        return new EthereumDAGsperNode(simulator, this, nodeID, this.sampleDownloadBandwidth(region),
                this.sampleUploadBandwidth(region), genesisBlock, (DAGsperConfig) chainBasedConsensusConfig);
    }

    public EthereumDAGsperMiner createNewEthereumDAGsperMiner(Simulator simulator, int nodeID,
                                                              EthereumBlock genesisBlock,
                                                              ChainBasedConsensusConfig chainBasedConsensusConfig) {
        R region = sampleMinerRegion();
        return new EthereumDAGsperMiner(simulator, this, nodeID, this.sampleDownloadBandwidth(region),
                this.sampleUploadBandwidth(region), sampleHashPower(), genesisBlock, (DAGsperConfig) chainBasedConsensusConfig);
    }
}
