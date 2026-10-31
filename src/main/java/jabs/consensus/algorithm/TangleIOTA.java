package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockDAG;
import jabs.consensus.config.TangleIOTAConsensusConfig;
import jabs.ledgerdata.tangle.TangleBlock;
import jabs.ledgerdata.tangle.TangleTx;

import java.util.HashMap;
import java.util.HashSet;
import jabs.simulator.event.BlockFinalizationEvent;

/**
 *
 */
public class TangleIOTA extends AbstractDAGBasedConsensus<TangleBlock, TangleTx> {
    protected final HashMap<TangleBlock, Double> blockAccWeights = new HashMap<>();

    // ADR-0006 (tarefa 4). Trafego MEDIDO, convencao "trafego recebido por no" (a da tarefa 1): cada
    // instancia pertence a um unico no e so acumula bytes de pacotes que ESTE no recebeu da rede
    // (packet.getSize()), vindos do hook em PeerDLTNode#processIncomingPacket (IOTANode e um PeerDLTNode,
    // nao um PeerBlockchainNode: o hook de PeerBlockchainNode nao o alcanca). Sem formula em n, sem ruido.
    // Aleatoriedade: o Random(42) so existia para o ruido do trafego sintetico; removido o ruido, este
    // protocolo nao consome mais nenhum numero aleatorio (nao ha o que derivar do RandomnessEngine).
    // Os mapas/conjuntos abaixo so sofrem get/put/remove/contains por chave, nunca iteracao.
    private final HashMap<TangleBlock, Long> blockTraffic = new HashMap<>();
    // Blocos ja confirmados por este no: pacotes tardios nao entram (a metrica e "ate a confirmacao").
    private final HashSet<TangleBlock> trafficClosed = new HashSet<>();

    /**
     * Creates a Tangle Consensus Algorithm
     *
     * @param localBlockDAG local block tree in the node's memory
     */
    public TangleIOTA(LocalBlockDAG<TangleBlock> localBlockDAG, TangleIOTAConsensusConfig tangleIOTAConsensusConfig) {
        super(localBlockDAG);
        this.newIncomingBlock(localBlockDAG.getGenesisBlock());
    }

    /** If a new block that is connected to the genesis block is received
     * this function will be called. Thus, any input block is both totally
     * new and 100% connected to genesis block with all ancestors known to
     * the node. This function will update the block acc accumulative
     * weights in the consensus state of the node.
     *
     * @param block the newly received block
     */
    @Override
    public void newIncomingBlock(TangleBlock block) {
        blockAccWeights.put(block, block.getWeight());
        HashSet<TangleBlock> ancestors = localBlockDAG.getAllAncestors(block);
        for (TangleBlock ancestor:ancestors) {
            blockAccWeights.put(ancestor, blockAccWeights.get(ancestor) + block.getWeight());
        }
    }

    /**
     * Acumula C_B_total (bytes recebidos por este no em pacotes que carregam o bloco). Chamado por
     * {@link jabs.network.node.nodes.PeerDLTNode#processIncomingPacket} com {@code packet.getSize()}.
     * Pacotes que chegam depois da confirmacao local do bloco nao entram.
     */
    public void addBlockTraffic(TangleBlock block, long bytes) {
        if (trafficClosed.contains(block)) {
            return;
        }
        blockTraffic.put(block, blockTraffic.getOrDefault(block, 0L) + bytes);
    }

    /** C_B_total acumulado por este no para o bloco (0 se nada foi recebido). */
    public long getBlockTraffic(TangleBlock block) {
        return blockTraffic.getOrDefault(block, 0L);
    }

    /**
     * Stub: confirm a block (emit finalization event and record acceptance).
     * Protocol-specific confirmation logic should call this when a block
     * reaches the required confidence/weight threshold.
     *
     * O trafego do evento e o MEDIDO por este no ate aqui (C_B_total, 0 = nada recebido). O Tangle nao
     * tem fase de consenso separavel (confirmacao por peso acumulado, sem mensagens de voto), logo
     * C_B_consenso e {@link BlockFinalizationEvent#NOT_MEASURED} (-1), que nao e zero.
     */
    public void confirmBlock(TangleBlock block) {
        if (block == null) return;
        if (this.peerDLTNode != null && this.peerDLTNode.getSimulator() != null) {
            long traffic = blockTraffic.getOrDefault(block, 0L);
            blockTraffic.remove(block);
            trafficClosed.add(block);
            this.peerDLTNode.getSimulator().putEvent(
                    new BlockFinalizationEvent(
                            this.peerDLTNode.getSimulator().getSimulationTime(),
                            this.peerDLTNode,
                            block,
                            traffic,
                            BlockFinalizationEvent.NOT_MEASURED
                    ),
                    0
            );
        }
        // record for BFT metrics
        recordBlockAcceptance(block);
    }

    @Override
    protected int getBlockProposer(TangleBlock block) {
        if (block == null || block.getCreator() == null) return 0;
        return block.getCreator().nodeID;
    }
}
