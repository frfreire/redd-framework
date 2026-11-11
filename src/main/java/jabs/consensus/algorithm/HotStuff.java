package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
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
import jabs.ledgerdata.hotstuff.HotStuffBlockWithTx;
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
import jabs.simulator.event.HotStuffViewTimeoutEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * </ul>
 *
 * <h3>D3a-2a-2c-3: troca de visao por TIMEOUT (Basic HotStuff, secao 4.1, "nextView interrupt")</h3>
 * <ul>
 *   <li><b>Timer por visao.</b> Ao entrar numa visao (em {@link #start()} e a cada avanco) a replica agenda um
 *       {@link HotStuffViewTimeoutEvent}(this, view) com atraso {@code config.getViewTimeoutSeconds()} (valor do
 *       config; nenhum valor embutido). O timer NAO e cancelado quando a visao termina por DECIDE: o evento obsoleto
 *       chega e o {@link ReplicaCore} o filtra e conta em {@link DiscardReason#STALE_VIEW_TIMEOUT} (mesmo estilo do
 *       {@code TendermintTimeoutEvent}). O evento nao consome aleatoriedade e, no regime honesto (timeout bem maior
 *       que a duracao de uma visao), so aparece como obsoleto: a sequencia de finalizacoes nao muda.</li>
 *   <li><b>Expirou o timer da visao corrente v</b> ({@link #onViewTimeout}): a replica avanca para v+1, envia
 *       NEW_VIEW(view = v, prepareQC ou sem QC se bottom) ao lider de v+1 (pacote pela rede; autoentrega local se for
 *       ela o lider) e arma o timer de v+1. E a mesma acao {@link ReplicaAction.Kind#NEW_VIEW} do fim de visao por
 *       DECIDE: um unico caminho de envio e de contabilizacao de trafego.</li>
 *   <li><b>PROVISORIO:</b> timeout de duracao CONSTANTE por visao, sem backoff exponencial. A politica de
 *       crescimento do timeout e o valor de Delta sao <b>a definir com o humano</b> (ADR-0015 Q12, A-24). Nada foi
 *       escolhido aqui.</li>
 *   <li><b>Trafego.</b> Os NEW_VIEW de troca de visao sao pacotes da rede simulada, medidos como os iniciais pelo
 *       hook de {@code PeerBlockchainNode} e por {@link #recordQcMessageTraffic} (sem QC: contador a parte; com QC:
 *       ao bloco do QC, sujeito ao corte). Nao ha contagem paralela. Pacote de no em crash nao existe.</li>
 *   <li><b>Limites declarados (nao implementados):</b> sincronizacao de replica atrasada (catch-up por QC/state
 *       transfer, Pacemaker/Cogsworth): a troca de visao e LOCAL, sem mensagem que alinhe visoes; retencao de visoes
 *       futuras limitada pelo horizonte {@code maxFutureViewHorizon} do config (obrigatorio, sem padrao; valor de
 *       producao a definir com o humano; excedente descartado e contado em {@link DiscardReason#FUTURE_VIEW_BEYOND_HORIZON};
 *       custo sobre replica atrasada descrito em {@link ReplicaCore}); proposta de visao
 *       que a replica ja deixou e perdida (e, no lado do lider, NEW_VIEW e votos de visao que a propria replica ja
 *       abandonou sao descartados e contados em {@link DiscardReason#LEADER_VIEW_ABANDONED}: ver {@link LeaderCore}). Falha de lider so por CRASH ({@code Node#crash()} no teste/cenario);
 *       {@code supportedAttacks()} declara so EQUIVOCATION (D3a-2a-2d-1, abaixo).</li>
 * </ul>
 *
 * <h3>D3a-2a-2d-1: ataque EQUIVOCATION (ADR-0010 PI-12)</h3>
 * {@link #supportedAttacks()} = {EQUIVOCATION}. {@code SILENT}, {@code WITHHOLD}, {@code DOUBLE_SIGN} e nome
 * desconhecido continuam recusados com falha alta em {@code setByzantineConfig} (nunca viram no honesto em silencio).
 * Definicao (ADR-0010 PI-12): o no bizantino emite DUAS mensagens conflitantes, cada uma a um subconjunto DISJUNTO de
 * nos honestos. O nucleo ({@link LeaderCore}, {@link ReplicaCore}) NAO foi alterado: o no bizantino roda o mesmo
 * protocolo honesto para o estado interno e a equivocacao fica so na camada de ENVIO, depois de o nucleo decidir
 * (como no Tendermint). Os pacotes conflitantes vao pela fila de upload ({@code addToUpLinkQueue}) e sao contados
 * pelo hook de trafego de {@code PeerBlockchainNode} no receptor, como qualquer outro.
 * <p><b>PROVISORIO ({@code a definir com o humano}, ADR-0015 Q28; B-34, B-35, B-36):</b> particao, abrangencia e
 * coordenacao abaixo sao escolhas provisorias, iguais ao PBFT e ao Tendermint onde possivel, para os resultados
 * serem comparaveis e a troca ser trivial depois.
 * <ul>
 *   <li><b>Particao (B-34):</b> duas metades por INDICE dos vizinhos ({@code half = max(1, size/2)}); indices
 *       [0, half) recebem o valor A e [half, size) o valor B. No {@code HotStuffP2P} os vizinhos sao todos os OUTROS
 *       nos em ordem crescente de id. Pre-condicao ({@link #requireEquivocationSplit}): ao menos 2 vizinhos e ao
 *       menos 1 destinatario HONESTO em cada metade; senao {@link IllegalStateException} com mensagem clara, antes
 *       de fabricar bloco ou enviar pacote (nunca degrada para no honesto).</li>
 *   <li><b>Abrangencia (B-35), o que EQUIVOCA:</b> (i) o LIDER bizantino, a proposta PREPARE de cada visao que
 *       conduz: A (o bloco do {@link LeaderCore}) aos vizinhos [0, half), B (bloco conflitante, mesmo pai, mesma
 *       altura e mesmo tamanho, valido para a visao) aos demais; (ii) a REPLICA bizantina, cada VOTO que envia pela
 *       rede (PREPARE, PRE_COMMIT, COMMIT): o voto no bloco real aos vizinhos [0, half) e o voto num bloco
 *       conflitante aos demais.</li>
 *   <li><b>O que NAO equivoca, e por que.</b> (1) PRE_COMMIT, COMMIT e DECIDE do lider: cada uma carrega o QC da
 *       fase anterior SOBRE o bloco proposto ({@code HotStuffProposalMessage} exige o mesmo bloco); uma versao B
 *       exigiria um QC sobre B, isto e, 2f+1 votos em B que o lider nao tem. Fabrica-lo (copiando os votantes do QC
 *       de A) modelaria a quebra da assinatura de QC, nao uma equivocacao. (2) Alem disso essas fases sao
 *       INALCANCAVEIS para um lider que equivoca o PREPARE: A so chega a {@code half} vizinhos mais o proprio lider
 *       (voto local), e {@code floor((n-1)/2) + 1 < n - floor((n-1)/3)} para todo n &gt;= 4, logo o
 *       {@link LeaderCore} nunca forma o QC de A; os votos em B que chegam ao lider sao contados como
 *       {@link DiscardReason#VOTE_WRONG_BLOCK}. Consequencia (liveness): a visao de um lider que equivoca nunca
 *       decide; as replicas saem dela por timeout. Se um lider equivocador algum dia emitir uma fase posterior, ela
 *       sai HONESTA e e CONTADA em {@link #getUnequivocatedLeaderPhases()} (nunca em silencio); os testes exigem 0.
 *       (3) O voto que o proprio lider da visao entrega a si mesmo (chamada local, sem pacote), NEW_VIEW e timeouts:
 *       nao ha destinatario para dividir.</li>
 *   <li><b>Voto: canal replica-&gt;lider.</b> Diferente do Tendermint (voto em broadcast), o voto honesto do HotStuff
 *       tem UM destinatario, o lider. A replica bizantina aplica a particao por indice a TODOS os vizinhos: o voto
 *       real aos de [0, half) e o conflitante aos demais. Como o lider cai numa das metades, so UMA das versoes chega
 *       ao lider; as outras chegam a nao-lideres e sao descartadas por NOT_VIEW_LEADER, mas ocupam banda e sao
 *       contadas pelo hook. O trafego extra e MEDIDO sobre um modelo provisorio, nao o custo de um HotStuff real.</li>
 *   <li><b>Coordenacao (B-36):</b> nenhuma. Cada bizantino decide sozinho, com a mesma particao por indice, e
 *       fabrica o seu proprio bloco conflitante.</li>
 * </ul>
 * Bloco conflitante: {@link #conflictingBlock}. E um {@code HotStuffBlockWithTx} novo (identidade e hash distintos),
 * com o MESMO pai ({@code getFixedParent}), altura e tamanho do bloco real, e conjunto de transacoes VAZIO: nao
 * chama {@code BlockFactory} (que poe transacoes em {@code HashSet} sem {@code hashCode}, ou seja, consome
 * {@code identityHashCode} da thread principal, ADR-0012 RM-08), nao consome aleatoriedade e nao chama
 * {@code hashCode}. O cache (visao, bloco real) -&gt; B e uma lista varrida por identidade, sem mapa hash. B nunca
 * e finalizado (nao ha QC sobre B), entao o conjunto vazio nao chega a Tt.
 * <p><b>Descarte de visao.</b> O ataque nao contorna {@link LeaderCore} nem {@link ReplicaCore}: so envia o que o
 * {@link LeaderCore} devolveu (a checagem LEADER_VIEW_ABANDONED vem antes) e toda mensagem conflitante entra no
 * receptor por {@link #onProposal}/{@link #onVote}, portanto sob OLD_VIEW e FUTURE_VIEW_BEYOND_HORIZON. O voto
 * conflitante sai do {@code ReplicaCore} (que ja filtrou visao) e so muda o bloco, nunca a visao.
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
    private long leaderActionsOnAbandonedView = 0L;
    private boolean started = false;
    private long crashedTimeouts = 0L;
    private long finalizedAncestors = 0L;

    // ---- D3a-2a-2d-1: EQUIVOCATION (camada de envio) ----
    /** Bloco conflitante (B) fabricado para um bloco real (A) numa visao. Lista varrida por IDENTIDADE (sem hash). */
    private record Alternative(long view, HotStuffBlock real, HotStuffBlock conflicting) { }

    private final ArrayList<Alternative> alternatives = new ArrayList<>();
    private long equivocatedProposals = 0L;
    private long equivocatedVotes = 0L;
    private long unequivocatedLeaderPhases = 0L;

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
                blockAccess, discards, config.getMaxFutureViewHorizon());
        HotStuffBlock genesisBlock = hotStuffTree.getGenesisBlock();
        this.leaderCore = new LeaderCore(numAllParticipants, self, INITIAL_VIEW, leaders, config, genesisBlock,
                this::proposeBlock, voteAggregator, newViewAggregator, discards,
                () -> replica.state().getCurrentView());
    }

    public HotStuffConsensusConfig getConfig() {
        return config;
    }

    public int getNumAllParticipants() {
        return numAllParticipants;
    }

    /**
     * Unico ataque implementado: {@code EQUIVOCATION} (ver a secao D3a-2a-2d-1 do javadoc da classe; particao,
     * abrangencia e coordenacao PROVISORIAS, ADR-0015 Q28). Os demais sao recusados por {@code setByzantineConfig}.
     */
    @Override
    protected Set<ByzantineConfig.AttackType> supportedAttacks() {
        return EnumSet.of(ByzantineConfig.AttackType.EQUIVOCATION);
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
        armViewTimer(INITIAL_VIEW);
        runEntry(() -> sendNewView(INITIAL_NEW_VIEW_VIEW,
                HotStuffQcMessage.newViewWithoutPrepareQc(requireNode(), INITIAL_NEW_VIEW_VIEW,
                        numAllParticipants, config)));
    }

    /**
     * Agenda o timeout da visao {@code view}: {@code config.getViewTimeoutSeconds()} a partir de agora. Sem
     * cancelamento (o evento obsoleto e filtrado pelo {@link ReplicaCore}); sem aleatoriedade.
     */
    private void armViewTimer(long view) {
        if (!started) {
            // no dirigido a mao por teste (mensagens entregues sem start()): nao ha protocolo em curso, logo nao ha
            // timer. Em execucao normal start() precede qualquer mensagem.
            return;
        }
        requireNode().getSimulator().putEvent(new HotStuffViewTimeoutEvent(this, view),
                config.getViewTimeoutSeconds());
    }

    /**
     * O timer da visao {@code view} expirou (entregue por {@link HotStuffViewTimeoutEvent}). Timeout obsoleto
     * (a replica ja passou dessa visao) e contado em {@link DiscardReason#STALE_VIEW_TIMEOUT} e nao tem efeito;
     * o da visao corrente executa o nextView (ver a documentacao da classe). So atua depois de {@link #start()}.
     */
    public void onViewTimeout(long view) {
        if (!started) {
            throw new IllegalStateException("timeout de visao antes de start() no no " + ownId());
        }
        if (requireNode().getNodeNetworkInterface().isNetworkInterfaceDown()) {
            // no em CRASH ({@code Node#crash()}, injetado pelo teste/cenario; nao e ataque do estudo): nao executa o
            // protocolo. Sem isto o no "morto" continuaria avancando visoes localmente e se autoentregando.
            crashedTimeouts++;
            return;
        }
        runEntry(() -> execute(replica.onViewTimeout(view)));
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
        if (a.view() < replica.state().getCurrentView()) {
            // TRIPWIRE de observabilidade, nao correcao: o LeaderCore nao deveria ter devolvido esta acao (visao que a
            // propria replica ja abandonou; ver LeaderCore, "Visao abandonada pela replica"). Em execucao correta fica
            // em 0; os testes o conferem no instante do envio (antes da autoentrega, que pode avancar a visao).
            leaderActionsOnAbandonedView++;
        }
        HotStuffProposalMessage message = toProposal(a);
        if (isEquivocator() && a.phase() == HotStuffProposalMessage.Phase.PREPARE) {
            // Sem catch: precondicao violada, falha ao fabricar B ou ao enviar derrubam a simulacao.
            broadcastPrepareEquivocation(a, message);
        } else {
            if (isEquivocator()) {
                unequivocatedLeaderPhases++; // fase com QC: nao equivocada (ver javadoc); contada, nunca silenciosa
            }
            requireNode().broadcastMessage(message);
        }
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
                case FINALIZE_ANCESTOR:
                    finalizedAncestors++;
                    finalizeLocally(a.block());
                    break;
                case FINALIZE:
                    finalizeLocally(a.block());
                    break;
                case NEW_VIEW:
                    endView(a);
                    break;
                default:
                    throw new IllegalStateException("acao desconhecida: " + a.kind());
            }
        }
    }

    /**
     * Fim da visao {@code a.view()} (por DECIDE ou por timeout): NEW_VIEW ao lider de {@code a.view()+1} (Algoritmo 2,
     * linha 5 do lider seguinte; secao 4.1, nextView) com o prepareQC que a replica tinha ao terminar a visao (ou sem
     * QC se bottom, caso so do timeout) e timer da nova visao.
     */
    private void endView(ReplicaAction a) {
        long endedView = a.view();
        HotStuffQcMessage message = a.qc().isPresent()
                ? HotStuffQcMessage.newView(requireNode(), endedView, a.qc().get())
                : HotStuffQcMessage.newViewWithoutPrepareQc(requireNode(), endedView, numAllParticipants, config);
        sendNewView(endedView, message);
        armViewTimer(endedView + 1);
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
        if (isEquivocator()) {
            broadcastVoteEquivocation(vote);
            return;
        }
        requireNode().getNodeNetworkInterface().addToUpLinkQueue(
                new Packet(requireNode(), neighbor(leaderId, vote.getView()), new VoteMessage(vote)));
    }

    // ------------------------------------------------------------------------------------------------------
    // D3a-2a-2d-1: EQUIVOCATION (camada de envio; o nucleo honesto nao muda).
    // ------------------------------------------------------------------------------------------------------

    /** Este no e bizantino E o ataque configurado e EQUIVOCATION. Sem config: falso, sem efeito colateral. */
    private boolean isEquivocator() {
        return isByzantineValidator(ownId())
                && ByzantineConfig.AttackType.EQUIVOCATION.name().equals(getByzantineAttackType());
    }

    /**
     * PREPARE equivocado: A (mensagem do nucleo) aos vizinhos [0, half) e B (mesma visao e mesmo highQC; bloco
     * conflitante) aos demais. Um pacote por vizinho; nenhum para si.
     */
    private void broadcastPrepareEquivocation(LeaderAction a, HotStuffProposalMessage messageA) {
        PeerBlockchainNode<B, T> self = requireNode();
        List<Node> neighbors = self.getP2pConnections().getNeighbors();
        int half = requireEquivocationSplit(neighbors, "PREPARE");
        HotStuffBlock b = conflictingBlock(a.view(), a.block());
        HotStuffProposalMessage messageB = a.qc().isPresent()
                ? HotStuffProposalMessage.prepare(a.view(), self, b, a.qc().get(), numAllParticipants)
                : HotStuffProposalMessage.prepareWithoutHighQc(a.view(), self, b, config, numAllParticipants);
        for (int i = 0; i < neighbors.size(); i++) {
            self.getNodeNetworkInterface().addToUpLinkQueue(
                    new Packet(self, neighbors.get(i), i < half ? messageA : messageB));
        }
        equivocatedProposals++;
    }

    /**
     * Voto equivocado (so quando o lider da visao e OUTRO no): o voto no bloco real aos vizinhos [0, half) e um voto
     * da mesma fase e visao num bloco conflitante aos demais. Ver o javadoc da classe sobre o destinatario unico.
     */
    private void broadcastVoteEquivocation(HotStuffVote vote) {
        PeerBlockchainNode<B, T> self = requireNode();
        List<Node> neighbors = self.getP2pConnections().getNeighbors();
        int half = requireEquivocationSplit(neighbors, vote.getPhase().name());
        HotStuffBlock b = conflictingBlock(vote.getView(), vote.getBlock());
        VoteMessage messageA = new VoteMessage(vote);
        VoteMessage messageB = new VoteMessage(new HotStuffVote(self, vote.getPhase(), vote.getView(), b, config));
        for (int i = 0; i < neighbors.size(); i++) {
            self.getNodeNetworkInterface().addToUpLinkQueue(
                    new Packet(self, neighbors.get(i), i < half ? messageA : messageB));
        }
        equivocatedVotes++;
    }

    /**
     * Bloco conflitante com {@code real} na visao {@code view}: novo objeto (identidade e hash distintos), mesmo pai,
     * altura e tamanho, transacoes vazias (ver o javadoc da classe). O mesmo B serve a todas as fases da visao.
     */
    private HotStuffBlock conflictingBlock(long view, HotStuffBlock real) {
        for (Alternative alt : alternatives) {
            if (alt.view() == view && alt.real() == real) { // identidade: Block nao define equals
                return alt.conflicting();
            }
        }
        HotStuffBlock parent = real.getFixedParent();
        if (parent == null) {
            throw new IllegalStateException("EQUIVOCATION (no " + ownId() + "): bloco real sem pai (genesis?) na visao "
                    + view + "; nao ha como fabricar o conflito");
        }
        if (!(requireNode() instanceof HotStuffNode)) {
            throw new IllegalStateException("EQUIVOCATION so fabrica blocos em HotStuffNode");
        }
        HotStuffNode creator = (HotStuffNode) (Object) requireNode();
        HotStuffBlock conflicting = new HotStuffBlockWithTx(real.getSize(), real.getHeight(),
                requireNode().getSimulator().getSimulationTime(), creator, parent, null);
        alternatives.add(new Alternative(view, real, conflicting));
        return conflicting;
    }

    /** Poda os blocos conflitantes de alturas ja finalizadas (a lista fica limitada ao que esta em voo). */
    private void pruneAlternatives(int finalizedHeight) {
        if (!alternatives.isEmpty()) {
            alternatives.removeIf(alt -> alt.real().getHeight() <= finalizedHeight);
        }
    }

    /**
     * Valida que a rede permite equivocar: ao menos 2 vizinhos e ao menos um destinatario HONESTO em cada metade.
     * Caso contrario falha alto com mensagem clara (nunca degrada para no honesto). Vem antes de fabricar bloco ou
     * enviar pacote e nao consome aleatoriedade. Mesmo criterio do PBFT e do Tendermint.
     *
     * @return {@code half}, o tamanho da primeira metade (indices [0, half) recebem o valor A)
     * @throws IllegalStateException se a rede nao tem destinatarios honestos disjuntos suficientes
     */
    private int requireEquivocationSplit(List<Node> neighbors, String phase) {
        int size = neighbors.size();
        int selfId = ownId();
        if (size < 2) {
            throw new IllegalStateException("EQUIVOCATION em " + phase + " (no " + selfId
                    + ") exige ao menos 2 vizinhos para enviar mensagens conflitantes a destinatarios disjuntos; "
                    + "a rede tem " + size + ". Ataque nao aplicado: simulacao abortada em vez de degradar.");
        }
        int half = Math.max(1, size / 2);
        int honestFirst = 0;
        int honestSecond = 0;
        for (int i = 0; i < size; i++) {
            Node nb = neighbors.get(i);
            if (nb == null) {
                throw new IllegalStateException("EQUIVOCATION em " + phase + " (no " + selfId
                        + "): vizinho nulo no indice " + i + "; rede mal formada.");
            }
            if (!isByzantineValidator(nb.getNodeID())) {
                if (i < half) {
                    honestFirst++;
                } else {
                    honestSecond++;
                }
            }
        }
        if (honestFirst == 0 || honestSecond == 0) {
            throw new IllegalStateException("EQUIVOCATION em " + phase + " (no " + selfId
                    + ") exige ao menos 1 destinatario honesto em cada metade disjunta dos vizinhos; "
                    + "honestos na 1a metade=" + honestFirst + ", na 2a=" + honestSecond + " (vizinhos=" + size
                    + "). Ataque nao aplicado: simulacao abortada em vez de degradar para no honesto.");
        }
        return half;
    }

    /** PREPARE que este no (como lider bizantino) enviou em duas versoes conflitantes. */
    public long getEquivocatedProposals() { return equivocatedProposals; }
    /** Votos que este no (como replica bizantina) enviou em duas versoes conflitantes. */
    public long getEquivocatedVotes() { return equivocatedVotes; }
    /**
     * Fases com QC (PRE_COMMIT, COMMIT, DECIDE) que um lider equivocador enviou HONESTAS (nao equivocadas). Esperado 0
     * (inalcancavel, ver o javadoc da classe); contador para a omissao nunca ser silenciosa.
     */
    public long getUnequivocatedLeaderPhases() { return unequivocatedLeaderPhases; }

    /**
     * DECIDE: corte do trafego, retirada dos contadores e {@link BlockFinalizationEvent} como no PBFT (C_B_total e
     * C_B_consenso por no que finalizou; granularidade ADR-0006). O {@code ReplicaState} ja finalizou o bloco e
     * avancou a visao (ReplicaCore).
     */
    @SuppressWarnings("unchecked")
    private void finalizeLocally(HotStuffBlock hsBlock) {
        B block = (B) hsBlock;
        markLocallyFinalized(block); // antes do take: pacote tardio nao recomeca a conta
        pruneAlternatives(hsBlock.getHeight());
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
    /** Mensagens do lider retidas agora (visao futura ou fase adiantada). Limitada pelo horizonte do config: ver javadoc de ReplicaCore. */
    public int getPendingProposalCount() { return replica.pendingSize(); }
    /** Pico da retencao desta replica na simulacao (para o cenario relatar). */
    public int getPeakPendingProposalCount() { return replica.peakPending(); }
    /** Timeouts de visao ignorados porque este no estava em crash ({@code Node#crash()}); 0 em execucao sem crash. */
    public long getCrashedTimeouts() { return crashedTimeouts; }
    /** Blocos finalizados como ANCESTRAIS de um DECIDE (visao cujo DECIDE esta replica nao viu); 0 sem troca de visao. */
    public long getFinalizedAncestors() { return finalizedAncestors; }
    /** Quantas vezes esta replica avancou de visao por TIMEOUT (nao conta o avanco por DECIDE). */
    public long getTimeoutAdvances() { return replica.timeoutAdvances(); }
    /** Maior aninhamento de execucao de {@link LeaderAction} observado (1 = nunca reentrou). */
    public int getMaxLeaderActionDepth() { return maxLeaderActionDepth; }
    /** Acoes que o LeaderCore devolveu a uma entrada ANINHADA (voto/NEW_VIEW local) e que esperaram a vez (nao reentraram). */
    public long getDeferredLeaderActions() { return deferredLeaderActions; }
    /**
     * Acoes do lider executadas numa visao MENOR que a visao corrente da propria replica no instante do envio (isto
     * e, de visao abandonada). Tripwire de teste: 0 em execucao correta.
     */
    public long getLeaderActionsOnAbandonedView() { return leaderActionsOnAbandonedView; }
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
