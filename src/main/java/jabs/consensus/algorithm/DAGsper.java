package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.DAGsperConfig;
import jabs.ledgerdata.*;
import jabs.ledgerdata.dagsper.DAGsperVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.Node;
import jabs.simulator.event.BlockFinalizationEvent;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.*;

public class DAGsper<B extends SingleParentBlock<B>, T extends Tx<T>> extends GhostProtocol<B, T>
        implements VotingBasedConsensus<B, T>, DeterministicFinalityConsensus<B,T> {
    private final int numOfStakeholders;
    private final int checkpointSpace;
    // ADR-0006 (tarefa 4, rodada 4, criterio 3): quorum teorico 2f+1, f = floor((n-1)/3), comparado com '>='.
    // Antes: (n/3)*2+1 com '>' estrito (ja na base original), que exigia 2f+2 votos quando n = 3f+1
    // (n=4 exigia 4; n=7 exigia 6; n=10 exigia 8). Mesma formula do PBFT (tarefa 2).
    private final int requiredVotes;

    private final HashMap<Integer, HashMap<Node, B>> fVotesInHeight = new HashMap<>();
    private final HashMap<Integer, HashMap<Node, B>> jVotesInHeight = new HashMap<>();
    private final HashMap<B, HashSet<Node>> fVotesForBlock = new HashMap<>();
    private final HashMap<B, HashSet<Node>> jVotesForBlock = new HashMap<>();
    private final HashMap<Integer, HashSet<B>> fVotedBlocksInHeight = new HashMap<>();
    private final HashMap<Integer, HashSet<B>> jVotedBlocksInHeight = new HashMap<>();
    private final Set<Integer> unfinalizableHeights = new HashSet<>();

    private final SortedSet<B> finalizedBlocks = new TreeSet<>();
    private final SortedSet<B> justifiedBlocks = new TreeSet<>();
    private final Set<B> indirectlyFinalizedBlocks = new HashSet<>();
    private final Set<T> finalizedTxs = new HashSet<>();

    private final HashMap<Integer, B> previousFVotes = new HashMap<>();
    private final HashMap<Integer, B> previousJVotes = new HashMap<>();

    private DescriptiveStatistics blockFinalizationTimes = null;

    // ADR-0006 (tarefa 4). Trafego MEDIDO, convencao "trafego recebido por no" (a mesma do PBFT, tarefa 1):
    // cada instancia pertence a um unico no e so acumula bytes de pacotes que ESTE no recebeu da rede
    // (packet.getSize()), vindos de PeerBlockchainNode#processIncomingPacket. Nenhuma formula em n, nenhum ruido.
    // Convencao (rodada 3): so conta pacote que chegou pela rede simulada (PacketSendingProcess -> PacketDeliveryEvent
    // -> PacketReceivingProcess). O bloco que o minerador entrega a si mesmo (EthereumMinerNode#generateNewBlock) e o
    // voto que o no entrega a si mesmo em updateChain sao chamadas diretas a processIncomingPacket com
    // from == to == este no: NAO sao trafego e o hook de PeerBlockchainNode os ignora para o DAGsper. Isto difere de
    // PBFT/Casper/Nakamoto/Parlia, cujos hooks nao foram alterados. O broadcast de rede para os OUTROS nos continua contado.
    // Atribuicao a um bloco (DAGsper nao tem mensagem "de um bloco" como o PRE_PREPARE do PBFT):
    //  - pacote DataMessage que carrega o bloco -> esse bloco;
    //  - DAGsperVote -> o bloco que o voto nomeia (toBeJustified), analogo ao PBFTBlockVote#getBlock.
    // LIMITE declarado: um voto que ajuda a finalizar o bloco X mas nomeia um descendente Y conta em Y, nao em X
    // (um voto ajuda a finalizar varios blocos; contar em todos duplicaria bytes). Logo C_B de X e o custo
    // observado ate X ser finalizado localmente em bytes que nomeiam X, nao o custo de todo voto que o finalizou.
    // C_B_total: bloco + votos atribuidos. C_B_consenso: so os votos (fase de consenso separavel do bloco).
    // Acesso so por chave (get/put/remove), nunca iteracao.
    private final HashMap<B, Long> blockTraffic = new HashMap<>();
    private final HashMap<B, Long> consensusTraffic = new HashMap<>();

    public DAGsper(LocalBlockTree<B> localBlockTree, DAGsperConfig daGsperConfig) {
        super(localBlockTree, daGsperConfig);
        this.checkpointSpace = daGsperConfig.checkpointSpace();
        this.numOfStakeholders = daGsperConfig.numOfStakeholders();
        this.requiredVotes = quorumSize(this.numOfStakeholders);
        finalizedBlocks.add(localBlockTree.getGenesisBlock());
        justifiedBlocks.add(localBlockTree.getGenesisBlock());
        indirectlyFinalizedBlocks.add(localBlockTree.getGenesisBlock());
    }

    /** Ataques implementados no DAGsper (ADR-0006, tarefa 5): SILENT e WITHHOLD. Os demais sao recusados. */
    @Override
    protected Set<jabs.config.ByzantineConfig.AttackType> supportedAttacks() {
        return EnumSet.of(jabs.config.ByzantineConfig.AttackType.SILENT,
                jabs.config.ByzantineConfig.AttackType.WITHHOLD);
    }

    /**
     * Desempate deterministico do GHOST (ADR-0006, tarefa 5). Os filhos vivem num HashSet de blocos sem hashCode:
     * a ordem de iteracao dependia do endereco do objeto e, num empate de peso, escolhia o ramo por esse acaso
     * (mesma semente, ramo principal diferente, e com ele blocos, votos e trafego diferentes). Ordem: altura,
     * instante de criacao, id do criador.
     */
    @Override
    protected Iterable<B> childrenInGhostOrder(B block) {
        List<B> ordered = new ArrayList<>(localBlockTree.getChildren(block));
        ordered.sort(Comparator.<B>comparingInt(b -> b.getHeight())
                .thenComparingDouble(b -> b.getCreationTime())
                .thenComparingInt(b -> b.getCreator() == null ? -1 : b.getCreator().nodeID));
        return ordered;
    }

    /** f = floor((n-1)/3): maximo de participantes bizantinos tolerados com n participantes (0 se n < 4). */
    public static int maxFaultyNodes(int numOfStakeholders) {
        return Math.max(0, (numOfStakeholders - 1) / 3);
    }

    /**
     * Quorum 2f+1 (f = floor((n-1)/3)), usado com {@code >=} em justificacao, finalizacao e na checagem de
     * altura nao finalizavel: n=4 -> 3, n=7 -> 5, n=10 -> 7. Votos distintos (por no) no mesmo bloco.
     */
    public static int quorumSize(int numOfStakeholders) {
        return 2 * maxFaultyNodes(numOfStakeholders) + 1;
    }

    /** Numero de votos (nos distintos) exigido para justificar ou finalizar um bloco: 2f+1. */
    public int getRequiredVotes() {
        return requiredVotes;
    }

    /** O bloco recebeu quorum de votos de justificacao neste no. */
    public boolean isBlockJustified(B block) {
        return justifiedBlocks.contains(block);
    }

    public void enableFinalizationTimeRecords(DescriptiveStatistics blockFinalizationTimes) {
        this.blockFinalizationTimes = blockFinalizationTimes;
    }

    /**
     * Acumula C_B_total com os bytes de um pacote recebido que carrega o bloco. Chamado por
     * {@link jabs.network.node.nodes.PeerBlockchainNode#processIncomingPacket} com {@code packet.getSize()}.
     * Pacotes que chegam depois da finalizacao local do bloco nao entram (a metrica e "ate a finalizacao").
     */
    public void addBlockTraffic(B block, long bytes) {
        if (indirectlyFinalizedBlocks.contains(block)) {
            return;
        }
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    /**
     * Acumula os bytes de um pacote de voto recebido no bloco que o voto nomeia (toBeJustified), em C_B_total
     * e em C_B_consenso. Mesma regra de corte de {@link #addBlockTraffic}. Ignora o que nao for DAGsperVote.
     */
    public void addVoteTraffic(Vote vote, long bytes) {
        if (!(vote instanceof DAGsperVote)) {
            return;
        }
        B named = (B) ((DAGsperVote) vote).toBeJustified;
        if (named == null || indirectlyFinalizedBlocks.contains(named)) {
            return;
        }
        blockTraffic.put(named, blockTraffic.getOrDefault(named, 0L) + bytes);
        consensusTraffic.put(named, consensusTraffic.getOrDefault(named, 0L) + bytes);
    }

    /** C_B_total acumulado por este no para o bloco (0 se nada foi recebido ou se ja finalizou). */
    public long getBlockTraffic(B block) {
        return blockTraffic.getOrDefault(block, 0L);
    }

    /** C_B_consenso acumulado por este no para o bloco (0 se nada foi recebido ou se ja finalizou). */
    public long getConsensusMessageTraffic(B block) {
        return consensusTraffic.getOrDefault(block, 0L);
    }

    /**
     * Emite o BlockFinalizationEvent com o trafego medido ate agora e encerra a contagem do bloco.
     * O bloco ja deve constar de indirectlyFinalizedBlocks (o que fecha novas acumulacoes).
     */
    private void emitFinalization(B block) {
        long total = blockTraffic.getOrDefault(block, 0L);
        long consensus = consensusTraffic.getOrDefault(block, 0L);
        blockTraffic.remove(block);
        consensusTraffic.remove(block);
        if (this.peerBlockchainNode != null && this.peerBlockchainNode.getSimulator() != null) {
            this.peerBlockchainNode.getSimulator().putEvent(
                    new BlockFinalizationEvent(
                            this.peerBlockchainNode.getSimulator().getSimulationTime(),
                            this.peerBlockchainNode, block, total, consensus),
                    0);
        }
    }

    @Override
    public void newIncomingVote(Vote vote) {
        if (vote instanceof DAGsperVote) {
            B toBeJustified = (B) ((DAGsperVote) vote).toBeJustified;
            B latestFinalized = localBlockTree.getAncestorOfHeight(toBeJustified, ((DAGsperVote) vote).latestFinalizedHeight);
            B toBeFinalized = localBlockTree.getAncestorOfHeight(toBeJustified, ((DAGsperVote) vote).toBeFinalizedHeight);
            HashSet<Integer> unaffectedHeights = ((DAGsperVote) vote).unaffectedHeights;
            List<B> toBeJustifiedPath = localBlockTree.getPathBetween(toBeJustified, toBeFinalized);
            if (toBeJustifiedPath != null) {
                toBeJustifiedPath.removeIf(i -> unaffectedHeights.contains(i.getHeight()));
                this.addVote(toBeJustifiedPath, vote.getVoter(), jVotesInHeight, jVotesForBlock, fVotedBlocksInHeight);
            }
            List<B> toBeFinalizedPath = localBlockTree.getPathBetween(toBeFinalized, latestFinalized);
            if (toBeFinalizedPath != null) {
                toBeFinalizedPath.removeIf(i -> unaffectedHeights.contains(i.getHeight()));
                this.addVote(toBeFinalizedPath, vote.getVoter(), fVotesInHeight, fVotesForBlock, jVotedBlocksInHeight);
            }
        }
    }

    public void addVote(List<B> blocks, Node voter, HashMap<Integer, HashMap<Node, B>> heightVotes,
                        HashMap<B, HashSet<Node>> blockVotes, HashMap<Integer, HashSet<B>> blocksInHeight) {
        for (B block:blocks) {
            if (!heightVotes.containsKey(block.getHeight())) { // first vote for this height
                heightVotes.put(block.getHeight(), new HashMap<>());
            }

            if (!blockVotes.containsKey(block)) { // first vote for this block
                blockVotes.put(block, new HashSet<>());
            }

            if (!blocksInHeight.containsKey(block.getHeight())) { // first block for this height
                blocksInHeight.put(block.getHeight(), new HashSet<>());
            }

            if (heightVotes.get(block.getHeight()).containsKey(voter)) {
                if (heightVotes.get(block.getHeight()).get(voter) != block) {
                    System.out.printf("Slashing condition for participant %s, on height %s\n", voter, block.getHeight());
                }
            } else {
                heightVotes.get(block.getHeight()).put(voter, block);
                blockVotes.get(block).add(voter);
                blocksInHeight.get(block.getHeight()).add(block);
            }

            if (heightVotes == fVotesInHeight) { // if it is a finalization vote
                int totalNumOfVotes = heightVotes.get(block.getHeight()).size();
                int highestVote = 0;
                for (B blockOfHeight:blocksInHeight.get(block.getHeight())) {
                    if (highestVote < blockVotes.get(blockOfHeight).size()) {
                        highestVote = blockVotes.get(blockOfHeight).size();
                    }
                }
                if ((numOfStakeholders - totalNumOfVotes) + highestVote < requiredVotes) {
                    // if the height is unfinalizable
                    unfinalizableHeights.add(block.getHeight());
                }

                // did block gain enough votes?
                if (!finalizedBlocks.contains(block)) {
                    if (blockVotes.get(block).size() >= requiredVotes) {
                        int finalizableHeight = block.getHeight() - 1;

                        while (unfinalizableHeights.contains(finalizableHeight)) {
                            finalizableHeight--;
                        }

                        if (finalizedBlocks.contains(localBlockTree.getAncestorOfHeight(block, finalizableHeight))) {
                            finalizedBlocks.add(block);
                            updateFinalizedBlocks(finalizedBlocks.last());
                            this.originOfGhost = finalizedBlocks.last();
                        }
                    }
                }
            } else { // it is a justification vote
                // did block gain enough votes?
                if (blockVotes.get(block).size() >= requiredVotes) {
                    justifiedBlocks.add(block);
                }
            }
        }
    }

    private void updateFinalizedBlocks(B newlyFinalizedBlock) {
        if (!indirectlyFinalizedBlocks.contains(newlyFinalizedBlock)) {
            indirectlyFinalizedBlocks.add(newlyFinalizedBlock);
            if (blockFinalizationTimes != null) {
                blockFinalizationTimes.addValue(peerBlockchainNode.getSimulator().getSimulationTime() - newlyFinalizedBlock.getCreationTime());
            }
            emitFinalization(newlyFinalizedBlock);
        }

        Set<B> ancestors = new HashSet<>();
        Set<B> nextAncestors = new HashSet<>();
        ancestors.add(newlyFinalizedBlock);
        while (ancestors.size() > 0) {
            for (B block:ancestors) {
                nextAncestors.add(block.getParent());
                if (block instanceof EthereumBlock) {
                    nextAncestors.addAll((Collection<? extends B>) ((EthereumBlock) block).getUncles());
                }
            }
            ancestors.clear();
            for (B block:nextAncestors) {
                if (localBlockTree.contains(block)) {
                    if (!indirectlyFinalizedBlocks.contains(block)) {
                        ancestors.add(block);
                    }
                }
            }

            indirectlyFinalizedBlocks.addAll(ancestors);

            // Ordem de emissao deterministica: `ancestors` e um HashSet de blocos sem hashCode (ordem pelo
            // endereco do objeto), e a ordem dos eventos no simulador nao pode depender disso.
            List<B> emissionOrder = new ArrayList<>(ancestors);
            emissionOrder.sort(Comparator.<B>comparingInt(b -> b.getHeight())
                    .thenComparingDouble(b -> b.getCreationTime())
                    .thenComparingInt(b -> b.getCreator() == null ? -1 : b.getCreator().nodeID));
            for (B block:emissionOrder) {
                emitFinalization(block);
            }

            for (B block:ancestors) {
                if (blockFinalizationTimes != null) {
                    blockFinalizationTimes.addValue(peerBlockchainNode.getSimulator().getSimulationTime() - block.getCreationTime());
                }
                if (block instanceof BlockWithTx) {
                    finalizedTxs.addAll(((BlockWithTx<T>) block).getTxs());
                }
            }

            nextAncestors.clear();
        }
    }

    @Override
    protected void updateChain() {
        confirmedBlocks = localBlockTree.getAllAncestors(currentMainChainHead);
        if (currentMainChainHead.getHeight() > 0) {
            if ((currentMainChainHead.getHeight() % checkpointSpace) == (peerBlockchainNode.nodeID % checkpointSpace)) {
                B latestConnectedJBlock = finalizedBlocks.last();

                if (localBlockTree.areBlocksConnected(currentMainChainHead, justifiedBlocks.last())) {
                    latestConnectedJBlock = justifiedBlocks.last();
                } else {
                    for (B jBlock:justifiedBlocks) {
                        if (localBlockTree.areBlocksConnected(currentMainChainHead, jBlock))
                            latestConnectedJBlock = jBlock;
                    }
                }

                HashSet<Integer> unaffectedHeights = new HashSet<>();

                List<B> fPath = localBlockTree.getPathBetween(latestConnectedJBlock, finalizedBlocks.last());
                removePreviouslyVotedHeights(unaffectedHeights, fPath, previousFVotes);

                List<B> jPath = localBlockTree.getPathBetween(currentMainChainHead, latestConnectedJBlock);
                removePreviouslyVotedHeights(unaffectedHeights, jPath, previousJVotes);

                DAGsperVote<B> daGsperVote = new DAGsperVote<>(peerBlockchainNode, finalizedBlocks.last().getHeight(),
                        latestConnectedJBlock.getHeight(), currentMainChainHead, unaffectedHeights);
                // Modelo de falha (ADR-0006, tarefa 5): SILENT e WITHHOLD, mesma semantica do voto do Casper:
                // o no bizantino NAO emite o proprio voto; segue recebendo, propagando e minerando. O previousFVotes/
                // previousJVotes acima ja foram atualizados (nao ha efeito colateral relevante: so o voto some).
                // Qualquer outro ataque configurado e recusado, nao tratado como honesto em silencio.
                if (isByzantineValidator(peerBlockchainNode.nodeID)) {
                    String attack = byzantineConfig.getAttackType() == null ? "" : byzantineConfig.getAttackType().toUpperCase();
                    if ("SILENT".equals(attack) || "WITHHOLD".equals(attack)) {
                        return;
                    }
                    throw new UnsupportedOperationException("DAGsper so implementa SILENT e WITHHOLD (ataque configurado: "
                            + attack + ")");
                }
                VoteMessage voteMessage = new VoteMessage(daGsperVote);
                Packet packet = new Packet(this.peerBlockchainNode, this.peerBlockchainNode, voteMessage);
                this.peerBlockchainNode.processIncomingPacket(packet);
            }
        }
    }

    private void removePreviouslyVotedHeights(HashSet<Integer> unaffectedHeights, List<B> path, HashMap<Integer, B> previousVotes) {
        if (path != null) {
            for (B jBlock:path) {
                if (previousVotes.containsKey(jBlock.getHeight())) {
                    if (previousVotes.get(jBlock.getHeight()) != jBlock) {
                        unaffectedHeights.add(jBlock.getHeight());
                    }
                } else {
                    previousVotes.put(jBlock.getHeight(), jBlock);
                }
            }
        }
    }

    @Override
    public boolean isBlockFinalized(B block) {
        return this.indirectlyFinalizedBlocks.contains(block);
    }

    @Override
    public boolean isTxFinalized(T tx) {
        return this.finalizedTxs.contains(tx);
    }

    @Override
    public int getNumOfFinalizedBlocks() {
        return this.indirectlyFinalizedBlocks.size();
    }

    @Override
    public int getNumOfFinalizedTxs() {
        return this.finalizedTxs.size();
    }
}
