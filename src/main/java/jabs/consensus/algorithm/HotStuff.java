package jabs.consensus.algorithm;

import jabs.consensus.algorithm.hotstuff.DiscardCounters;
import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.consensus.algorithm.hotstuff.LeaderAction;
import jabs.consensus.algorithm.hotstuff.LeaderCore;
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
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.SingleParentBlock;
import jabs.ledgerdata.Tx;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffQcNetworkMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.event.BlockFinalizationEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

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
 * <h3>D3a-2a-2c-2b-2: lado do LIDER no caminho honesto (Yin et al., PODC 2019, Algoritmo 2)</h3>
 * <ul>
 *   <li><b>Numeracao de visoes (decisao tecnica).</b> {@link #INITIAL_VIEW} = 1, fiel ao artigo (secao 4.2:
 *       {@code viewNumber} comeca em 1). Os NEW_VIEW iniciais sao "da visao 0" ({@link #INITIAL_NEW_VIEW_VIEW}): o
 *       campo {@code view} de {@code HotStuffQcMessage} exige {@code >= 0} e o destino e {@code view + 1}
 *       (Algoritmo 2, linha 5; {@code LeaderCore} e {@code ReplicaState} recebem {@code INITIAL_VIEW}). O lider da
 *       primeira visao e {@code leaderOf(1)}; com ROTATING, {@code leaderOf(v) = v mod n}.</li>
 *   <li><b>Fluxo.</b> {@link #start()}: cada replica envia NEW_VIEW sem QC ao lider da visao inicial (pacote pela
 *       rede; o lider se autoentrega localmente, sem pacote e sem trafego, e conta como um dos n-f). Com n-f
 *       NEW_VIEW o {@link LeaderCore} propoe PREPARE; com n-f votos de cada fase forma o QC e difunde a fase
 *       seguinte. O bloco vem de {@code BlockFactory.sampleHotStuffBlockWithTx} com lote cheio
 *       ({@code config.getBatchSize()} transacoes de {@code config.getTxSizeBytes()}; ambos obrigatorios no
 *       config, sem padrao). Ao fim de DECIDE (visao v), cada replica envia NEW_VIEW(view = v, prepareQC) ao lider
 *       de v+1 (pela rede, ou autoentrega local se ela for o lider).</li>
 *   <li><b>DECIDE: um unico caminho, {@code HotStuffProposalMessage.decide}.</b> As quatro fases do lider
 *       (PREPARE, PRE_COMMIT, COMMIT, DECIDE) usam o mesmo tipo de mensagem, a mesma contabilizacao
 *       ({@link #recordProposalTraffic}) e o mesmo despacho. A replica continua tratando tambem
 *       {@code HotStuffQcMessage.decide} (nao emitido por este lider).</li>
 *   <li><b>Autoentrega e reentrada.</b> A difusao vai a todos os OUTROS nos ({@code broadcastMessage}; o
 *       {@code HotStuffP2P} nao liga o no a si) e o proprio lider recebe a mensagem por chamada direta a
 *       {@link #onProposal}, sem pacote e sem trafego. Como essa entrega gera voto local, que chama
 *       {@code LeaderCore.onVote} de dentro da execucao de uma acao, as {@link LeaderAction} vao para uma FILA
 *       interna esvaziada por um unico laco: {@code executeLeaderAction} nunca e aninhada
 *       ({@link #getMaxLeaderActionDepth()} == 1 e {@link #getDeferredLeaderActions()} medem isso).</li>
 *   <li><b>Trafego.</b> So conta o que este no RECEBE pela rede; os envios do lider nao entram. NEW_VIEW com QC e
 *       atribuido ao bloco do QC e fica sujeito ao corte de {@link #markLocallyFinalized} (convencao PBFT): chegando
 *       depois da finalizacao local desse bloco, nao soma em C_B daquele bloco.</li>
 *   <li><b>Fora do escopo:</b> troca de visao por timeout/pacemaker (2c-3), EQUIVOCATION (2d). O caminho honesto
 *       nao usa {@code viewTimeoutSeconds}.</li>
 * </ul>
 *
 * <p><b>Pendencias (nao feitas aqui):</b>
 * <ul>
 *   <li>D3a-2a-2c-3: troca de visao por timeout (pacemaker) e sincronizacao de replica atrasada;</li>
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
    // Agregadores do papel de LIDER, usados pelo LeaderCore. Acesso por chave/LinkedHashMap.
    private final VoteAggregator<HotStuffBlock> voteAggregator = new VoteAggregator<>();
    private final NewViewAggregator newViewAggregator = new NewViewAggregator();

    // ---- D3a-2a-2c-2b-2: lado do LIDER ----
    /** Visao inicial, fiel ao artigo (secao 4.2). O lider da primeira visao e {@code leaderOf(1)}. */
    public static final long INITIAL_VIEW = 1L;
    /** Os NEW_VIEW iniciais sao "da visao 0" (Algoritmo 2, linha 4); destino = {@code view + 1}. */
    public static final long INITIAL_NEW_VIEW_VIEW = 0L;

    private final LeaderCore leaderCore;
    // Fila das acoes do lider: evita reentrada (a autoentrega gera voto local que chama LeaderCore.onVote).
    private final ArrayDeque<LeaderAction> leaderQueue = new ArrayDeque<>();
    private boolean drainingLeaderQueue = false;
    private int entryDepth = 0;
    private int leaderActionDepth = 0;
    private int maxLeaderActionDepth = 0;
    private long deferredLeaderActions = 0L;
    private long executedLeaderActions = 0L;
    private boolean started = false;

    /** Contadores de um bloco devolvidos (e zerados) por {@link #takeTraffic}. */
    public record BlockTraffic(long total, long consensus) { }

    /**
     * @param localBlockTree arvore local de blocos do no
     * @param config configuracao (obrigatoria)
     * @param numAllParticipants n (&gt;= 4)
     * @param self id do no dono em [0, n) (o {@link LeaderCore} precisa saber se lidera uma visao)
     * @throws IllegalArgumentException se config for nulo, n &lt; 4 ou self fora de [0, n)
     */
    public HotStuff(LocalBlockTree<B> localBlockTree, HotStuffConsensusConfig config, int numAllParticipants,
                    int self) {
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
        this.replica = new ReplicaCore(numAllParticipants, leaders, new ReplicaState<HotStuffBlock>(INITIAL_VIEW),
                blockAccess, discards);
        HotStuffBlock genesisBlock = hotStuffTree.getGenesisBlock();
        this.leaderCore = new LeaderCore(numAllParticipants, self, INITIAL_VIEW, leaders, config, genesisBlock,
                this::proposeBlock, voteAggregator, newViewAggregator, discards);
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
     * (o tamanho do pacote ja foi contado, inclusive o do DECIDE que finaliza o bloco). Tambem e a porta da
     * AUTOENTREGA do lider (chamada direta, sem pacote, sem trafego).
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
        runEntry(() -> execute(replica.onLeaderMessage(LeaderMessage.fromProposal(message))));
    }

    /**
     * NEW_VIEW (replica -&gt; lider) ou DECIDE por mensagem de QC.
     * <ul>
     *   <li>DECIDE: mesmo tratamento do DECIDE por proposta (o QC leva o bloco). Este lider NAO emite DECIDE por
     *       mensagem de QC (usa {@code HotStuffProposalMessage.decide}), mas a replica o aceita;</li>
     *   <li>NEW_VIEW: vai ao {@link LeaderCore} com a visao de destino {@code view + 1} ({@link HotStuffQcMessage}
     *       define {@code view} como a visao que terminou; Algoritmo 2, linha 5). Entregue a quem nao e o lider de
     *       destino: contado NOT_VIEW_LEADER. Com n-f NEW_VIEW distintos o lider propoe PREPARE.</li>
     * </ul>
     */
    public void onQcMessage(HotStuffQcNetworkMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message obrigatoria");
        }
        HotStuffQcMessage qcMessage = message.getQcMessage();
        switch (qcMessage.getType()) {
            case DECIDE:
                runEntry(() -> execute(replica.onLeaderMessage(LeaderMessage.fromDecide(qcMessage))));
                break;
            case NEW_VIEW:
                runEntry(() -> enqueueLeaderActions(leaderCore.onNewView(qcMessage.getView() + 1,
                        qcMessage.getSender().getNodeID(), qcMessage.getQc())));
                break;
            default:
                throw new UnsupportedOperationException("HotStuff.onQcMessage: tipo " + qcMessage.getType()
                        + " nao tratado");
        }
    }

    /**
     * Voto de uma replica chegando a ESTE no (papel de lider), inclusive o voto do proprio lider (autoentrega).
     * Vai ao {@link LeaderCore}, que valida (cada rejeicao e contada em {@link DiscardReason}), agrega e, no
     * quorum n-f, forma o QC e devolve a difusao da fase seguinte, executada pela fila interna (sem reentrada).
     */
    public void onVote(HotStuffVote vote) {
        if (vote == null) {
            throw new IllegalArgumentException("vote obrigatorio");
        }
        runEntry(() -> enqueueLeaderActions(leaderCore.onVote(vote)));
    }

    // ------------------------------------------------------------------------------------------------------
    // D3a-2a-2c-2b-2: inicio, fila de acoes do lider e NEW_VIEW.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Inicia o protocolo neste no (Algoritmo 2, linha 4: NEW_VIEW da "visao 0"): envia NEW_VIEW sem prepareQC ao
     * lider da visao inicial, pela rede simulada (um pacote, MEDIDO no receptor em {@link #getUnattributedTraffic});
     * se este no e o lider, a entrega e local (sem pacote e sem trafego) e conta como um dos n-f. Chamado uma vez
     * pelo cenario depois de montar a rede.
     *
     * @throws IllegalStateException se ja foi iniciado ou o no nao foi definido
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("HotStuff ja iniciado no no " + ownId());
        }
        started = true;
        runEntry(() -> sendNewView(INITIAL_NEW_VIEW_VIEW,
                HotStuffQcMessage.newViewWithoutPrepareQc(requireNode(), INITIAL_NEW_VIEW_VIEW,
                        numAllParticipants, config)));
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Envia o NEW_VIEW ao lider da visao {@code endedView + 1}: pela rede (um pacote) ou, se este no e o lider,
     * por entrega local direta ao {@link LeaderCore} (sem pacote, sem trafego).
     */
    private void sendNewView(long endedView, HotStuffQcMessage message) {
        long target = endedView + 1;
        int leaderId = leaders.leaderOf(target);
        if (leaderId == ownId()) {
            enqueueLeaderActions(leaderCore.onNewView(target, ownId(), message.getQc()));
            return;
        }
        requireNode().getNodeNetworkInterface().addToUpLinkQueue(
                new Packet(requireNode(), neighbor(leaderId, target), new HotStuffQcNetworkMessage(message)));
    }

    private Node neighbor(int id, long view) {
        for (Node candidate : requireNode().getP2pConnections().getNeighbors()) {
            if (candidate.getNodeID() == id) {
                return candidate;
            }
        }
        throw new IllegalStateException("lider " + id + " da visao " + view + " nao e vizinho do no " + ownId()
                + " (a malha completa deveria ligar todos)");
    }

    /**
     * Ponto de entrada de TODA mensagem (pacote, autoentrega ou start). So a entrada mais externa esvazia a fila
     * de acoes do lider; entradas aninhadas apenas enfileiram. Assim a execucao de uma {@link LeaderAction}
     * (broadcast + entrega local, que gera voto local e chama {@link LeaderCore#onVote}) nunca reentra em si.
     */
    private void runEntry(Runnable body) {
        entryDepth++;
        try {
            body.run();
        } finally {
            entryDepth--;
        }
        if (entryDepth == 0) {
            drainLeaderQueue();
        }
    }

    private void enqueueLeaderActions(List<LeaderAction> actions) {
        if (entryDepth > 1) {
            // devolvidas por uma entrada ANINHADA (p.ex. o voto local do lider, entregue de dentro do processamento de
            // outra mensagem ou de uma acao): numa versao recursiva executariam aqui dentro; esperam a vez.
            deferredLeaderActions += actions.size();
        }
        leaderQueue.addAll(actions);
    }

    private void drainLeaderQueue() {
        entryDepth++; // acoes executadas aqui nao sao entradas externas: nada reentra neste laco
        drainingLeaderQueue = true;
        try {
            LeaderAction action;
            while ((action = leaderQueue.poll()) != null) {
                leaderActionDepth++;
                maxLeaderActionDepth = Math.max(maxLeaderActionDepth, leaderActionDepth);
                try {
                    executeLeaderAction(action);
                } finally {
                    leaderActionDepth--;
                }
                executedLeaderActions++;
            }
        } finally {
            drainingLeaderQueue = false;
            entryDepth--;
        }
    }

    /**
     * Difunde a fase aos OUTROS nos pela rede simulada ({@code broadcastMessage}; o {@code HotStuffP2P} nao liga o
     * no a si mesmo) e a entrega ao proprio lider por chamada direta a {@link #onProposal}: sem pacote, sem
     * latencia e sem trafego (ADR-0006). Todas as fases, DECIDE inclusive, usam {@link HotStuffProposalMessage}.
     */
    private void executeLeaderAction(LeaderAction a) {
        HotStuffProposalMessage message = toProposal(a);
        requireNode().broadcastMessage(message);
        onProposal(message);
    }

    private HotStuffProposalMessage toProposal(LeaderAction a) {
        Node leader = requireNode();
        HotStuffBlock block = a.block();
        Optional<HotStuffQuorumCertificate> qc = a.qc();
        switch (a.phase()) {
            case PREPARE:
                return qc.isPresent()
                        ? HotStuffProposalMessage.prepare(a.view(), leader, block, qc.get(), numAllParticipants)
                        : HotStuffProposalMessage.prepareWithoutHighQc(a.view(), leader, block, config,
                                numAllParticipants);
            case PRE_COMMIT:
                return HotStuffProposalMessage.preCommit(a.view(), leader, block, qc.get());
            case COMMIT:
                return HotStuffProposalMessage.commit(a.view(), leader, block, qc.get());
            case DECIDE:
                return HotStuffProposalMessage.decide(a.view(), leader, block, qc.get());
            default:
                throw new IllegalStateException("fase desconhecida: " + a.phase());
        }
    }

    /**
     * createLeaf do artigo: lote CHEIO ({@code config.getBatchSize()} transacoes de {@code config.getTxSizeBytes()}
     * bytes, ambos do config, sem valor embutido). Nao consome aleatoriedade.
     */
    private HotStuffBlock proposeBlock(long view, HotStuffBlock parent) {
        if (!(requireNode() instanceof HotStuffNode)) {
            throw new IllegalStateException("HotStuff so propoe blocos em HotStuffNode");
        }
        HotStuffNode creator = (HotStuffNode) (Object) requireNode();
        return BlockFactory.sampleHotStuffBlockWithTx(requireNode().getSimulator(), creator, parent, config,
                config.getTxSizeBytes(), config.getBatchSize());
    }

    private void execute(List<ReplicaAction> actions) {
        for (ReplicaAction a : actions) {
            switch (a.kind()) {
                case VOTE:
                    sendVote(new HotStuffVote(requireNode(), a.votePhase(), a.view(), a.block(), config));
                    break;
                case FINALIZE:
                    finalizeLocally(a.block());
                    advanceToNextView(a.view());
                    break;
                default:
                    throw new IllegalStateException("acao desconhecida: " + a.kind());
            }
        }
    }

    /**
     * Fim da visao {@code endedView} (DECIDE): NEW_VIEW com o prepareQC da replica ao lider de {@code endedView+1}
     * (Algoritmo 2, linha 5 do lider seguinte). O prepareQC existe sempre aqui (PRE_COMMIT precede DECIDE); se nao
     * existisse seria bug do nucleo, e falha alto.
     */
    private void advanceToNextView(long endedView) {
        HotStuffQuorumCertificate prepareQc = replica.state().getPrepareQc().orElseThrow(
                () -> new IllegalStateException("DECIDE da visao " + endedView + " sem prepareQC na replica"));
        sendNewView(endedView, HotStuffQcMessage.newView(requireNode(), endedView, prepareQc));
    }

    /**
     * Envia o voto ao LIDER DA VISAO (canal replica -&gt; lider do artigo), nunca em broadcast.
     * <b>Sem autoentrega por pacote:</b> se esta replica e o lider da visao, o voto vai direto ao
     * {@link LeaderCore} ({@link #onVote}), sem pacote, sem latencia e sem trafego (o {@code HotStuffP2P} tambem
     * nao liga o no a si mesmo; ADR-0006). O voto do lider conta como UM dos n-f (decisao do {@link LeaderCore}).
     */
    private void sendVote(HotStuffVote vote) {
        int leaderId = leaders.leaderOf(vote.getView());
        if (leaderId == ownId()) {
            onVote(vote);
            return;
        }
        requireNode().getNodeNetworkInterface().addToUpLinkQueue(
                new Packet(requireNode(), neighbor(leaderId, vote.getView()), new VoteMessage(vote)));
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
    /** Maior aninhamento de execucao de {@link LeaderAction} observado (1 = nunca reentrou). */
    public int getMaxLeaderActionDepth() { return maxLeaderActionDepth; }
    /** Acoes que o LeaderCore devolveu a uma entrada ANINHADA (voto/NEW_VIEW local) e que esperaram a vez (nao reentraram). */
    public long getDeferredLeaderActions() { return deferredLeaderActions; }
    /** Acoes do lider executadas ate agora (difusoes de fase). */
    public long getExecutedLeaderActions() { return executedLeaderActions; }
    public LeaderCore getLeaderCore() { return leaderCore; }
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
