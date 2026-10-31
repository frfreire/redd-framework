package jabs.network.node.nodes.ethereum;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.algorithm.DAGsper;
import jabs.consensus.config.DAGsperConfig;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.networks.Network;
import jabs.simulator.Simulator;

import java.util.LinkedHashSet;

public class EthereumDAGsperMiner extends EthereumMinerNode {
    public EthereumDAGsperMiner(Simulator simulator, Network network, int nodeID, long downloadBandwidth,
                                long uploadBandwidth, double hashPower, EthereumBlock genesisBlock,
                                DAGsperConfig daGsperConfig) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth, hashPower,
                new DAGsper<>(new LocalBlockTree<>(genesisBlock), daGsperConfig));
        // ADR-0006, tarefa 5 (determinismo): EthereumMinerNode#generateNewBlock ITERA o memPool para escolher as
        // transacoes do bloco (e para na primeira que estoura o limite de gas). Com o HashSet da classe-base a
        // ordem depende do hash de identidade de EthereumTx (sem hashCode proprio): o tamanho do bloco, e com ele o
        // trafego medido, mudava com -XX:hashCode (24 vs 12 blocos em n=10, semente 1, com transacoes). Ordem de
        // insercao = ordem de chegada dos pacotes, que e deterministica. Trocado so aqui: Casper/Nakamoto herdam a
        // mesma classe-base e nao podem ter a saida alterada nesta tarefa.
        this.memPool = new LinkedHashSet<>();
    }
}
