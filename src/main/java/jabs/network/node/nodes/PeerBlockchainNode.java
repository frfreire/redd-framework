package jabs.network.node.nodes;

import jabs.consensus.algorithm.AbstractChainBasedConsensus;
import jabs.consensus.algorithm.DAGsper;
import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.algorithm.Parlia;
import jabs.consensus.algorithm.CasperFFG;
import jabs.consensus.algorithm.NakamotoConsensus;
import jabs.consensus.algorithm.PBFT;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.ledgerdata.*;
import jabs.ledgerdata.dagsper.DAGsperVote;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.ledgerdata.pbft.PBFTBlockVote;
import jabs.network.message.*;
import jabs.network.networks.Network;
import jabs.network.p2p.AbstractP2PConnections;
import jabs.simulator.Simulator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.SortedSet;
import java.util.TreeSet;

public abstract class PeerBlockchainNode<B extends SingleParentBlock<B>, T extends Tx<T>> extends PeerDLTNode<B, T> {
    protected final AbstractChainBasedConsensus<B, T> consensusAlgorithm;

    protected final HashMap<Hash, T> alreadySeenTxs = new HashMap<>();
    protected final HashMap<Hash, B> alreadySeenBlocks = new HashMap<>();
    protected final HashSet<Vote> alreadySeenVotes = new HashSet<>();
    protected final HashSet<Query> alreadySeenQueries = new HashSet<>();
    protected final LocalBlockTree<B> localBlockTree;

    public PeerBlockchainNode(Simulator simulator, Network network, int nodeID, long downloadBandwidth,
                              long uploadBandwidth, AbstractP2PConnections routingTable,
                              AbstractChainBasedConsensus<B, T> consensusAlgorithm) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth, routingTable, consensusAlgorithm);
        this.consensusAlgorithm = consensusAlgorithm;
        this.localBlockTree = consensusAlgorithm.getLocalBlockTree();
    }

    @Override
    public void processIncomingPacket(Packet packet) {
        Message message = packet.getMessage();
        if (message instanceof DataMessage) {
            // System.out.println("[PeerBlockchainNode] Received DataMessage: " + message);
            Data data = ((DataMessage) message).getData();
            if (data instanceof Block) {
                B block = (B) data;
                // Track traffic for this block (Parlia)
                if (this.consensusAlgorithm instanceof Parlia) {
                    ((Parlia<B, T>) this.consensusAlgorithm).addBlockTraffic(block, packet.getSize());
                }
                // Track traffic for this block (CasperFFG)
                if (this.consensusAlgorithm instanceof CasperFFG) {
                    ((CasperFFG<B, T>) this.consensusAlgorithm).addBlockTraffic(block, packet.getSize());
                }
                    // Track traffic for this block (Nakamoto / Bitcoin)
                    if (this.consensusAlgorithm instanceof NakamotoConsensus) {
                        ((NakamotoConsensus<B, T>) this.consensusAlgorithm).addBlockTraffic(block, packet.getSize());
                    }
                // Track traffic for this block (DAGsper, ADR-0006 tarefa 4): todo pacote que chegou a este no PELA
                // REDE SIMULADA conta (packet.getSize()), antes do filtro de duplicata. O bloco que o minerador
                // entrega a si mesmo (EthereumMinerNode#generateNewBlock, Packet(self, self) direto) nao e trafego.
                if (this.consensusAlgorithm instanceof DAGsper && !isLocalSelfDelivery(packet)) {
                    ((DAGsper<B, T>) this.consensusAlgorithm).addBlockTraffic(block, packet.getSize());
                }
                // Track traffic for this block (HotStuff, D3a-2a-1): bloco proposto que chegou PELA REDE SIMULADA.
                // So C_B_total (a carga do bloco nao e consenso). Autoentrega local nao conta; no HotStuffP2P o no
                // nao e vizinho de si, entao from == this so ocorre em entrega local direta.
                if (this.consensusAlgorithm instanceof HotStuff && !isLocalSelfDelivery(packet)) {
                    ((HotStuff<B, T>) this.consensusAlgorithm).addBlockTraffic(block, packet.getSize());
                }
                if (!localBlockTree.contains(block)){
                    // Prevent memory leak by clearing seen blocks when too many
                    if (alreadySeenBlocks.size() > 50000) {
                        alreadySeenBlocks.clear();
                    }
                    localBlockTree.add(block);
                    alreadySeenBlocks.put(block.getHash(), block);
                    if (localBlockTree.getLocalBlock(block).isConnectedToGenesis) {
                        this.processNewBlock(block);
                        SortedSet<B> newBlocks = new TreeSet<>(localBlockTree.getAllSuccessors(block));
                        for (B newBlock:newBlocks){
                            this.processNewBlock(newBlock);
                        }
                    } else {
                        this.networkInterface.addToUpLinkQueue(
                                new Packet(this, packet.getFrom(),
                                        new RequestDataMessage(block.getParent().getHash())
                                )
                        );
                    }
                }
            } else if (data instanceof Tx) {
                T tx = (T) data;
                // Prevent memory leak by clearing seen txs when too many
                if (alreadySeenTxs.size() > 50000) {
                    alreadySeenTxs.clear();
                }
                if (!alreadySeenTxs.containsValue(tx)){
                    alreadySeenTxs.put(tx.getHash(), tx);
                    this.processNewTx(tx, packet.getFrom());
                }
            }
        } else if (message instanceof InvMessage) {
            // System.out.println("[PeerBlockchainNode] Received InvMessage: " + message);
            Hash hash = ((InvMessage) message).getHash();
            if (hash.getData() instanceof Block){
                // Prevent memory leak by clearing seen txs when too many
                if (alreadySeenTxs.size() > 50000) {
                    alreadySeenTxs.clear();
                }
                if (!alreadySeenTxs.containsKey(hash)) {
                    alreadySeenTxs.put(hash, null);
                    this.networkInterface.addToUpLinkQueue(
                            new Packet(this, packet.getFrom(),
                                    new RequestDataMessage(hash)
                            )
                    );
                }
            } else if (hash.getData() instanceof Tx) {
                if (!alreadySeenBlocks.containsKey(hash)) {
                    alreadySeenBlocks.put(hash, null);
                    this.networkInterface.addToUpLinkQueue(
                            new Packet(this, packet.getFrom(),
                                    new RequestDataMessage(hash)
                            )
                    );
                }
            }
        } else if (message instanceof RequestDataMessage) {
            // System.out.println("[PeerBlockchainNode] Received RequestDataMessage: " + message);
            Hash hash = ((RequestDataMessage) message).getHash();
            if (hash.getData() instanceof Block) {
                if (alreadySeenBlocks.containsKey(hash)) {
                    B block = alreadySeenBlocks.get(hash);
                    if (block != null) {
                        this.networkInterface.addToUpLinkQueue(
                                new Packet(this, packet.getFrom(),
                                        new DataMessage(block)
                                )
                        );
                    }
                }
            } else if (hash.getData() instanceof Tx) {
                if (alreadySeenTxs.containsKey(hash)) {
                    T tx = alreadySeenTxs.get(hash);
                    if (tx != null) {
                        this.networkInterface.addToUpLinkQueue(
                                new Packet(this, packet.getFrom(),
                                        new DataMessage(tx)
                                )
                        );
                    }
                }
            }
        } else if (message instanceof VoteMessage) {
            // System.out.println("[PeerBlockchainNode] Received VoteMessage: " + message);
            Vote vote = ((VoteMessage) message).getVote();
            // PBFT (ADR-0006): trafego MEDIDO, "recebido por no". Conta todo pacote de voto entregue a este
            // no (antes do filtro alreadySeenVotes: duplicata tambem consumiu banda), sem formula em n.
            if (this.consensusAlgorithm instanceof PBFT && vote instanceof PBFTBlockVote) {
                PBFT<B, T> pbft = (PBFT<B, T>) this.consensusAlgorithm;
                PBFTBlockVote<B> blockVote = (PBFTBlockVote<B>) vote;
                B votedBlock = blockVote.getBlock();
                // C_B_total: pacote inteiro (PRE_PREPARE carrega o bloco).
                pbft.addBlockTraffic(votedBlock, packet.getSize());
                // C_B_consenso: PRE_PREPARE so conta o overhead fixo do anuncio; PREPARE/COMMIT carregam
                // apenas o hash do bloco e contam inteiros.
                long consensusOnlyBytes = (blockVote.getVoteType() == PBFTBlockVote.VoteType.PRE_PREPARE)
                        ? PBFTBlockVote.PBFT_VOTE_SIZE_OVERHEAD
                        : packet.getSize();
                pbft.addConsensusMessageTraffic(votedBlock, consensusOnlyBytes);
            }
            // DAGsper (ADR-0006 tarefa 4): conta antes do filtro alreadySeenVotes (duplicata tambem consumiu
            // banda), mas so pacote que chegou pela rede simulada. O voto que o no entrega a si mesmo em
            // DAGsper#updateChain (Packet(self, self) direto em processIncomingPacket, sem addToUpLinkQueue) NAO
            // e trafego: nao soma bytes, mas segue para processNewVote normalmente.
            // Diferenca para o PBFT: la o proprio voto conta porque o broadcast inclui o remetente entre os
            // vizinhos (PBFTP2P) e o pacote percorre envio, latencia e recepcao. No DAGsper (EthereumGethP2P)
            // o no nunca e vizinho de si, logo from == this so ocorre em entrega local.
            if (this.consensusAlgorithm instanceof DAGsper && vote instanceof DAGsperVote
                    && !isLocalSelfDelivery(packet)) {
                ((DAGsper<B, T>) this.consensusAlgorithm).addVoteTraffic(vote, packet.getSize());
            }
            // HotStuff (D3a-2a-1): voto que chegou PELA REDE SIMULADA, antes do filtro alreadySeenVotes (duplicata
            // tambem consumiu banda). Granularidade: POR NO QUE RECEBE, por bloco nomeado pelo voto. Um voto e
            // 100% protocolo (hash + overhead + assinatura): soma inteiro em C_B_total e em C_B_consenso (a
            // carga do bloco nao vai no voto). Autoentrega local (o voto do proprio no a si mesmo, por chamada
            // direta a processIncomingPacket) NAO e trafego: nao soma bytes, mas segue para processNewVote.
            // Proposta e NEW_VIEW/DECIDE nao sao VoteMessage: tem ramos proprios abaixo (D3a-2a-2b).
            if (this.consensusAlgorithm instanceof HotStuff && vote instanceof HotStuffVote
                    && !isLocalSelfDelivery(packet)) {
                HotStuff<B, T> hotStuff = (HotStuff<B, T>) this.consensusAlgorithm;
                B votedBlock = (B) ((HotStuffVote) vote).getBlock();
                hotStuff.addBlockTraffic(votedBlock, packet.getSize());
                hotStuff.addConsensusMessageTraffic(votedBlock, packet.getSize());
            }
            // Prevent memory leak by clearing seen votes when too many
            if (alreadySeenVotes.size() > 50000) {
                alreadySeenVotes.clear();
            }
            if (!alreadySeenVotes.contains(vote)) {
                alreadySeenVotes.add(vote);
                this.processNewVote(vote);
            }
        } else if (message instanceof HotStuffProposalMessage) {
            // HotStuff (D3a-2a-2b): proposta do lider que chegou PELA REDE SIMULADA. Mesma convencao do voto
            // (por no que recebe, duplicata conta, autoentrega local nao conta). Bloco = o da proposta (referencia
            // final capturada na construcao, sem lista de pais); C_B_total = packet.getSize(); C_B_consenso =
            // pacote menos a carga do bloco (so PREPARE a leva). Conta ANTES do despacho ao algoritmo.
            HotStuffProposalMessage proposal = (HotStuffProposalMessage) message;
            HotStuff<B, T> hotStuff = requireHotStuff(message);
            if (!isLocalSelfDelivery(packet)) {
                hotStuff.recordProposalTraffic(proposal, packet.getSize());
            }
            hotStuff.onProposal(proposal); // D3a-2a-2c; hoje UnsupportedOperationException (nunca descarta)
        } else if (message instanceof HotStuffQcNetworkMessage) {
            // HotStuff (D3a-2a-2b): NEW_VIEW/DECIDE. Bloco = o do QC carregado; NEW_VIEW sem QC vai ao contador
            // a parte do consenso (ver HotStuff#recordQcMessageTraffic). Mesma convencao da proposta.
            HotStuffQcNetworkMessage qcMessage = (HotStuffQcNetworkMessage) message;
            HotStuff<B, T> hotStuff = requireHotStuff(message);
            if (!isLocalSelfDelivery(packet)) {
                hotStuff.recordQcMessageTraffic(qcMessage, packet.getSize());
            }
            hotStuff.onQcMessage(qcMessage); // D3a-2a-2c; hoje UnsupportedOperationException (nunca descarta)
        } else if (message instanceof QueryMessage) {
            // System.out.println("[PeerBlockchainNode] Received QueryMessage: " + message);
            Query query = ((QueryMessage) message).getQuery();
            // Prevent memory leak by clearing seen queries when too many
            if (alreadySeenQueries.size() > 50000) {
                alreadySeenQueries.clear();
            }
            if (!alreadySeenQueries.contains(query)) {
                alreadySeenQueries.add(query);
                this.processNewQuery(query);
            }
        }
    }

    /**
     * Convencao de trafego recebido do DAGsper (ADR-0006, tarefa 4): so conta pacote que percorreu a rede
     * simulada. Entrega local direta (Packet com from == este no, passada por chamada a processIncomingPacket
     * sem passar pela fila de envio) nao e trafego. Usado SOMENTE pelo hook do DAGsper; PBFT/Parlia/Casper/
     * Nakamoto mantem a contagem anterior (no PBFT o broadcast de rede inclui o remetente, entao
     * from == this ali e trafego de rede e nao pode ser filtrado).
     */
    private boolean isLocalSelfDelivery(Packet packet) {
        return packet.getFrom() == this;
    }

    /** Mensagem de rede do HotStuff em no que nao roda HotStuff e erro de montagem: falha alto, nao ignora. */
    @SuppressWarnings("unchecked")
    private HotStuff<B, T> requireHotStuff(Message message) {
        if (!(this.consensusAlgorithm instanceof HotStuff)) {
            throw new IllegalStateException(message.getClass().getSimpleName()
                    + " entregue a um no cujo consenso nao e HotStuff: " + this.consensusAlgorithm.getClass().getSimpleName());
        }
        return (HotStuff<B, T>) this.consensusAlgorithm;
    }

    protected abstract void processNewBlock(B block);
    protected abstract void processNewVote(Vote vote);
    protected abstract void processNewQuery(Query query);

    public AbstractChainBasedConsensus<B, T> getConsensusAlgorithm() {
        return this.consensusAlgorithm;
    }

    public int numberOfAlreadySeenBlocks() {
        return alreadySeenBlocks.size();
    }
}
