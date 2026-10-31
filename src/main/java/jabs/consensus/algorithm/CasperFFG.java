package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.CasperFFGConfig;
import jabs.ledgerdata.*;
import jabs.ledgerdata.casper.CasperFFGLink;
import jabs.ledgerdata.casper.CasperFFGVote;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.Node;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import jabs.simulator.event.BlockFinalizationEvent;

import java.util.*;

/**
 * @param <B>
 * @param <T>
 */
public class CasperFFG<B extends SingleParentBlock<B>, T extends Tx<T>> extends GhostProtocol<B, T>
        implements VotingBasedConsensus<B, T>, DeterministicFinalityConsensus<B,T> {
    private final HashMap<CasperFFGLink<B>, HashMap<Node, CasperFFGVote<B>>> votes = new HashMap<>();
    private final SortedSet<B> justifiedBlocks = new TreeSet<>();
    private final SortedSet<B> finalizedBlocks = new TreeSet<>();

    private final Set<B> indirectlyFinalizedBlocks = new HashSet<>();
    private final Set<T> finalizedTxs = new HashSet<>();
    private final Map<B, Long> blockTraffic = new HashMap<>();

    private final int checkpointSpace;
    private final int numOfStakeholders;
    /** Votos distintos necessarios para um supermajority link: {@link #supermajorityThreshold(int)}. */
    private final int requiredVotes;
    private int latestCheckpoint = 0;

    private DescriptiveStatistics blockFinalizationTimes = null;

    /**
     * @param localBlockTree
     * @param casperFFGConfig
     */
    public CasperFFG(LocalBlockTree<B> localBlockTree, CasperFFGConfig casperFFGConfig) {
        super(localBlockTree, casperFFGConfig);
        this.checkpointSpace = casperFFGConfig.checkpointSpace();
        this.numOfStakeholders = casperFFGConfig.numOfStakeholders();
        this.requiredVotes = supermajorityThreshold(this.numOfStakeholders);
        this.justifiedBlocks.add(localBlockTree.getGenesisBlock());
        this.finalizedBlocks.add(localBlockTree.getGenesisBlock());
        this.indirectlyFinalizedBlocks.add(localBlockTree.getGenesisBlock());
        // System.out.println("[CasperFFG] Initialized with checkpointSpace=" + checkpointSpace +
        //         ", numOfStakeholders=" + numOfStakeholders);
    }

    /**
     * Limiar de votos de um supermajority link do Casper FFG (Buterin &amp; Griffith, 2017): votos somando pelo menos
     * 2/3 do peso total. Com peso unitario por validador isso e {@code ceil(2S/3)} votos, comparado com
     * {@code >=}; em aritmetica inteira exata, {@code (2S + 2) / 3}. Ex.: S=4 -&gt; 3, 7 -&gt; 5, 10 -&gt; 7,
     * 16 -&gt; 11, 64 -&gt; 43, 128 -&gt; 86.
     *
     * <p>Peso unitario por validador; stake desigual nao modelado (a definir com o humano). O codigo nao le
     * stake: {@code S} e o denominador configurado ({@link CasperFFGConfig#numOfStakeholders()}).
     *
     * <p>Substitui o limiar antigo {@code size() > ((S / 3) * 2) + 1}, isto e, no minimo {@code (S / 3) * 2 + 2}
     * votos. Em relacao ao teorico {@code ceil(2S/3)}, a diferenca depende de {@code S mod 3}:
     * <ul>
     * <li>{@code S = 3k}: antigo {@code 2k+2}, teorico {@code 2k}: o antigo exigia DOIS votos a mais
     * (S=9: 8 contra 6);</li>
     * <li>{@code S = 3k+1}: antigo {@code 2k+2}, teorico {@code 2k+1}: UM voto a mais (S=10: 8 contra 7);</li>
     * <li>{@code S = 3k+2}: antigo {@code 2k+2}, teorico {@code 2k+2}: IGUAL, a mudanca nao altera o limiar
     * (S=11: 8 contra 8).</li>
     * </ul>
     * Outros exemplos (antigo): S=4 exigia 4; 7: 6; 10: 8; 16: 12; 64: 44. Mudanca intencional da saida do Casper
     * (ADR-0006).
     *
     * @param numOfValidators S, numero de validadores (peso unitario), {@code >= 1}
     * @return menor numero de votos que satisfaz {@code votos >= 2S/3}
     * @throws IllegalArgumentException se {@code numOfValidators < 1}
     */
    public static int supermajorityThreshold(int numOfValidators) {
        if (numOfValidators < 1) {
            throw new IllegalArgumentException("CasperFFG: numero de validadores deve ser >= 1: " + numOfValidators);
        }
        return (int) ((2L * numOfValidators + 2) / 3);
    }

    /** Votos distintos exigidos por link neste no (justificacao e finalizacao, {@code >=}). */
    public int getRequiredVotes() {
        return requiredVotes;
    }

    /** Numero de votos distintos ja registrados para o link (0 se nenhum). */
    public int getVoteCount(CasperFFGLink<B> link) {
        HashMap<Node, CasperFFGVote<B>> vm = votes.get(link);
        return vm == null ? 0 : vm.size();
    }

    /** O bloco esta em {@code justifiedBlocks} (genesis incluido). */
    public boolean isBlockJustified(B block) {
        return justifiedBlocks.contains(block);
    }

    /**
     * Ataques com ramo proprio no CasperFFG (auditoria A.1): SILENT, WITHHOLD (ambos: nao vota),
     * EQUIVOCATION e DOUBLE_SIGN (neutralizados pela contagem por votante; semantica nao alterada aqui).
     */
    @Override
    protected Set<jabs.config.ByzantineConfig.AttackType> supportedAttacks() {
        return EnumSet.of(jabs.config.ByzantineConfig.AttackType.SILENT,
                jabs.config.ByzantineConfig.AttackType.WITHHOLD,
                jabs.config.ByzantineConfig.AttackType.EQUIVOCATION,
                jabs.config.ByzantineConfig.AttackType.DOUBLE_SIGN);
    }

    /**
     * @param blockFinalizationTimes
     */
    public void enableFinalizationTimeRecords(DescriptiveStatistics blockFinalizationTimes) {
        this.blockFinalizationTimes = blockFinalizationTimes;
    }

    // Add this method to allow traffic tracking from network code
    public void addBlockTraffic(B block, long bytes) {
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    public void newIncomingVote(Vote vote) {
        if (vote instanceof CasperFFGVote) {
            CasperFFGVote<B> casperFFGVote = (CasperFFGVote<B>) vote;
            CasperFFGLink<B> casperFFGLink = casperFFGVote.getLink();
            //  System.out.println("[CasperFFG] Received vote from node " + casperFFGVote.getVoter().nodeID +
            //         " for link: " + casperFFGLink.getToBeFinalized().getHeight() + "→" +
            //         casperFFGLink.getToBeJustified().getHeight());
            if (localBlockTree.areBlocksConnected(casperFFGLink.getToBeJustified(), casperFFGLink.getToBeFinalized())) {
                if (!votes.containsKey(casperFFGLink)) { // first vote for the link
                    votes.put(casperFFGLink, new HashMap<>());
                }
                votes.get(casperFFGLink).put(casperFFGVote.getVoter(), casperFFGVote);
                // System.out.println("[CasperFFG] Link votes for " +
                //         casperFFGLink.getToBeFinalized().getHeight() + "→" +
                //         casperFFGLink.getToBeJustified().getHeight() + ": " +
                //         votes.get(casperFFGLink).size() + " / " +
                //         requiredVotes);
                // Justificacao e finalizacao dependem do MESMO limiar (ceil(2S/3), >=): supermajorityThreshold.
                if (votes.get(casperFFGLink).size() >= requiredVotes) {
                    justifiedBlocks.add(casperFFGLink.getToBeJustified());
                    // System.out.println("[CasperFFG] Block justified: " +
                    //         casperFFGLink.getToBeJustified().getHeight());
                    if (!finalizedBlocks.contains(casperFFGLink.getToBeFinalized())) {
                        // System.out.println("[CasperFFG] Finalizing block: " +
                        //         casperFFGLink.getToBeFinalized().getHeight());
                        updateFinalizedBlocks(casperFFGLink.getToBeFinalized());
                        finalizedBlocks.add(casperFFGLink.getToBeFinalized());
                        if (this.originOfGhost.getHeight() < casperFFGLink.getToBeFinalized().getHeight()) {
                            this.originOfGhost = casperFFGLink.getToBeFinalized();
                        }
                    }
                }
            } else {
                // System.out.println("[CasperFFG] Blocks not connected for link: " +
                //         casperFFGLink.getToBeFinalized().getHeight() + "→" +
                //         casperFFGLink.getToBeJustified().getHeight());
            }
        } else {
            // System.out.println("[CasperFFG] Received non-CasperFFG vote: " + vote);
        }
    }

    private void updateFinalizedBlocks(B newlyFinalizedBlock) {
        if (!indirectlyFinalizedBlocks.contains(newlyFinalizedBlock)) {
            indirectlyFinalizedBlocks.add(newlyFinalizedBlock);
            if (blockFinalizationTimes != null) {
                blockFinalizationTimes.addValue(peerBlockchainNode.getSimulator().getSimulationTime() - newlyFinalizedBlock.getCreationTime());
            }
            // Record acceptance for BFT metrics based on validator votes (PoS).
            // Criterio 4 (modelo de falha explicito): sem try/catch. As unicas condicoes legitimas ja tem checagem
            // explicita (link/bloco/mapa nulos abaixo; "nenhum link do bloco" cai na atribuicao por proponente).
            // Qualquer outra falha (ex.: voto sem votante) PROPAGA em vez de virar contagem silenciosamente errada.
            boolean counted = false;
            for (Map.Entry<CasperFFGLink<B>, HashMap<Node, CasperFFGVote<B>>> e : votes.entrySet()) {
                CasperFFGLink<B> link = e.getKey();
                if (link != null && link.getToBeFinalized() != null && link.getToBeFinalized().equals(newlyFinalizedBlock)) {
                    HashMap<Node, CasperFFGVote<B>> vm = e.getValue();
                    int totalVotes = vm == null ? 0 : vm.size();
                    int byzVotes = vm == null ? 0 : countByzantineVoters(vm);
                    int honestVotes = totalVotes - byzVotes;
                    if (byzVotes > honestVotes) {
                        acceptedBlocksFromByzantine++;
                        jabs.log.BFTDebugAggregator.addPart(newlyFinalizedBlock.getHeight(),
                                String.format("casperVotes=byz:%d/total:%d -> COUNTED_BYZ", byzVotes, totalVotes));
                    } else {
                        acceptedBlocksFromHonest++;
                        jabs.log.BFTDebugAggregator.addPart(newlyFinalizedBlock.getHeight(),
                                String.format("casperVotes=byz:%d/total:%d -> COUNTED_HONEST", byzVotes, totalVotes));
                    }
                    counted = true;
                    break;
                }
            }
            if (!counted) {
                // Nenhum link com este bloco como toBeFinalized: atribuicao por proponente
                recordBlockAcceptance(newlyFinalizedBlock);
            }
            // Emit BlockFinalizationEvent for this block
            if (this.peerBlockchainNode != null && this.peerBlockchainNode.getSimulator() != null) {
                long traffic = blockTraffic.getOrDefault(newlyFinalizedBlock, 0L);
                this.peerBlockchainNode.getSimulator().putEvent(
                    new BlockFinalizationEvent(
                        this.peerBlockchainNode.getSimulator().getSimulationTime(),
                        this.peerBlockchainNode,
                        newlyFinalizedBlock,
                        traffic
                    ),
                    0
                );
            }
        }

        HashSet<B> ancestors = localBlockTree.getAllAncestors(newlyFinalizedBlock);

        for (B block:ancestors) {
            if (!indirectlyFinalizedBlocks.contains(block)) {
                indirectlyFinalizedBlocks.add(block);
                if (blockFinalizationTimes != null) {
                    blockFinalizationTimes.addValue(peerBlockchainNode.getSimulator().getSimulationTime() - block.getCreationTime());
                }
                if (block instanceof BlockWithTx) {
                    finalizedTxs.addAll(((BlockWithTx<T>) block).getTxs());
                }
                // Emit BlockFinalizationEvent for ancestor blocks
                if (this.peerBlockchainNode != null && this.peerBlockchainNode.getSimulator() != null) {
                    long traffic = blockTraffic.getOrDefault(block, 0L);
                    this.peerBlockchainNode.getSimulator().putEvent(
                        new BlockFinalizationEvent(
                            this.peerBlockchainNode.getSimulator().getSimulationTime(),
                            this.peerBlockchainNode,
                            block,
                            traffic
                        ),
                        0
                    );
                    // Record acceptance for BFT metrics for ancestor block (vote-based attribution first).
                    // Sem try/catch (criterio 4): mesma regra do bloco finalizado acima.
                    boolean countedAncestor = false;
                    for (Map.Entry<CasperFFGLink<B>, HashMap<Node, CasperFFGVote<B>>> e : votes.entrySet()) {
                        CasperFFGLink<B> link = e.getKey();
                        if (link != null && link.getToBeFinalized() != null && link.getToBeFinalized().equals(block)) {
                            HashMap<Node, CasperFFGVote<B>> vm = e.getValue();
                            int totalVotes = vm == null ? 0 : vm.size();
                            int byzVotes = vm == null ? 0 : countByzantineVoters(vm);
                            int honestVotes = totalVotes - byzVotes;
                            if (byzVotes > honestVotes) {
                                acceptedBlocksFromByzantine++;
                            } else {
                                acceptedBlocksFromHonest++;
                            }
                            countedAncestor = true;
                            break;
                        }
                    }
                    if (!countedAncestor) {
                        recordBlockAcceptance(block);
                    }
                }
            }
        }
    }

    /**
     * Quantos dos votantes do link sao bizantinos. Votante nulo e defeito do modelo (um voto sempre tem autor) e
     * falha ALTO, em vez de ser engolido e contado como honesto (criterio 4).
     */
    private int countByzantineVoters(Map<Node, CasperFFGVote<B>> votersOfLink) {
        int byz = 0;
        for (Node voter : votersOfLink.keySet()) {
            if (voter == null) {
                throw new IllegalStateException("CasperFFG: voto sem votante (null) no quorum de um link; "
                        + "nao e possivel atribuir honesto/bizantino");
            }
            if (isByzantineValidator(voter.nodeID)) byz++;
        }
        return byz;
    }

    @Override
    protected int getBlockProposer(B block) {
        if (block == null || block.getCreator() == null) return 0;
        return block.getCreator().nodeID;
    }

    @Override
    protected void updateChain() {
        // System.out.println("[CasperFFG] Updating chain...");
        this.confirmedBlocks = this.localBlockTree.getAllAncestors(this.currentMainChainHead);
        if ((currentMainChainHead.getHeight() - checkpointSpace) > latestCheckpoint) {
            latestCheckpoint = currentMainChainHead.getHeight() - (currentMainChainHead.getHeight() % checkpointSpace);

            B toBeFinalizedBlock = finalizedBlocks.last();
            if (localBlockTree.areBlocksConnected(currentMainChainHead, justifiedBlocks.last())) {
                if (finalizedBlocks.last().getHeight() < justifiedBlocks.last().getHeight()) {
                    toBeFinalizedBlock = justifiedBlocks.last();
                }
            } else {
                for (B jBlock:justifiedBlocks) {
                    if (localBlockTree.areBlocksConnected(currentMainChainHead, jBlock)) {
                        if (finalizedBlocks.last().getHeight() < jBlock.getHeight()) {
                            toBeFinalizedBlock = jBlock;
                        }
                    }
                }
            }

            B toBeJustifiedBlock = localBlockTree.getAncestorOfHeight(currentMainChainHead, latestCheckpoint);

            if (!localBlockTree.areBlocksConnected(toBeJustifiedBlock, toBeFinalizedBlock)) {
                System.out.print("What?\n");
            }

            CasperFFGLink<B> casperFFGLink = new CasperFFGLink<>(toBeFinalizedBlock, toBeJustifiedBlock);
            CasperFFGVote<B> casperFFGVote = new CasperFFGVote<>(this.peerBlockchainNode, casperFFGLink);
            VoteMessage voteMessage = new VoteMessage(casperFFGVote);

            // Byzantine behavior injection: SILENT, WITHHOLD, EQUIVOCATION, DOUBLE_SIGN
            boolean isByz = isByzantineValidator(this.peerBlockchainNode.nodeID);
            String attack = (byzantineConfig == null) ? null : byzantineConfig.getAttackType();
            if (attack != null) attack = attack.toUpperCase();

            // Criterio 4 (modelo de falha explicito): sem try/catch. As condicoes que o catch antigo cobria sao
            // checagens explicitas abaixo (lista de vizinhos vazia: o laco nao envia nada; link alternativo
            // ausente: voto no mesmo link, ver altJustified). Falha de envio, de processamento local ou de
            // finalizacao PROPAGA: o ataque nunca "some" com a simulacao seguindo adiante.
            if (isByz && "SILENT".equals(attack)) {
                // Do not vote
            } else if (isByz && "WITHHOLD".equals(attack)) {
                // Withhold vote (do nothing)
            } else if (isByz && "EQUIVOCATION".equals(attack)) {
                // Send conflicting votes to different halves of neighbors
                java.util.List<Node> neighbors = this.peerBlockchainNode.getP2pConnections().getNeighbors();
                int half = Math.max(1, neighbors.size() / 2);
                // Try to pick an alternative justification block (different from toBeJustifiedBlock)
                B altJustified = null;
                for (B jb : justifiedBlocks) {
                    if (!jb.equals(toBeJustifiedBlock)) { altJustified = jb; break; }
                }
                if (altJustified == null && this.localBlockTree.getGenesisBlock() != null && !this.localBlockTree.getGenesisBlock().equals(toBeJustifiedBlock)) {
                    altJustified = this.localBlockTree.getGenesisBlock();
                }

                // LACUNA registrada (nao e falha, semantica mantida): sem link alternativo justificavel
                // (altJustified == null) as duas metades recebem o MESMO voto (equivocacao vira voto honesto).
                // Hoje inalcancavel: o genesis esta sempre em justifiedBlocks e toBeJustifiedBlock tem altura
                // >= checkpointSpace > 0, logo difere do genesis.
                CasperFFGLink<B> altLink = (altJustified == null) ? casperFFGLink : new CasperFFGLink<>(toBeFinalizedBlock, altJustified);
                for (int i = 0; i < neighbors.size(); i++) {
                    Node nb = neighbors.get(i);
                    CasperFFGLink<B> chosen = (i < half) ? casperFFGLink : altLink;
                    CasperFFGVote<B> v = new CasperFFGVote<>(this.peerBlockchainNode, chosen);
                    this.peerBlockchainNode.getNodeNetworkInterface().addToUpLinkQueue(new jabs.network.message.Packet(this.peerBlockchainNode, nb, new VoteMessage(v)));
                }
                // Also process one locally (pick first vote)
                this.peerBlockchainNode.processIncomingPacket(new Packet(this.peerBlockchainNode, this.peerBlockchainNode, new VoteMessage(new CasperFFGVote<>(this.peerBlockchainNode, casperFFGLink))));
            } else if (isByz && "DOUBLE_SIGN".equals(attack)) {
                // Broadcast the same vote twice
                this.peerBlockchainNode.broadcastMessage(voteMessage);
                this.peerBlockchainNode.broadcastMessage(voteMessage);
                // Also process locally once
                this.peerBlockchainNode.processIncomingPacket(new Packet(this.peerBlockchainNode, this.peerBlockchainNode, voteMessage));
            } else {
                // Normal behavior: process own vote locally and broadcast to peers
                this.peerBlockchainNode.processIncomingPacket(new Packet(this.peerBlockchainNode, this.peerBlockchainNode, voteMessage));
                this.peerBlockchainNode.broadcastMessage(voteMessage);
            }
        }
    }

    public int getNumOfJustifiedBlocks() {
        return this.justifiedBlocks.size();
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
