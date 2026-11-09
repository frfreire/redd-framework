package jabs.network.node.nodes.tendermint;

import jabs.consensus.algorithm.Tendermint;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.Query;
import jabs.ledgerdata.Vote;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.ledgerdata.tendermint.TendermintTx;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.p2p.TendermintP2P;
import jabs.simulator.Simulator;

/**
 * No Tendermint. O genesis e uma constante imutavel (altura 0, tamanho 0, sem criador), igual ao PBFT/HotStuff.
 * Propostas e votos sao despachados por {@code PeerBlockchainNode} direto ao {@link Tendermint}; nao ha
 * DataMessage de bloco no protocolo (o bloco viaja dentro da proposta).
 */
public class TendermintNode extends PeerBlockchainNode<TendermintBlock, TendermintTx> {
    public static final TendermintBlock TENDERMINT_GENESIS_BLOCK = new TendermintBlock(0, 0, 0, null, null);

    private final TendermintConsensusConfig config;
    private final int numAllParticipants;

    public TendermintNode(Simulator simulator, Network network, int nodeID, long downloadBandwidth,
                          long uploadBandwidth, int numAllParticipants, TendermintConsensusConfig config) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth,
                new TendermintP2P(),
                new Tendermint<>(new LocalBlockTree<>(TENDERMINT_GENESIS_BLOCK), config, nodeID, numAllParticipants)
        );
        this.config = config;
        this.numAllParticipants = numAllParticipants;
        this.consensusAlgorithm.setNode(this);
    }

    public TendermintConsensusConfig getConfig() {
        return config;
    }

    public int getNumAllParticipants() {
        return numAllParticipants;
    }

    /** Inicia o Algoritmo 1 neste no (altura 0, round 0). Chamado uma vez pela rede. */
    public void startConsensus() {
        ((Tendermint<TendermintBlock, TendermintTx>) this.consensusAlgorithm).start();
    }

    @Override
    protected void processNewTx(TendermintTx tx, Node from) {
        // Nada: o lote vem da amostragem do bloco (BlockFactory), como no PBFT/HotStuff.
    }

    @Override
    protected void processNewBlock(TendermintBlock block) {
        // Nada: o bloco Tendermint nao trafega em DataMessage; entra na arvore pela proposta.
    }

    @Override
    protected void processNewVote(Vote vote) {
        // Nada: votos Tendermint usam TendermintVoteMessage (nao VoteMessage) e sao despachados direto ao algoritmo.
    }

    @Override
    protected void processNewQuery(Query query) {
        // sem consulta no Tendermint
    }

    @Override
    public void generateNewTransaction() {
        // nada, como no PBFT/HotStuff
    }
}
