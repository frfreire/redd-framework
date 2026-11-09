package jabs.consensus.algorithm;

import jabs.consensus.algorithm.tendermint.TendermintAction;
import jabs.consensus.algorithm.tendermint.TendermintCore;
import jabs.consensus.algorithm.tendermint.TendermintProposal;
import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.consensus.algorithm.tendermint.TimeoutKind;
import jabs.consensus.blockchain.LocalBlock;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.SingleParentBlock;
import jabs.ledgerdata.Tx;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.network.message.TendermintProposalMessage;
import jabs.network.message.TendermintVoteMessage;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.TendermintTimeoutEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Adaptador JABS do Tendermint sobre o nucleo puro {@link TendermintCore} (Algoritmo 1 de Buchman, Kwon e
 * Milosevic, arXiv:1807.04938).
 *
 * <p><b>Caminho honesto.</b> O adaptador traduz mensagens de rede para as entradas do nucleo, executa as acoes
 * devolvidas (difundir proposta/voto, agendar timeout, decidir) e mede trafego. Nao ha autoentrega: o
 * {@link jabs.network.p2p.TendermintP2P} nao liga o no a si mesmo e o nucleo ja registra a propria mensagem no
 * log; por isso o adaptador so difunde aos outros e reentrega a este no e erro.
 *
 * <p><b>Admissao de blocos.</b> O bloco viaja dentro da proposta (nao ha DataMessage). Ao receber uma proposta, o
 * adaptador o admite na {@link LocalBlockTree} antes de chamar o nucleo, para que {@code valid(v)} do artigo
 * (aqui: bloco presente e conectado ao genesis) tenha a mesma resposta em todos os nos corretos. Bloco proposto
 * pelo proprio no tambem e admitido no {@code getValue}.
 *
 * <p><b>Altura seguinte.</b> O nucleo decide e ja inicia a proxima altura dentro de {@code decide()}, antes de o
 * adaptador executar a acao {@code DECIDE}. Para o bloco novo estender o valor recem-decidido, {@code getValue}
 * usa {@code core.decision(height - 1)} como pai, e nao {@code currentMainChainHead} (que so e atualizado quando a
 * acao {@code DECIDE} e executada).
 *
 * <p><b>Trafego (ADR-0006).</b> MEDIDO por no que recebe, por bloco, a partir de {@code packet.getSize()} no
 * hook de {@code PeerBlockchainNode}. C_B_total inclui a carga do bloco na proposta; C_B_consenso inclui so o
 * cabecalho da proposta e os votos. Voto nil nao nomeia bloco: vai para um contador a parte
 * ({@link #getUnattributedTraffic()}), nunca para um bloco por palpite.
 *
 * <p><b>Byzantinos.</b> Este adaptador implementa apenas o caminho honesto. {@code supportedAttacks()} continua
 * vazio, entao configurar um ataque falha alto em vez de tratar o no bizantino como honesto em silencio.
 */
public class Tendermint<B extends SingleParentBlock<B>, T extends Tx<T>> extends AbstractChainBasedConsensus<B, T> {
    private final TendermintConsensusConfig config;
    private final int numAllParticipants;
    private final TendermintCore<TendermintBlock> core;

    private final HashMap<B, Long> blockTraffic = new HashMap<>();
    private final HashMap<B, Long> consensusTraffic = new HashMap<>();
    private final HashSet<B> locallyFinalized = new HashSet<>();
    private long unattributedTraffic = 0L;

    /** Contadores de um bloco devolvidos (e zerados) por {@link #takeTraffic}. */
    public record BlockTraffic(long total, long consensus) { }

    /**
     * @param localBlockTree arvore local (genesis deve ser um {@link TendermintBlock})
     * @param config configuracao (obrigatoria)
     * @param self id deste validador em [0, n)
     * @param numAllParticipants n (&gt;= 4)
     */
    public Tendermint(LocalBlockTree<B> localBlockTree, TendermintConsensusConfig config, int self,
                      int numAllParticipants) {
        super(localBlockTree);
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        config.validateFor(numAllParticipants);
        if (self < 0 || self >= numAllParticipants) {
            throw new IllegalArgumentException("self fora de [0, " + numAllParticipants + "): " + self);
        }
        this.config = config;
        this.numAllParticipants = numAllParticipants;
        this.currentMainChainHead = localBlockTree.getGenesisBlock();
        this.core = new TendermintCore<>(self, numAllParticipants,
                TendermintCore.roundRobin(numAllParticipants), this::proposeValue, this::isValidValue);
    }

    // ------------------------------------------------------------------------------------------------------
    // Entradas do adaptador
    // ------------------------------------------------------------------------------------------------------

    /** Linha 5: inicia a altura 0. Deve ser chamado uma unica vez por no. */
    public void start() {
        execute(core.start());
    }

    /** Proposta recebida PELA REDE SIMULADA; o bloco e admitido na arvore antes de o nucleo avalia-lo. */
    public void onProposal(TendermintProposalMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        admit(castBlock(message.getBlock()));
        execute(core.onProposal(message.toProposal()));
    }

    /** Voto (PREVOTE/PRECOMMIT ou nil) recebido PELA REDE SIMULADA. */
    public void onVote(TendermintVoteMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        execute(core.onVote(message.toVote()));
    }

    /** Temporizador entregue pelo {@link TendermintTimeoutEvent}. Timeout obsoleto e filtrado pelo nucleo. */
    public void onTimeout(TimeoutKind kind, long height, int round) {
        execute(core.onTimeout(kind, height, round));
    }

    // ------------------------------------------------------------------------------------------------------
    // Callbacks do nucleo
    // ------------------------------------------------------------------------------------------------------

    private TendermintBlock proposeValue(long height) {
        TendermintBlock parent;
        if (height == 0) {
            parent = (TendermintBlock) localBlockTree.getGenesisBlock();
        } else {
            parent = core.decision(height - 1);
            if (parent == null) {
                throw new IllegalStateException("proposta na altura " + height
                        + " sem decisao local na altura " + (height - 1));
            }
        }
        TendermintNode node = requireTendermintNode();
        TendermintBlock block = BlockFactory.sampleTendermintBlockWithTx(
                node.getSimulator(), node, parent, config);
        admit(castBlock(block));
        return block;
    }

    private boolean isValidValue(TendermintBlock block) {
        if (block == null) {
            return false;
        }
        B local = castBlock(block);
        if (!localBlockTree.contains(local)) {
            return false;
        }
        LocalBlock<B> localBlock = localBlockTree.getLocalBlock(local);
        return localBlock != null && localBlock.isConnectedToGenesis;
    }

    // ------------------------------------------------------------------------------------------------------
    // Execucao das acoes
    // ------------------------------------------------------------------------------------------------------

    private void execute(List<TendermintAction<TendermintBlock>> actions) {
        for (TendermintAction<TendermintBlock> action : actions) {
            switch (action.kind()) {
                case BROADCAST_PROPOSAL:
                    TendermintProposal<TendermintBlock> proposal = action.proposal();
                    requireNode().broadcastMessage(new TendermintProposalMessage(requireNode(),
                            proposal.height(), proposal.round(), proposal.value(), proposal.validRound(), config));
                    break;
                case BROADCAST_VOTE:
                    TendermintVote<TendermintBlock> vote = action.vote();
                    requireNode().broadcastMessage(new TendermintVoteMessage(requireNode(), vote.type(),
                            vote.height(), vote.round(), vote.value(), config));
                    break;
                case SCHEDULE_TIMEOUT:
                    double delay = config.getTimeouts().durationSeconds(action.timeout(), action.round());
                    requireNode().getSimulator().putEvent(new TendermintTimeoutEvent(this, action.timeout(),
                            action.height(), action.round()), delay);
                    break;
                case DECIDE:
                    finalizeLocally(action.value());
                    break;
                default:
                    throw new IllegalStateException("acao desconhecida: " + action.kind());
            }
        }
    }

    private void finalizeLocally(TendermintBlock value) {
        B block = castBlock(value);
        markLocallyFinalized(block);
        BlockTraffic traffic = takeTraffic(block);
        currentMainChainHead = block;
        updateChain();
        requireNode().getSimulator().putEvent(
                new BlockFinalizationEvent(requireNode().getSimulator().getSimulationTime(), requireNode(), block,
                        traffic.total(), traffic.consensus()),
                0);
    }

    // ------------------------------------------------------------------------------------------------------
    // Trafego (convencao ADR-0006: recebido por no, medido, com corte na finalizacao local)
    // ------------------------------------------------------------------------------------------------------

    public void addBlockTraffic(B block, long bytes) {
        requireTraffic(block, bytes);
        if (locallyFinalized.contains(block)) {
            return;
        }
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    public void addConsensusMessageTraffic(B block, long bytes) {
        requireTraffic(block, bytes);
        if (locallyFinalized.contains(block)) {
            return;
        }
        consensusTraffic.put(block, consensusTraffic.getOrDefault(block, 0L) + bytes);
    }

    public long getBlockTraffic(B block) {
        return blockTraffic.getOrDefault(block, 0L);
    }

    public long getConsensusMessageTraffic(B block) {
        return consensusTraffic.getOrDefault(block, 0L);
    }

    /**
     * Proposta recebida pela rede simulada: C_B_total += pacote inteiro; C_B_consenso += pacote menos a carga do
     * bloco (que viaja dentro da proposta).
     */
    public void recordProposalTraffic(TendermintProposalMessage message, long packetBytes) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        long blockPayload = (long) message.getTotalSize() - message.getConsensusSize();
        long consensusBytes = packetBytes - blockPayload;
        if (consensusBytes < 0) {
            throw new IllegalStateException("pacote menor que a carga do bloco: " + packetBytes + " < " + blockPayload);
        }
        B block = castBlock(message.getBlock());
        addBlockTraffic(block, packetBytes);
        addConsensusMessageTraffic(block, consensusBytes);
    }

    /** Voto recebido pela rede simulada; nil nao nomeia bloco e vai para o contador a parte. */
    public void recordVoteTraffic(TendermintVoteMessage message, long packetBytes) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        if (message.isNil()) {
            if (packetBytes < 0) {
                throw new IllegalArgumentException("bytes deve ser >= 0: " + packetBytes);
            }
            unattributedTraffic += packetBytes;
            return;
        }
        B block = castBlock(message.getBlock());
        addBlockTraffic(block, packetBytes);
        addConsensusMessageTraffic(block, packetBytes);
    }

    public long getUnattributedTraffic() {
        return unattributedTraffic;
    }

    public long takeUnattributedTraffic() {
        long value = unattributedTraffic;
        unattributedTraffic = 0L;
        return value;
    }

    public void markLocallyFinalized(B block) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        locallyFinalized.add(block);
    }

    public boolean isLocallyFinalized(B block) {
        return locallyFinalized.contains(block);
    }

    public BlockTraffic takeTraffic(B block) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        long total = blockTraffic.getOrDefault(block, 0L);
        long consensus = consensusTraffic.getOrDefault(block, 0L);
        blockTraffic.remove(block);
        consensusTraffic.remove(block);
        return new BlockTraffic(total, consensus);
    }

    private static void requireTraffic(Object block, long bytes) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes deve ser >= 0: " + bytes);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // AbstractChainBasedConsensus
    // ------------------------------------------------------------------------------------------------------

    @Override
    public void newIncomingBlock(B block) {
        // Blocos Tendermint entram na arvore pela proposta (onProposal), nao por DataMessage.
    }

    @Override
    protected void updateChain() {
        this.confirmedBlocks.add(this.currentMainChainHead);
        recordBlockAcceptance(this.currentMainChainHead);
        int threshold = Math.max(0, this.currentMainChainHead.getHeight() - 1000);
        this.localBlockTree.clearObsoleteData(threshold);
    }

    @Override
    protected int getBlockProposer(B block) {
        if (block != null && block.getCreator() != null) {
            return block.getCreator().nodeID;
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------------------------------------------

    public TendermintConsensusConfig getConfig() { return config; }
    public int getNumAllParticipants() { return numAllParticipants; }
    public int getRequiredVotes() { return core.quorum().strong(); }
    public long getHeight() { return core.height(); }
    public int getRound() { return core.round(); }
    public TendermintBlock getDecision(long height) { return core.decision(height); }
    public boolean isStarted() { return core.isStarted(); }
    public TendermintCore<TendermintBlock> getCore() { return core; }

    // ------------------------------------------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------------------------------------------

    private void admit(B block) {
        if (!localBlockTree.contains(block)) {
            localBlockTree.add(block);
        }
    }

    @SuppressWarnings("unchecked")
    private B castBlock(TendermintBlock block) {
        return (B) block;
    }

    private PeerBlockchainNode<B, T> requireNode() {
        if (peerBlockchainNode == null) {
            throw new IllegalStateException("Tendermint sem no: chame setNode antes de iniciar a simulacao");
        }
        return peerBlockchainNode;
    }

    private TendermintNode requireTendermintNode() {
        PeerBlockchainNode<B, T> node = requireNode();
        if (!(node instanceof TendermintNode)) {
            throw new IllegalStateException("Tendermint so roda em TendermintNode, recebido: "
                    + node.getClass().getName());
        }
        return (TendermintNode) node;
    }
}
