package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
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
import jabs.metrics.NetworkHeightLedger;
import jabs.network.message.Packet;
import jabs.network.message.TendermintProposalMessage;
import jabs.network.message.TendermintVoteMessage;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.TendermintTimeoutEvent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * (aqui: bloco presente, conectado ao genesis, com altura {@code h + 1} e pai igual a decisao da altura anterior,
 * ver {@link #isValidValue}) tenha a mesma resposta em todos os nos corretos. Proposta invalida e entregue ao
 * nucleo, que prevota nil e a conta em {@code INVALID_VALUE}. Bloco proposto
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
 * <p><b>Byzantinos (D3b-tendermint-equivocation).</b> Implementa UM ataque, {@code EQUIVOCATION};
 * {@code SILENT}, {@code WITHHOLD} e {@code DOUBLE_SIGN} continuam fora de {@link #supportedAttacks()} e falham alto em
 * {@code setByzantineConfig} (nunca viram no honesto em silencio). O no bizantino roda o MESMO
 * {@link TendermintCore} honesto para o seu estado interno (o nucleo nao foi alterado); a equivocacao fica so na
 * camada de envio, ao executar {@code BROADCAST_PROPOSAL} / {@code BROADCAST_VOTE}: em vez de um unico pacote para
 * todos, saem DOIS valores conflitantes, cada um a um subconjunto disjunto dos vizinhos, pelo mesmo caminho do
 * PBFT ({@code addToUpLinkQueue(new Packet(...))} por vizinho, na fila de upload do no). Os pacotes sao contados
 * como trafego normal por {@code PeerBlockchainNode} no receptor; o bizantino nao envia nem conta autoentrega.
 *
 * <p><b>Definicao e o que e PROVISORIO.</b> A definicao aceita e a de ADR-0010 PI-12: "um no bizantino emite duas
 * mensagens conflitantes, cada uma a um subconjunto disjunto de nos honestos". Tamanho/escolha dos subconjuntos,
 * abrangencia e coordenacao continuam {@code a definir com o humano} (ADR-0015, Q28; B-34, B-35, B-36). Ate a
 * resposta, estas tres escolhas sao PROVISORIAS e replicam o modelo ja usado no PBFT, para os resultados serem
 * comparaveis e a troca ser trivial depois:
 * <ul>
 *   <li><b>Particao (B-34):</b> deterministica em duas metades por INDICE dos vizinhos, {@code half = max(1, size/2)};
 *       indices [0, half) recebem o valor A e [half, size) o valor B. Na malha completa do Tendermint os vizinhos
 *       estao em ordem crescente de id, sem o proprio no. Pre-condicao verificada de forma explicita
 *       ({@link #requireEquivocationSplit}): ao menos 2 vizinhos e ao menos 1 destinatario HONESTO em cada metade,
 *       senao {@link IllegalStateException} (como o PBFT).</li>
 *   <li><b>Abrangencia (B-35):</b> o equivocador conflita nas mensagens que ele ORIGINA e que carregam valor:
 *       a PROPOSTA quando e o proponente do round, e o PREVOTE e o PRECOMMIT que emite. Voto nil e timeouts nao
 *       sao equivocados. ASSIMETRIA com o PBFT: la o ataque cobre PRE_PREPARE do primario e COMMIT, mas nao o
 *       PREPARE; aqui cobre proposta e as duas fases de voto.</li>
 *   <li><b>Coordenacao (B-36):</b> nenhuma. Cada bizantino decide sozinho; todos usam a mesma particao por indice
 *       e fabricam o seu proprio bloco B.</li>
 * </ul>
 * O bloco B e fabricado por {@link BlockFactory#sampleTendermintBlockWithTx}: mesmo pai e mesma altura do bloco real
 * A, identidade e hash distintos, tamanho igual, e VALIDO para a altura ({@link #isValidValue} so olha presenca na
 * arvore, conexao ao genesis, altura e pai), de modo que um honesto que o recebe como proposta o aceita e o conflito
 * e observavel como conflito e nao como bloco rejeitado. Nao consome aleatoriedade. Para um mesmo
 * (altura, round, bloco real) o mesmo B serve a proposta, ao prevote e ao precommit, de modo que cada metade ve um
 * mundo consistente (A, ou B). A proposta B leva {@code validRound = -1} (bloco novo, sem polka), mesmo quando A
 * e uma re-proposta. Quando o bizantino vota um bloco proposto por OUTRO no, o B do voto e um bloco que ninguem
 * propos (o voto so carrega o id), e seu trafego fica atribuido a um bloco que nunca e finalizado.
 *
 * <p><b>Gossip.</b> O adaptador NAO repassa mensagens de outros nos (fora do escopo desta tarefa). Com equivocacao
 * uma metade pode ficar sem a proposta que a outra decidiu; ver o relato de liveness no teste
 * {@code TendermintEquivocationTest}. Isto e consequencia do modelo (broadcast direto) e nao foi "corrigido" aqui.
 */
public class Tendermint<B extends SingleParentBlock<B>, T extends Tx<T>> extends AbstractChainBasedConsensus<B, T> {
    private final TendermintConsensusConfig config;
    private final int numAllParticipants;
    private final TendermintCore<TendermintBlock> core;

    private final HashMap<B, Long> blockTraffic = new HashMap<>();
    private final HashMap<B, Long> consensusTraffic = new HashMap<>();
    private final HashSet<B> locallyFinalized = new HashSet<>();
    private long unattributedTraffic = 0L;
    // HS-CB-fase2 (parte A): acumulador POR ALTURA, SEM corte local e nunca zerado, independente dos contadores acima
    // (chave long; ver NetworkHeightLedger). Nao altera o C_B do primeiro no a finalizar.
    private final NetworkHeightLedger heightLedger = new NetworkHeightLedger();

    /** Chave do bloco alternativo (B) fabricado para o equivocador: (altura, round, bloco real A). */
    private record AlternativeKey(long height, int round, TendermintBlock real) { }

    /**
     * B por (altura, round, A). So leitura/escrita por chave (nunca se itera para decidir comportamento) e
     * {@code LinkedHashMap} por garantia. Vazio em no honesto. Podado ao decidir.
     */
    private final Map<AlternativeKey, TendermintBlock> equivocationAlternatives = new LinkedHashMap<>();

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
                TendermintCore.roundRobin(numAllParticipants), this::proposeValue, this::isValidValue,
                config.getMaxFutureHorizon());
    }

    /**
     * Unico ataque implementado: {@code EQUIVOCATION} (ver o Javadoc da classe; particao, abrangencia e coordenacao
     * PROVISORIAS, ADR-0010 PI-12 e ADR-0015 Q28). {@code SILENT}, {@code WITHHOLD} e {@code DOUBLE_SIGN} nao tem
     * hook aqui e sao recusados por {@code setByzantineConfig}.
     */
    @Override
    protected Set<ByzantineConfig.AttackType> supportedAttacks() {
        return EnumSet.of(ByzantineConfig.AttackType.EQUIVOCATION);
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

    /**
     * {@code valid(h, v)} do Algoritmo 1 para a altura de consenso {@code height} (a do nucleo, nunca a da
     * mensagem). Convencao de alturas: a altura 0 do nucleo propoe o primeiro bloco, filho do genesis, com
     * {@code block.getHeight() == 1} (genesis tem altura 0; {@code BlockFactory} usa {@code parent.getHeight() + 1}).
     * Logo, na altura {@code h} do nucleo:
     * <ol>
     *   <li>bloco nao nulo, presente na arvore local e conectado ao genesis;</li>
     *   <li>{@code block.getHeight() == h + 1};</li>
     *   <li>pai (campo imutavel {@link TendermintBlock#getFixedParent()}, nao a lista mutavel
     *       {@code getParents()}) e a mesma referencia do genesis se {@code h == 0}, ou da decisao
     *       {@code core.decision(h - 1)} se {@code h > 0}.</li>
     * </ol>
     * Sem decisao local em {@code h - 1} o valor e invalido (nao ocorre: o nucleo so chega a {@code h} depois de
     * decidir {@code h - 1}). Valor invalido recebe prevote nil no nucleo e e contado como
     * {@code INVALID_VALUE}; nao lanca excecao. Funcao pura sobre o estado local: igual em todos os nos corretos
     * que decidiram a mesma cadeia.
     */
    private boolean isValidValue(long height, TendermintBlock block) {
        if (block == null) {
            return false;
        }
        B local = castBlock(block);
        if (!localBlockTree.contains(local)) {
            return false;
        }
        LocalBlock<B> localBlock = localBlockTree.getLocalBlock(local);
        if (localBlock == null || !localBlock.isConnectedToGenesis) {
            return false;
        }
        if ((long) block.getHeight() != height + 1) {
            return false;
        }
        TendermintBlock expectedParent = height == 0
                ? (TendermintBlock) localBlockTree.getGenesisBlock()
                : core.decision(height - 1);
        return expectedParent != null && block.getFixedParent() == expectedParent;
    }

    // ------------------------------------------------------------------------------------------------------
    // Execucao das acoes
    // ------------------------------------------------------------------------------------------------------

    private void execute(List<TendermintAction<TendermintBlock>> actions) {
        for (TendermintAction<TendermintBlock> action : actions) {
            switch (action.kind()) {
                case BROADCAST_PROPOSAL:
                    TendermintProposal<TendermintBlock> proposal = action.proposal();
                    if (isEquivocator()) {
                        // Sem catch: falha de envio, de fabricacao do bloco B ou rede sem destinatarios honestos
                        // disjuntos propaga e derruba a simulacao, em vez de o ataque sumir em silencio.
                        broadcastProposalEquivocation(proposal);
                    } else {
                        requireNode().broadcastMessage(new TendermintProposalMessage(requireNode(),
                                proposal.height(), proposal.round(), proposal.value(), proposal.validRound(), config));
                    }
                    break;
                case BROADCAST_VOTE:
                    TendermintVote<TendermintBlock> vote = action.vote();
                    if (isEquivocator() && !vote.isNil()) {
                        // Voto nil nao carrega valor: nao ha o que conflitar (provisorio, Q28/B-35).
                        broadcastVoteEquivocation(vote);
                    } else {
                        requireNode().broadcastMessage(new TendermintVoteMessage(requireNode(), vote.type(),
                                vote.height(), vote.round(), vote.value(), config));
                    }
                    break;
                case SCHEDULE_TIMEOUT:
                    double delay = config.getTimeouts().durationSeconds(action.timeout(), action.round());
                    requireNode().getSimulator().putEvent(new TendermintTimeoutEvent(this, action.timeout(),
                            action.height(), action.round()), delay);
                    break;
                case DECIDE:
                    finalizeLocally(action.value());
                    pruneEquivocationAlternatives(action.height());
                    break;
                default:
                    throw new IllegalStateException("acao desconhecida: " + action.kind());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Equivocacao (camada de envio; o nucleo honesto nao muda)
    // ------------------------------------------------------------------------------------------------------

    /** Este no e bizantino E o ataque configurado e EQUIVOCATION. Sem config: falso, sem efeito colateral. */
    private boolean isEquivocator() {
        return isByzantineValidator(requireNode().nodeID)
                && ByzantineConfig.AttackType.EQUIVOCATION.name().equals(getByzantineAttackType());
    }

    /**
     * Proposta equivocada: A (o bloco real do nucleo) aos vizinhos [0, half) e B (alternativo valido) aos demais.
     * Um pacote por vizinho; nenhum para si.
     */
    private void broadcastProposalEquivocation(TendermintProposal<TendermintBlock> proposal) {
        PeerBlockchainNode<B, T> self = requireNode();
        List<Node> neighbors = self.getP2pConnections().getNeighbors();
        int half = requireEquivocationSplit(neighbors, "PROPOSAL");
        TendermintBlock a = proposal.value();
        TendermintBlock b = alternativeFor(proposal.height(), proposal.round(), a);
        TendermintProposalMessage messageA = new TendermintProposalMessage(self, proposal.height(),
                proposal.round(), a, proposal.validRound(), config);
        // B e um bloco novo, que nunca teve polka: a unica afirmacao coerente e validRound = -1 (proposta fresca).
        // Com o validRound de A (re-proposta), os honestos exigiriam uma polka de B que nao existe e ignorariam B.
        TendermintProposalMessage messageB = new TendermintProposalMessage(self, proposal.height(),
                proposal.round(), b, -1, config);
        for (int i = 0; i < neighbors.size(); i++) {
            self.getNodeNetworkInterface().addToUpLinkQueue(
                    new Packet(self, neighbors.get(i), i < half ? messageA : messageB));
        }
    }

    /** Voto (PREVOTE ou PRECOMMIT) equivocado sobre um valor nao nil: A aos vizinhos [0, half), B aos demais. */
    private void broadcastVoteEquivocation(TendermintVote<TendermintBlock> vote) {
        PeerBlockchainNode<B, T> self = requireNode();
        List<Node> neighbors = self.getP2pConnections().getNeighbors();
        String phase = vote.type().name();
        int half = requireEquivocationSplit(neighbors, phase);
        TendermintBlock a = vote.value();
        TendermintBlock b = alternativeFor(vote.height(), vote.round(), a);
        TendermintVoteMessage messageA = new TendermintVoteMessage(self, vote.type(), vote.height(), vote.round(),
                a, config);
        TendermintVoteMessage messageB = new TendermintVoteMessage(self, vote.type(), vote.height(), vote.round(),
                b, config);
        for (int i = 0; i < neighbors.size(); i++) {
            self.getNodeNetworkInterface().addToUpLinkQueue(
                    new Packet(self, neighbors.get(i), i < half ? messageA : messageB));
        }
    }

    /**
     * Bloco B conflitante com {@code real}: mesmo pai e mesma altura (logo valido na mesma altura), identidade e hash
     * distintos, mesmo tamanho (lote cheio). Nao consome aleatoriedade. O mesmo B e reutilizado para o mesmo
     * (altura, round, A) na proposta, no prevote e no precommit.
     */
    private TendermintBlock alternativeFor(long height, int round, TendermintBlock real) {
        AlternativeKey key = new AlternativeKey(height, round, real);
        TendermintBlock cached = equivocationAlternatives.get(key);
        if (cached != null) {
            return cached;
        }
        TendermintBlock parent = real.getFixedParent();
        if (parent == null) {
            throw new IllegalStateException("EQUIVOCATION (no " + requireNode().nodeID
                    + "): bloco real sem pai (genesis?) na altura " + height + "; nao ha como fabricar o conflito");
        }
        TendermintBlock alternative = BlockFactory.sampleTendermintBlockWithTx(
                requireNode().getSimulator(), requireTendermintNode(), parent, config);
        if (alternative == real || alternative.getHeight() != real.getHeight()) {
            throw new IllegalStateException("EQUIVOCATION: bloco alternativo nao e distinto do real na mesma altura");
        }
        equivocationAlternatives.put(key, alternative);
        return alternative;
    }

    private void pruneEquivocationAlternatives(long decidedHeight) {
        if (!equivocationAlternatives.isEmpty()) {
            equivocationAlternatives.keySet().removeIf(k -> k.height() <= decidedHeight);
        }
    }

    /**
     * Valida que a rede permite equivocar: ao menos 2 vizinhos (duas metades nao vazias) e ao menos um destinatario
     * HONESTO em cada metade. Caso contrario falha alto com mensagem clara, em vez de degradar o ataque para
     * comportamento honesto ou para um conflito que so bizantinos veem. A checagem vem antes de qualquer fabricacao
     * ou envio e nao consome aleatoriedade. Mesmo criterio do PBFT.
     *
     * @return {@code half}, o tamanho da primeira metade (indices [0, half) recebem o valor A)
     * @throws IllegalStateException se a rede nao tem destinatarios honestos disjuntos suficientes
     */
    private int requireEquivocationSplit(List<Node> neighbors, String phase) {
        int size = neighbors.size();
        int selfId = requireNode().nodeID;
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
            if (!isByzantineValidator(nb.nodeID)) {
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
                    + "honestos na 1a metade=" + honestFirst + ", na 2a=" + honestSecond + " (n=" + size
                    + "). Ataque nao aplicado: simulacao abortada em vez de degradar para no honesto.");
        }
        return half;
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
        heightLedger.addBlockPacket(block.getHeight(), bytes); // sem corte local (HS-CB-fase2)
        if (locallyFinalized.contains(block)) {
            return;
        }
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    public void addConsensusMessageTraffic(B block, long bytes) {
        requireTraffic(block, bytes);
        heightLedger.addConsensusBytes(block.getHeight(), bytes); // sem corte local (HS-CB-fase2)
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
            heightLedger.addUnattributed(packetBytes); // (ii) provisoria; contador que take nao zera
            return;
        }
        B block = castBlock(message.getBlock());
        addBlockTraffic(block, packetBytes);
        addConsensusMessageTraffic(block, packetBytes);
    }

    /** HS-CB-fase2 (parte A): acumulador por altura deste no (sem corte local, nunca zerado); o protocolo nao o le. */
    public NetworkHeightLedger getHeightLedger() {
        return heightLedger;
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
        heightLedger.markFinalized(block.getHeight()); // so rotula (i) no acumulador por altura; o corte nao muda
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
