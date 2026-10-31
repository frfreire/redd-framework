package jabs.network.node.nodes.hotstuff;

import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.Query;
import jabs.ledgerdata.Vote;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.p2p.HotStuffP2P;
import jabs.simulator.Simulator;

/**
 * No HotStuff (molde: PBFTNode). D3a-2a-2c-2a: lado da REPLICA do caminho honesto. Propostas e mensagens de QC sao
 * despachadas por {@code PeerBlockchainNode} direto a {@link HotStuff#onProposal}/{@link HotStuff#onQcMessage};
 * {@link #processNewVote} entrega o voto recebido (este no como lider) ao agregador do {@link HotStuff}, SO
 * armazenando (o lider age na 2c-2b). Nao ha DataMessage de bloco no protocolo: o bloco viaja dentro da proposta
 * PREPARE e entra na arvore local quando a proposta e aceita (ver {@link HotStuff#onProposal}).
 *
 * <p>O genesis nao e compartilhado entre nos de redes diferentes por acidente: e uma constante imutavel
 * (altura 0, tamanho 0, sem criador), igual ao PBFT.
 */
public class HotStuffNode extends PeerBlockchainNode<HotStuffBlock, HotStuffTx> {
    public static final HotStuffBlock HOTSTUFF_GENESIS_BLOCK = new HotStuffBlock(0, 0, 0, null, null);

    private final HotStuffConsensusConfig config;
    private final int numAllParticipants;

    /**
     * @param numAllParticipants n da rede (&gt;= 4, validado por {@link HotStuff})
     * @param config configuracao do protocolo (obrigatoria)
     */
    public HotStuffNode(Simulator simulator, Network network, int nodeID, long downloadBandwidth,
                        long uploadBandwidth, int numAllParticipants, HotStuffConsensusConfig config) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth,
                new HotStuffP2P(),
                new HotStuff<>(new LocalBlockTree<>(HOTSTUFF_GENESIS_BLOCK), config, numAllParticipants)
        );
        this.config = config;
        this.numAllParticipants = numAllParticipants;
        this.consensusAlgorithm.setNode(this);
    }

    public HotStuffConsensusConfig getConfig() {
        return config;
    }

    public int getNumAllParticipants() {
        return numAllParticipants;
    }

    @Override
    protected void processNewTx(HotStuffTx tx, Node from) {
        // Nada, de proposito: o lote de transacoes vem da amostragem do bloco (BlockFactory), como no PBFT.
    }

    @Override
    protected void processNewBlock(HotStuffBlock block) {
        // Nada, de proposito: o bloco ja foi posto na LocalBlockTree por PeerBlockchainNode (DataMessage avulso, que
        // o protocolo nao usa) e SO vira bloco do consenso quando uma proposta PREPARE sobre ele for aceita.
        // Tratar aqui duplicaria a admissao e o tráfego do bloco.
    }

    @Override
    protected void processNewVote(Vote vote) {
        if (!(vote instanceof HotStuffVote)) {
            throw new IllegalArgumentException("HotStuffNode so recebe HotStuffVote, recebido: "
                    + (vote == null ? "null" : vote.getClass().getName()));
        }
        // So armazena no agregador do lider (2c-2b forma o QC). Tráfego do voto ja foi contado em PeerBlockchainNode.
        ((HotStuff<HotStuffBlock, HotStuffTx>) this.consensusAlgorithm).onVote((HotStuffVote) vote);
    }

    @Override
    protected void processNewQuery(Query query) {
        // sem consulta no HotStuff
    }

    @Override
    public void generateNewTransaction() {
        // nada, como no PBFT
    }
}
