package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.ledgerdata.*;
import jabs.ledgerdata.pbft.*;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.event.BlockFinalizationEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

// based on: https://sawtooth.hyperledger.org/docs/pbft/nightly/master/architecture.html
// another good source: http://ug93tad.github.io/pbft/

public class PBFT<B extends SingleParentBlock<B>, T extends Tx<T>> extends AbstractChainBasedConsensus<B, T>
        implements VotingBasedConsensus<B, T>, DeterministicFinalityConsensus<B, T> {
    private final int numAllParticipants;
    private final HashMap<B, HashMap<Node, Vote>> prepareVotes = new HashMap<>();
    private final HashMap<B, HashMap<Node, Vote>> commitVotes = new HashMap<>();
    private final HashSet<B> preparedBlocks = new HashSet<>();
    // ADR-0006 (tarefa 2, rodada 2). Pre-condicoes de fase (Castro & Liskov): um PREPARE so conta para o bloco
    // se este no ja ACEITOU o PRE_PREPARE dele; um COMMIT so conta se o no ja esta PREPARED para o bloco.
    // Aceito = PRE_PREPARE recebido e bloco conectado ao genesis (a mesma condicao sob a qual o no emite o seu PREPARE).
    private final HashSet<B> acceptedPrePrepares = new HashSet<>();
    // Politica escolhida para voto ADIANTADO (a rede simulada pode entregar PREPARE/COMMIT antes do PRE_PREPARE
    // ou antes de o no estar prepared): o voto e RETIDO aqui, nunca descartado, e reavaliado quando a
    // pre-condicao passa a valer. Mapa voter -> voto (duplicata do mesmo no nao conta duas vezes, como nos mapas
    // de votos efetivos). LinkedHashMap: reavaliacao em ordem de chegada, deterministica. Os HashMap de blocos so
    // sofrem get/put/remove por chave, nunca iteracao. Memoria: um voto por (bloco, no); as entradas de um bloco
    // sao liberadas ao reavalia-las e ao finalizar. Blocos que nunca recebem PRE_PREPARE (voto bizantino) ficam
    // retidos ate o fim da simulacao: custo aceito, pois view change nao esta implementado (TODO abaixo).
    private final HashMap<B, LinkedHashMap<Node, PBFTBlockVote<B>>> pendingPrepares = new HashMap<>();
    private final HashMap<B, LinkedHashMap<Node, PBFTBlockVote<B>>> pendingCommits = new HashMap<>();
    private final HashSet<B> committedBlocks = new HashSet<>();
    private int currentViewNumber = 0;
    // ADR-0006 (tarefa 2). Quorum teorico de Castro & Liskov: com n >= 3f+1, f = floor((n-1)/3) e quorum = 2f+1.
    // Antes: (n/3)*2+1 com comparacao estrita '>', que exigia 2f+2 votos quando n = 3f+1 (n=4 exigia 4; n=7 exigia 6).
    private final int maxFaulty;
    private final int requiredVotes;

    // ADR-0006 (tarefa 1). Trafego MEDIDO, convencao "trafego recebido por no": cada instancia de PBFT
    // pertence a um unico no e so acumula bytes de pacotes que ESTE no recebeu (packet.getSize()),
    // vindos de PeerBlockchainNode#processIncomingPacket. Nenhuma formula em n, nenhum ruido.
    // C_B_total: tudo o que o no recebeu sobre o bloco (PRE_PREPARE inclui a carga do bloco).
    private final HashMap<B, Long> blockTraffic = new HashMap<>();
    // C_B_consenso: so mensagens do protocolo de votos (PRE_PREPARE sem a carga do bloco, PREPARE e COMMIT inteiros).
    private final HashMap<B, Long> consensusTraffic = new HashMap<>();

    // TODO: View change should be implemented

    private PBFTMode pbftMode = PBFTMode.NORMAL_MODE;
    private PBFTPhase pbftPhase = PBFTPhase.PRE_PREPARING;

    @Override
    public boolean isBlockFinalized(B block) {
        return false;
    }

    @Override
    public boolean isTxFinalized(T tx) {
        return false;
    }

    @Override
    public int getNumOfFinalizedBlocks() {
        return 0;
    }

    @Override
    public int getNumOfFinalizedTxs() {
        return 0;
    }

    public enum PBFTMode {
        NORMAL_MODE,
        VIEW_CHANGE_MODE
    }

    public enum PBFTPhase {
        PRE_PREPARING,
        PREPARING,
        COMMITTING
    }

    public PBFT(LocalBlockTree<B> localBlockTree, int numAllParticipants) {
        super(localBlockTree);
        this.numAllParticipants = numAllParticipants;
        this.maxFaulty = maxFaultyNodes(numAllParticipants);
        this.requiredVotes = quorumSize(numAllParticipants);
        this.currentMainChainHead = localBlockTree.getGenesisBlock();
    }

    /**
     * Ataques com ramo proprio no PBFT (auditoria A.1): SILENT, WITHHOLD, EQUIVOCATION e DOUBLE_SIGN.
     * Cobertura parcial declarada: PREPARE nao tem ramo; DOUBLE_SIGN so existe no COMMIT.
     */
    @Override
    protected java.util.Set<jabs.config.ByzantineConfig.AttackType> supportedAttacks() {
        return java.util.EnumSet.of(jabs.config.ByzantineConfig.AttackType.SILENT,
                jabs.config.ByzantineConfig.AttackType.WITHHOLD,
                jabs.config.ByzantineConfig.AttackType.EQUIVOCATION,
                jabs.config.ByzantineConfig.AttackType.DOUBLE_SIGN);
    }

    /** f = floor((n-1)/3): maximo de nos bizantinos tolerados com n participantes (0 se n < 4). */
    public static int maxFaultyNodes(int numAllParticipants) {
        return Math.max(0, (numAllParticipants - 1) / 3);
    }

    /**
     * Quorum teorico 2f+1 (f = floor((n-1)/3)): n=4 -> 3, n=7 -> 5, n=10 -> 7.
     * Usado com {@code >=} em {@link #checkVotes} nas fases PREPARE e COMMIT.
     */
    public static int quorumSize(int numAllParticipants) {
        return 2 * maxFaultyNodes(numAllParticipants) + 1;
    }

    /** Numero de votos (nos distintos) exigido por fase: 2f+1. */
    public int getRequiredVotes() {
        return requiredVotes;
    }

    /** f usado no calculo do quorum. */
    public int getMaxFaulty() {
        return maxFaulty;
    }

    /** O bloco atingiu o quorum de PREPARE neste no. */
    public boolean isPrepared(B block) {
        return preparedBlocks.contains(block);
    }

    /** Este no recebeu e aceitou o PRE_PREPARE do bloco (pre-condicao para contar PREPAREs). */
    public boolean hasAcceptedPrePrepare(B block) {
        return acceptedPrePrepares.contains(block);
    }

    /** Votos PREPARE e COMMIT retidos (adiantados) para o bloco, aguardando a pre-condicao de fase. */
    public int getPendingVoteCount(B block) {
        LinkedHashMap<Node, PBFTBlockVote<B>> p = pendingPrepares.get(block);
        LinkedHashMap<Node, PBFTBlockVote<B>> c = pendingCommits.get(block);
        return (p == null ? 0 : p.size()) + (c == null ? 0 : c.size());
    }

    /** O bloco atingiu o quorum de COMMIT (finalizado localmente) neste no. */
    public boolean isCommitted(B block) {
        return committedBlocks.contains(block);
    }

    /**
     * Acumula C_B_total (bytes recebidos por este no, incluindo a carga do bloco no PRE_PREPARE).
     * Chamado por {@link jabs.network.node.nodes.PeerBlockchainNode#processIncomingPacket} com
     * {@code packet.getSize()}. Pacotes que chegam depois da finalizacao local do bloco nao entram:
     * a metrica e "ate a finalizacao" e isso evita acumulo sem fim para blocos ja finalizados.
     */
    public void addBlockTraffic(B block, long bytes) {
        if (committedBlocks.contains(block)) {
            return;
        }
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    /**
     * Acumula C_B_consenso (bytes recebidos por este no que pertencem so ao protocolo de votos,
     * sem a carga do bloco carregada no PRE_PREPARE). Mesma regra de corte de {@link #addBlockTraffic}.
     */
    public void addConsensusMessageTraffic(B block, long bytes) {
        if (committedBlocks.contains(block)) {
            return;
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

    public void newIncomingVote(Vote vote) {
        if (vote instanceof PBFTBlockVote) { // for the time being, the view change votes are not supported
            PBFTBlockVote<B> blockVote = (PBFTBlockVote<B>) vote;
            B block = blockVote.getBlock();
            switch (blockVote.getVoteType()) {
                case PRE_PREPARE :
                    if (!this.localBlockTree.contains(block)) {
                        this.localBlockTree.add(block);
                    }
                    if (this.localBlockTree.getLocalBlock(block).isConnectedToGenesis) {
                        boolean firstAcceptance = acceptedPrePrepares.add(block);
                        this.pbftPhase = PBFTPhase.PREPARING;
                        this.peerBlockchainNode.broadcastMessage(
                                new VoteMessage(
                                        new PBFTPrepareVote<>(this.peerBlockchainNode, blockVote.getBlock())
                                )
                        );
                        if (firstAcceptance) {
                            // PREPAREs que chegaram antes do PRE_PREPARE sao reavaliados agora, sem perder voto.
                            LinkedHashMap<Node, PBFTBlockVote<B>> early = pendingPrepares.remove(block);
                            if (early != null) {
                                for (PBFTBlockVote<B> v : early.values()) {
                                    countPrepare(v, block);
                                }
                            }
                        }
                    }
                    break;
                case PREPARE:
                    if (acceptedPrePrepares.contains(block)) {
                        countPrepare(blockVote, block);
                    } else {
                        pendingPrepares.computeIfAbsent(block, k -> new LinkedHashMap<>())
                                .put(blockVote.getVoter(), blockVote);
                    }
                    break;
                case COMMIT:
                    if (preparedBlocks.contains(block)) {
                        checkVotes(blockVote, block, commitVotes, committedBlocks, PBFTPhase.PRE_PREPARING);
                    } else {
                        pendingCommits.computeIfAbsent(block, k -> new LinkedHashMap<>())
                                .put(blockVote.getVoter(), blockVote);
                    }
                    break;
            }
        }
    }

    /**
     * Conta um PREPARE de bloco cujo PRE_PREPARE ja foi aceito. Ao atingir o quorum o no fica prepared, emite o
     * seu COMMIT (dentro de {@link #checkVotes}) e so entao reavalia os COMMITs que chegaram adiantados.
     */
    private void countPrepare(PBFTBlockVote<B> vote, B block) {
        boolean wasPrepared = preparedBlocks.contains(block);
        checkVotes(vote, block, prepareVotes, preparedBlocks, PBFTPhase.COMMITTING);
        if (!wasPrepared && preparedBlocks.contains(block)) {
            LinkedHashMap<Node, PBFTBlockVote<B>> early = pendingCommits.remove(block);
            if (early != null) {
                for (PBFTBlockVote<B> v : early.values()) {
                    checkVotes(v, block, commitVotes, committedBlocks, PBFTPhase.PRE_PREPARING);
                }
            }
        }
    }

    /**
     * Conta votos por voter distinto (mapa voter -> voto: duplicata do mesmo no nao conta duas vezes) e
     * avanca de fase ao atingir o quorum 2f+1 com {@code >=}. Vale para PREPARE e COMMIT.
     *
     * Contagem adotada (ADR-0006, tarefa 2):
     *  - O broadcast de {@code Node#broadcastMessage} inclui o proprio no entre os vizinhos (PBFTP2P adiciona
     *    todos os nos da rede), logo o proprio voto chega a si mesmo e ENTRA no mapa. Quorum = 2f+1 votos de nos
     *    distintos, incluindo o proprio.
     *  - PREPARE: o PRE_PREPARE nao e contado como voto separado. Ele entra de forma implicita: so envia PREPARE
     *    quem recebeu e aceitou o PRE_PREPARE, e o primario tambem envia o seu PREPARE (recebe o proprio
     *    PRE_PREPARE). Assim "pre-prepare + 2f prepares" do artigo equivale aqui a 2f+1 PREPAREs distintos,
     *    sem contar o PRE_PREPARE duas vezes.
     *  - COMMIT: 2f+1 COMMITs distintos, incluindo o proprio.
     *
     * Pre-condicoes (rodada 2): para PREPARE este metodo so e chamado depois de o PRE_PREPARE do bloco ter sido
     * aceito, e para COMMIT depois de o no estar prepared para o bloco (ver {@link #newIncomingVote}); votos
     * adiantados ficam retidos em pendingPrepares/pendingCommits e voltam aqui quando a pre-condicao vale.
     */
    private void checkVotes(PBFTBlockVote<B> vote, B block, HashMap<B, HashMap<Node, Vote>> votes, HashSet<B> blocks, PBFTPhase nextStep) {
        if (!blocks.contains(block)) {
            if (!votes.containsKey(block)) { // this the first vote received for this block
                votes.put(block, new HashMap<>());
            }
            votes.get(block).put(vote.getVoter(), vote);
            int currentVotes = votes.get(block).size();

            if (currentVotes >= this.requiredVotes) {
                blocks.add(block);
                this.pbftPhase = nextStep;
                switch (nextStep) {
                    case PRE_PREPARING:
                        this.currentViewNumber += 1;
                        this.currentMainChainHead = block;
                        updateChain();
        
                        // Fire BlockFinalizationEvent for metrics collection
                        if (this.peerBlockchainNode != null && this.peerBlockchainNode.getSimulator() != null) {
                            // Clear vote maps for finalized block to prevent memory leaks
                            prepareVotes.remove(block);
                            commitVotes.remove(block);
                            pendingPrepares.remove(block);
                            pendingCommits.remove(block);
                            // ADR-0006: trafego MEDIDO (bytes recebidos por este no ate a finalizacao local),
                            // decomposto em C_B_total e C_B_consenso. Sentinela -1 nao e usado aqui: 0 e 0 medido.
                            long totalTraffic = blockTraffic.getOrDefault(block, 0L);
                            long consensusOnlyTraffic = consensusTraffic.getOrDefault(block, 0L);
                            blockTraffic.remove(block);
                            consensusTraffic.remove(block);
                            this.peerBlockchainNode.getSimulator().putEvent(
                                new BlockFinalizationEvent(
                                    this.peerBlockchainNode.getSimulator().getSimulationTime(),
                                    this.peerBlockchainNode,
                                    block,
                                    totalTraffic,
                                    consensusOnlyTraffic
                                ),
                                0
                            );
                        }
                        
                        if (this.peerBlockchainNode.nodeID == this.getCurrentPrimaryNumber()){
                            // Check Byzantine behavior
                            boolean isByz = isByzantineValidator(this.peerBlockchainNode.nodeID);
                            String attack = (byzantineConfig == null) ? null : byzantineConfig.getAttackType();
                            if (isByz && attack != null) attack = attack.toUpperCase();

                            if (isByz && "SILENT".equals(attack)) {
                                // Do not broadcast anything
                            } else if (isByz && "WITHHOLD".equals(attack)) {
                                // Withhold pre-prepare: do nothing
                            } else if (isByz && "EQUIVOCATION".equals(attack)) {
                                // Send conflicting pre-prepare messages to different halves of neighbors.
                                // Sem catch: falha de envio ou rede sem destinatarios honestos disjuntos propaga (criterio 4).
                                broadcastEquivocation(block, true);
                            } else {
                                // Normal broadcast
                                this.peerBlockchainNode.broadcastMessage(
                                        new VoteMessage(
                                                new PBFTPrePrepareVote<>(this.peerBlockchainNode,
                                                        BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(),
                                                                peerBlockchainNode.getNetwork().getRandom(),
                                                                (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50)
                                                )
                                        )
                                );
                            }
                        }
                        break;
                    case COMMITTING:
                        // Commit broadcasting may be manipulated by Byzantine nodes
                        boolean isByzCommit = isByzantineValidator(this.peerBlockchainNode.nodeID);
                        String attackCommit = (byzantineConfig == null) ? null : byzantineConfig.getAttackType();
                        if (isByzCommit && attackCommit != null) attackCommit = attackCommit.toUpperCase();

                        if (isByzCommit && "SILENT".equals(attackCommit)) {
                            // do nothing
                        } else if (isByzCommit && "WITHHOLD".equals(attackCommit)) {
                            // withhold commit
                        } else if (isByzCommit && "EQUIVOCATION".equals(attackCommit)) {
                            // Sem catch: mesma regra do ramo PRE_PREPARE (falha propaga, criterio 4).
                            broadcastEquivocation(block, false);
                        } else if (isByzCommit && "DOUBLE_SIGN".equals(attackCommit)) {
                            // Broadcast two conflicting commits quickly
                            this.peerBlockchainNode.broadcastMessage(new VoteMessage(new PBFTCommitVote<>(this.peerBlockchainNode, block)));
                            this.peerBlockchainNode.broadcastMessage(new VoteMessage(new PBFTCommitVote<>(this.peerBlockchainNode, block)));
                        } else {
                            this.peerBlockchainNode.broadcastMessage(
                                    new VoteMessage(
                                            new PBFTCommitVote<>(this.peerBlockchainNode, block)
                                    )
                            );
                        }
                        break;
                }
            }
        }
    }

    /**
     * EQUIVOCATION (PRE_PREPARE do primario ou COMMIT): envia DOIS votos conflitantes (sobre dois blocos distintos
     * amostrados) a metades disjuntas dos vizinhos: indices [0, half) recebem o bloco A e [half, n) o bloco B,
     * com {@code half = max(1, n/2)}. Semantica identica a anterior; a diferenca e que NADA e engolido: nenhum
     * {@code catch}, entao falha de envio, de cast ou de amostragem propaga e derruba a simulacao em vez de
     * deixar o ataque deixar de ser aplicado em silencio (criterio 4).
     *
     * Pre-condicao verificada de forma explicita e deterministica em {@link #requireEquivocationSplit}: cada metade
     * precisa ter ao menos um destinatario honesto, senao o conflito nao seria observavel por ninguem honesto e
     * o ataque degradaria para algo diferente de equivocacao. Como a checagem vem antes de qualquer sorteio ou
     * envio, ela nao consome aleatoriedade: execucoes validas continuam com a mesma saida para a mesma semente.
     */
    @SuppressWarnings("unchecked")
    private void broadcastEquivocation(B block, boolean prePrepare) {
        String phase = prePrepare ? "PRE_PREPARE" : "COMMIT";
        java.util.List<Node> neighbors = this.peerBlockchainNode.getP2pConnections().getNeighbors();
        int half = requireEquivocationSplit(neighbors, phase);
        PBFTBlock a = BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(), peerBlockchainNode.getNetwork().getRandom(), (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50);
        PBFTBlock b = BlockFactory.samplePBFTBlockWithTx(peerBlockchainNode.getSimulator(), peerBlockchainNode.getNetwork().getRandom(), (PBFTNode) this.peerBlockchainNode, (PBFTBlock) block, 50);
        for (int i = 0; i < neighbors.size(); i++) {
            Node nb = neighbors.get(i);
            B chosen = (B) ((i < half) ? a : b);
            Vote vote = prePrepare
                    ? new PBFTPrePrepareVote<>(this.peerBlockchainNode, chosen)
                    : new PBFTCommitVote<>(this.peerBlockchainNode, chosen);
            this.peerBlockchainNode.getNodeNetworkInterface().addToUpLinkQueue(
                    new jabs.network.message.Packet(this.peerBlockchainNode, nb, new VoteMessage(vote)));
        }
    }

    /**
     * Valida que a rede permite equivocar: ao menos 2 vizinhos (para haver duas metades nao vazias) e ao menos
     * um destinatario HONESTO em cada metade. Caso contrario falha alto com mensagem clara, em vez de degradar
     * o ataque para comportamento honesto ou para um conflito que so bizantinos veem.
     *
     * @return {@code half}, o tamanho da primeira metade (indices [0, half) recebem o bloco A)
     * @throws IllegalStateException se a rede nao tem destinatarios honestos disjuntos suficientes
     */
    private int requireEquivocationSplit(java.util.List<Node> neighbors, String phase) {
        int size = neighbors.size();
        if (size < 2) {
            throw new IllegalStateException("EQUIVOCATION em " + phase + " (no " + this.peerBlockchainNode.nodeID
                    + ") exige ao menos 2 vizinhos para enviar votos conflitantes a destinatarios disjuntos; "
                    + "a rede tem " + size + ". Ataque nao aplicado: simulacao abortada em vez de degradar.");
        }
        int half = Math.max(1, size / 2);
        int honestFirst = 0;
        int honestSecond = 0;
        for (int i = 0; i < size; i++) {
            Node nb = neighbors.get(i);
            if (nb == null) {
                throw new IllegalStateException("EQUIVOCATION em " + phase + " (no " + this.peerBlockchainNode.nodeID
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
            throw new IllegalStateException("EQUIVOCATION em " + phase + " (no " + this.peerBlockchainNode.nodeID
                    + ") exige ao menos 1 destinatario honesto em cada metade disjunta dos vizinhos; "
                    + "honestos na 1a metade=" + honestFirst + ", na 2a=" + honestSecond + " (n=" + size
                    + "). Ataque nao aplicado: simulacao abortada em vez de degradar para no honesto.");
        }
        return half;
    }

    @Override
    public void newIncomingBlock(B block) {

    }

    /**
     * @param block
     * @return
     */
    @Override
    public boolean isBlockConfirmed(B block) {
        return false;
    }

    /**
     * @param block
     * @return
     */
    @Override
    public boolean isBlockValid(B block) {
        return false;
    }

    public int getCurrentViewNumber() {
        return this.currentViewNumber;
    }

    public int getCurrentPrimaryNumber() {
        return (this.currentViewNumber % this.numAllParticipants);
    }

    public int getNumAllParticipants() {
        return this.numAllParticipants;
    }

    public PBFTPhase getPbftPhase() {
        return this.pbftPhase;
    }

    @Override
    protected void updateChain() {
        this.confirmedBlocks.add(this.currentMainChainHead);
        // Record acceptance for BFT per-node counters (sem catch: contabilizacao que falha deve falhar alto)
        recordBlockAcceptance(this.currentMainChainHead);
        // Prune local block DAG to free memory for very old blocks
        int threshold = Math.max(0, this.currentMainChainHead.getHeight() - 1000);
        this.localBlockTree.clearObsoleteData(threshold);
    }

    @Override
    protected int getBlockProposer(B block) {
        // Condicao legitima esperada: bloco genesis/sem criador (creator == null) -> proponente 0.
        // Checagem explicita; qualquer outra falha propaga.
        if (block != null && block.getCreator() != null) {
            return block.getCreator().nodeID;
        }
        return 0;
    }
}
