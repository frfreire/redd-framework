package jabs.consensus.algorithm;

import jabs.consensus.algorithm.hotstuff.DiscardCounters;
import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.consensus.algorithm.hotstuff.LeaderMessage;
import jabs.consensus.algorithm.hotstuff.NewViewAggregator;
import jabs.consensus.algorithm.hotstuff.ReplicaAction;
import jabs.consensus.algorithm.hotstuff.ReplicaCore;
import jabs.consensus.algorithm.hotstuff.ReplicaState;
import jabs.consensus.algorithm.hotstuff.TreeBlockAccess;
import jabs.consensus.algorithm.hotstuff.ViewLeaders;
import jabs.consensus.algorithm.hotstuff.VoteAggregator;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.SingleParentBlock;
import jabs.ledgerdata.Tx;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffQcNetworkMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.simulator.event.BlockFinalizationEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * ESQUELETO D3a-2a-1 do HotStuff: sem logica de protocolo. O algoritmo (fases PREPARE, PRE_COMMIT, COMMIT, DECIDE,
 * troca de visao, lider, quorum, finalizacao, ataques) vem em D3a-2a-2.
 *
 * <p>O que ESTE esqueleto faz, e so isso:
 * <ul>
 *   <li>guarda a {@link HotStuffConsensusConfig} e o numero de participantes (validado: n &gt;= 4);</li>
 *   <li>acumula o trafego MEDIDO recebido por este no, por bloco, alimentado por
 *       {@code PeerBlockchainNode#processIncomingPacket} com {@code packet.getSize()}.</li>
 * </ul>
 *
 * <p><b>Granularidade do trafego (declarada):</b> POR NO QUE RECEBE (cada instancia pertence a um unico no e so
 * soma pacotes entregues a ele), POR BLOCO. Duas componentes, como no PBFT (ADR-0006) e no DAGsper:
 * <ul>
 *   <li>C_B_total ({@link #getBlockTraffic}): todo byte recebido que nomeia o bloco (carga do bloco + votos);</li>
 *   <li>C_B_consenso ({@link #getConsensusMessageTraffic}): so os votos (a carga do bloco nao conta).</li>
 * </ul>
 * Pacotes que o no entrega a si mesmo (autoentrega local, sem passar pela rede simulada) NAO sao trafego e o
 * hook nao os soma (ver {@code isLocalSelfDelivery} em PeerBlockchainNode). Zero e zero medido, nao o sentinela -1.
 *
 * <p><b>Atribuicao de bloco (D3a-2a-2b).</b> Cada tipo de mensagem de rede e atribuido a um bloco SEM consultar
 * a lista de pais do bloco (mutavel na base, {@code Block#getParents}): usa-se a referencia ao bloco que a propria
 * mensagem/QC capturou em campo {@code final} na construcao. Os mapas deste consenso sao chaveados por essa
 * identidade (Block nao define equals/hashCode), nunca por posicao na arvore.
 * <ul>
 *   <li>DataMessage de bloco: o bloco enviado (total);</li>
 *   <li>{@link jabs.ledgerdata.hotstuff.HotStuffVote}: o bloco do voto (total e consenso, inteiro);</li>
 *   <li>{@link HotStuffProposalMessage}: o bloco da proposta; total = {@code packet.getSize()}, consenso = total
 *       menos a carga do bloco (so PREPARE leva a carga: ver {@link #recordProposalTraffic});</li>
 *   <li>{@link HotStuffQcNetworkMessage} com QC (DECIDE, NEW_VIEW com prepareQC): o bloco do QC carregado, inteiro
 *       nos dois;</li>
 *   <li>NEW_VIEW SEM QC: nao nomeia bloco nenhum. NAO e descartado nem atribuido a bloco por palpite: vai para um
 *       contador a parte, por no ({@link #getUnattributedTraffic}, {@link #takeUnattributedTraffic}), fora de
 *       C_B_total e C_B_consenso de qualquer bloco. A parte 2c decide se e como o soma a algum bloco.</li>
 * </ul>
 *
 * <p><b>Corte (convencao PBFT).</b> {@link #markLocallyFinalized} marca o bloco como finalizado localmente neste
 * no; a partir dai {@link #addBlockTraffic}/{@link #addConsensusMessageTraffic} ignoram pacotes daquele bloco
 * (proposta, voto, QC ou o proprio bloco). {@link #takeTraffic} devolve e zera os contadores do bloco (e o que a
 * 2c le ao emitir o {@code BlockFinalizationEvent}). A marca nao e desfeita por {@code takeTraffic}, senao um
 * pacote tardio recomecaria a conta. O contador a parte (NEW_VIEW sem QC) nao tem bloco e nao sofre corte.
 *
 * <p><b>Pendencias (nao feitas aqui):</b>
 * <ul>
 *   <li>D3a-2a-2c: o algoritmo ({@link #onProposal}, {@link #onQcMessage}, fases, quorum, lider, troca de visao),
 *       a chamada de {@code markLocallyFinalized}/{@code takeTraffic} e a emissao do
 *       {@code BlockFinalizationEvent} com C_B_total e C_B_consenso;</li>
 *   <li>D3a-2a-2d: {@code supportedAttacks()}: continua o padrao (vazio), logo {@code setByzantineConfig} com
 *       ataque FALHA ALTO ate o algoritmo declarar EQUIVOCATION (decisao do humano para o estudo).</li>
 * </ul>
 *
 * @param <B> tipo de bloco
 * @param <T> tipo de transacao
 */
public class HotStuff<B extends SingleParentBlock<B>, T extends Tx<T>> extends AbstractChainBasedConsensus<B, T> {
    private final HotStuffConsensusConfig config;
    private final int numAllParticipants;

    // Trafego MEDIDO por no, por bloco. Acesso so por chave (get/put), nunca iteracao: ordem de HashMap
    // nao influencia comportamento.
    private final HashMap<B, Long> blockTraffic = new HashMap<>();
    private final HashMap<B, Long> consensusTraffic = new HashMap<>();
    // Blocos finalizados localmente (corte). Acesso so por contains/add, nunca iteracao.
    private final HashSet<B> locallyFinalized = new HashSet<>();
    // NEW_VIEW sem QC: nao nomeia bloco; contado a parte (ver javadoc da classe). Total == consenso.
    private long unattributedTraffic = 0L;

    // ---- D3a-2a-2c-2a: lado da REPLICA do caminho honesto ----
    private final ViewLeaders leaders;
    private final DiscardCounters discards = new DiscardCounters();
    private final TreeBlockAccess blockAccess;
    private final ReplicaCore replica;
    // Agregadores do papel de LIDER: aqui so ARMAZENAM (o lider age na 2c-2b). Acesso por chave/LinkedHashMap.
    private final VoteAggregator<HotStuffBlock> voteAggregator = new VoteAggregator<>();
    private final NewViewAggregator newViewAggregator = new NewViewAggregator();

    /** Contadores de um bloco devolvidos (e zerados) por {@link #takeTraffic}. */
    public record BlockTraffic(long total, long consensus) { }

    /**
     * @param localBlockTree arvore local de blocos do no
     * @param config configuracao (obrigatoria)
     * @param numAllParticipants n (&gt;= 4)
     * @throws IllegalArgumentException se config for nulo ou n &lt; 4
     */
    public HotStuff(LocalBlockTree<B> localBlockTree, HotStuffConsensusConfig config, int numAllParticipants) {
        super(localBlockTree);
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        config.validateFor(numAllParticipants);
        this.config = config;
        this.numAllParticipants = numAllParticipants;
        this.currentMainChainHead = localBlockTree.getGenesisBlock();
        this.leaders = new ViewLeaders(config, numAllParticipants);
        // HotStuff so e instanciado com blocos HotStuff (HotStuffNode); o cast e verificado pelo uso.
        @SuppressWarnings("unchecked")
        LocalBlockTree<HotStuffBlock> hotStuffTree = (LocalBlockTree<HotStuffBlock>) (LocalBlockTree<?>) localBlockTree;
        this.blockAccess = new TreeBlockAccess(hotStuffTree);
        this.replica = new ReplicaCore(numAllParticipants, leaders, new ReplicaState<HotStuffBlock>(0L),
                blockAccess, discards);
    }

    public HotStuffConsensusConfig getConfig() {
        return config;
    }

    public int getNumAllParticipants() {
        return numAllParticipants;
    }

    /** Quorum teorico 2f+1 para o n deste consenso (comparar sempre com {@code >=}). */
    public int getRequiredVotes() {
        return HotStuffConsensusConfig.quorumSize(numAllParticipants);
    }

    /**
     * Acumula C_B_total: bytes de um pacote recebido da rede simulada que nomeia {@code block}. Chamado por
     * {@code PeerBlockchainNode#processIncomingPacket} com {@code packet.getSize()}.
     */
    public void addBlockTraffic(B block, long bytes) {
        requireTraffic(block, bytes);
        if (locallyFinalized.contains(block)) {
            return; // corte: depois da finalizacao local o pacote nao entra mais (convencao PBFT)
        }
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    /**
     * Acumula C_B_consenso: bytes recebidos que pertencem so ao protocolo de votos (sem a carga do bloco).
     * Quem chama soma tambem em {@link #addBlockTraffic}, pois todo byte de consenso e tambem byte total.
     */
    public void addConsensusMessageTraffic(B block, long bytes) {
        requireTraffic(block, bytes);
        if (locallyFinalized.contains(block)) {
            return; // mesmo corte de addBlockTraffic
        }
        consensusTraffic.put(block, consensusTraffic.getOrDefault(block, 0L) + bytes);
    }

    /** C_B_total acumulado por este no para o bloco (0 se nada foi recebido). */
    public long getBlockTraffic(B block) {
        return blockTraffic.getOrDefault(block, 0L);
    }

    /** C_B_consenso acumulado por este no para o bloco (0 se nada foi recebido). */
    public long getConsensusMessageTraffic(B block) {
        return consensusTraffic.getOrDefault(block, 0L);
    }

    /**
     * Contabiliza uma proposta recebida PELA REDE SIMULADA. {@code packetBytes} e {@code packet.getSize()}
     * (== {@link HotStuffProposalMessage#getTotalSize()}). Bloco: o da proposta (referencia {@code final} da
     * mensagem, sem lista de pais). C_B_total += pacote inteiro; C_B_consenso += pacote menos a carga do bloco
     * (so PREPARE leva {@code block.getSize()}; nas demais fases consenso == total).
     */
    @SuppressWarnings("unchecked")
    public void recordProposalTraffic(HotStuffProposalMessage message, long packetBytes) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        long blockPayload = (long) message.getTotalSize() - message.getConsensusSize();
        long consensusBytes = packetBytes - blockPayload;
        if (consensusBytes < 0) {
            throw new IllegalStateException("pacote menor que a carga do bloco: " + packetBytes + " < " + blockPayload);
        }
        B block = (B) message.getBlock();
        addBlockTraffic(block, packetBytes);
        addConsensusMessageTraffic(block, consensusBytes);
    }

    /**
     * Contabiliza uma mensagem de QC (NEW_VIEW/DECIDE) recebida PELA REDE SIMULADA; tudo e protocolo, entao soma
     * inteiro nos dois. Bloco: o do QC carregado (referencia {@code final} do QC). NEW_VIEW sem QC nao nomeia
     * bloco: soma em {@link #getUnattributedTraffic()} (nunca descartado, nunca atribuido por palpite).
     */
    @SuppressWarnings("unchecked")
    public void recordQcMessageTraffic(HotStuffQcNetworkMessage message, long packetBytes) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        HotStuffQcMessage qcMessage = message.getQcMessage();
        if (qcMessage.hasQc()) {
            B block = (B) qcMessage.getQc().get().getBlock();
            addBlockTraffic(block, packetBytes);
            addConsensusMessageTraffic(block, packetBytes);
        } else {
            if (packetBytes < 0) {
                throw new IllegalArgumentException("bytes deve ser >= 0: " + packetBytes);
            }
            unattributedTraffic += packetBytes;
        }
    }

    /** Bytes de mensagens sem bloco (NEW_VIEW sem QC) recebidos por este no desde o ultimo take. */
    public long getUnattributedTraffic() {
        return unattributedTraffic;
    }

    /** Devolve e zera o contador de mensagens sem bloco. */
    public long takeUnattributedTraffic() {
        long v = unattributedTraffic;
        unattributedTraffic = 0L;
        return v;
    }

    /** Marca o bloco como finalizado localmente neste no: pacotes seguintes dele deixam de somar. Idempotente. */
    public void markLocallyFinalized(B block) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        locallyFinalized.add(block);
    }

    public boolean isLocallyFinalized(B block) {
        return locallyFinalized.contains(block);
    }

    /**
     * Devolve C_B_total e C_B_consenso acumulados do bloco (0 e 0 medido, nao -1) e zera os contadores. Nao
     * desfaz {@link #markLocallyFinalized}.
     */
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

    // ------------------------------------------------------------------------------------------------------
    // D3a-2a-2c-2a: LADO DA REPLICA do caminho honesto (Yin et al., PODC 2019, alg. 2). A decisao (validar,
    // reter, mudar estado) e do ReplicaCore (puro); aqui ficam so os efeitos com rede e simulador.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Proposta do lider (PREPARE, PRE_COMMIT, COMMIT ou DECIDE). Valida, retem visao futura, atualiza o estado e
     * responde com voto ao LIDER DA VISAO (ver {@link #sendVote}). Mensagem ignorada e CONTADA em
     * {@link #getDiscardCount}; nada e descartado em silencio. Chamado depois de {@link #recordProposalTraffic}
     * (o tamanho do pacote ja foi contado, inclusive o do DECIDE que finaliza o bloco).
     *
     * <p><b>Como o bloco entra na arvore local:</b> o bloco proposto viaja DENTRO da proposta PREPARE (nao ha
     * DataMessage separado). Se a proposta e ACEITA, o {@code TreeBlockAccess} o admite na {@code LocalBlockTree}
     * sem novo pacote: o trafego do bloco ja esta em C_B_total pela proposta. Proposta rejeitada nao deixa rastro
     * na arvore.
     */
    public void onProposal(HotStuffProposalMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        execute(replica.onLeaderMessage(LeaderMessage.fromProposal(message)));
    }

    /**
     * NEW_VIEW (replica -&gt; lider) ou DECIDE por mensagem de QC.
     * <ul>
     *   <li>DECIDE: mesmo tratamento do DECIDE por proposta (o QC leva o bloco);</li>
     *   <li>NEW_VIEW: so ARMAZENA no {@link NewViewAggregator} do lider da visao de destino
     *       ({@code view + 1}: {@link HotStuffQcMessage} define {@code view} como a visao que expirou; a conferir
     *       com o artigo). Quem usa o highQC e a troca de visao (2c-3). Entregue a quem nao e o lider de destino:
     *       contado NOT_VIEW_LEADER.</li>
     * </ul>
     */
    public void onQcMessage(HotStuffQcNetworkMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        HotStuffQcMessage qcMessage = message.getQcMessage();
        switch (qcMessage.getType()) {
            case DECIDE:
                execute(replica.onLeaderMessage(LeaderMessage.fromDecide(qcMessage)));
                break;
            case NEW_VIEW:
                storeNewView(qcMessage);
                break;
            default:
                throw new UnsupportedOperationException("HotStuff.onQcMessage: tipo " + qcMessage.getType()
                        + " nao tratado (troca de visao em 2c-3)");
        }
    }

    /**
     * Voto de uma replica chegando a ESTE no (papel de lider). So ARMAZENA no {@link VoteAggregator}; formar QC e
     * propor a fase seguinte e do lado do lider (2c-2b). Voto entregue a quem nao lidera a visao do voto:
     * NOT_VIEW_LEADER; id fora de [0, n): SENDER_OUT_OF_RANGE; repetido no mesmo balde: DUPLICATE_VOTE.
     */
    public void onVote(HotStuffVote vote) {
        if (vote == null) {
            throw new IllegalArgumentException("vote obrigatorio");
        }
        int voterId = vote.getVoter().getNodeID();
        if (voterId < 0 || voterId >= numAllParticipants) {
            discards.count(DiscardReason.SENDER_OUT_OF_RANGE);
            return;
        }
        if (leaders.leaderOf(vote.getView()) != ownId()) {
            discards.count(DiscardReason.NOT_VIEW_LEADER);
            return;
        }
        if (!voteAggregator.add(vote)) {
            discards.count(DiscardReason.DUPLICATE_VOTE);
        }
    }

    private void storeNewView(HotStuffQcMessage m) {
        long target = m.getView() + 1;
        if (leaders.leaderOf(target) != ownId()) {
            discards.count(DiscardReason.NOT_VIEW_LEADER);
            return;
        }
        if (!newViewAggregator.add(target, m.getSender().getNodeID(), m.getQc())) {
            discards.count(DiscardReason.DUPLICATE_NEW_VIEW);
        }
    }

    private void execute(List<ReplicaAction> actions) {
        for (ReplicaAction a : actions) {
            switch (a.kind()) {
                case VOTE:
                    sendVote(new HotStuffVote(requireNode(), a.votePhase(), a.view(), a.block(), config));
                    break;
                case FINALIZE:
                    finalizeLocally(a.block());
                    break;
                default:
                    throw new IllegalStateException("acao desconhecida: " + a.kind());
            }
        }
    }

    /**
     * Envia o voto ao LIDER DA VISAO (canal replica -&gt; lider do artigo), nunca em broadcast.
     * <b>Sem autoentrega:</b> se esta replica e o lider da visao, o voto vai direto ao agregador local
     * ({@link #onVote}), sem pacote, sem latencia e sem tráfego (o {@code HotStuffP2P} tambem nao liga o no a si
     * mesmo; a convencao ADR-0006 diz que autoentrega nao e trafego). A decisao de contar o voto proprio como um
     * dos 2f+1 e do lado do lider (2c-2b; a conferir com o artigo).
     */
    private void sendVote(HotStuffVote vote) {
        int leaderId = leaders.leaderOf(vote.getView());
        if (leaderId == ownId()) {
            onVote(vote);
            return;
        }
        Node leader = null;
        for (Node neighbor : requireNode().getP2pConnections().getNeighbors()) {
            if (neighbor.getNodeID() == leaderId) {
                leader = neighbor;
                break;
            }
        }
        if (leader == null) {
            throw new IllegalStateException("lider " + leaderId + " da visao " + vote.getView()
                    + " nao e vizinho do no " + ownId() + " (a malha completa deveria ligar todos)");
        }
        requireNode().getNodeNetworkInterface().addToUpLinkQueue(new Packet(requireNode(), leader, new VoteMessage(vote)));
    }

    /**
     * DECIDE: corte do trafego, retirada dos contadores e {@link BlockFinalizationEvent} como no PBFT (C_B_total e
     * C_B_consenso por no que finalizou; granularidade ADR-0006). O {@code ReplicaState} ja finalizou o bloco e
     * avancou a visao (ReplicaCore).
     */
    @SuppressWarnings("unchecked")
    private void finalizeLocally(HotStuffBlock hsBlock) {
        B block = (B) hsBlock;
        markLocallyFinalized(block); // antes do take: pacote tardio nao recomeca a conta
        BlockTraffic traffic = takeTraffic(block);
        this.currentMainChainHead = block;
        updateChain();
        requireNode().getSimulator().putEvent(
                new BlockFinalizationEvent(requireNode().getSimulator().getSimulationTime(), requireNode(), block,
                        traffic.total(), traffic.consensus()),
                0);
    }

    private PeerBlockchainNode<B, T> requireNode() {
        if (peerBlockchainNode == null) {
            throw new IllegalStateException("HotStuff sem no: chame setNode antes de entregar mensagens");
        }
        return peerBlockchainNode;
    }

    private int ownId() {
        return requireNode().getNodeID();
    }

    // ---- consultas (testes e lado do lider em 2c-2b) ----

    /** Mensagens ignoradas por motivo. */
    public long getDiscardCount(DiscardReason reason) { return discards.get(reason); }
    public long getTotalDiscards() { return discards.total(); }
    /** Visao corrente desta replica. */
    public long getCurrentView() { return replica.state().getCurrentView(); }
    public ReplicaState<HotStuffBlock> getReplicaState() { return replica.state(); }
    /** Mensagens do lider retidas agora (visao futura ou fase adiantada). Sem teto: ver javadoc de ReplicaCore. */
    public int getPendingProposalCount() { return replica.pendingSize(); }
    /** Pico da retencao desta replica na simulacao (para o cenario relatar). */
    public int getPeakPendingProposalCount() { return replica.peakPending(); }
    public VoteAggregator<HotStuffBlock> getVoteAggregator() { return voteAggregator; }
    public NewViewAggregator getNewViewAggregator() { return newViewAggregator; }

    private static void requireTraffic(Object block, long bytes) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes deve ser >= 0: " + bytes);
        }
    }

    /**
     * O PBFT tambem deixa o bloco recebido sem acao propria. Blocos que chegam por DataMessage (nao e o caminho da
     * proposta) ja foram postos na {@code LocalBlockTree} por {@code PeerBlockchainNode}; so entram no consenso
     * quando uma proposta PREPARE sobre eles for aceita.
     */
    @Override
    public void newIncomingBlock(B block) {
        // sem acao (ver javadoc)
    }

    /** Chamado so por {@link #finalizeLocally}: registra a aceitacao e poda a arvore e o indice de pais. */
    @Override
    protected void updateChain() {
        this.confirmedBlocks.add(this.currentMainChainHead);
        recordBlockAcceptance(this.currentMainChainHead); // sem catch: contabilizacao que falha deve falhar alto
        blockAccess.prune(Math.max(0, this.currentMainChainHead.getHeight() - 1000));
    }

    /** Proponente = criador do bloco; genesis (sem criador) -&gt; 0, condicao legitima, como no PBFT. */
    @Override
    protected int getBlockProposer(B block) {
        if (block != null && block.getCreator() != null) {
            return block.getCreator().getNodeID();
        }
        return 0;
    }
}
