package jabs.network.node.nodes.iota;

import jabs.consensus.algorithm.TangleIOTA;
import jabs.consensus.blockchain.LocalBlockDAG;
import jabs.consensus.config.TangleIOTAConsensusConfig;
import jabs.ledgerdata.Query;
import jabs.ledgerdata.Vote;
import jabs.ledgerdata.tangle.TangleTx;
import jabs.ledgerdata.tangle.TangleBlock;
import jabs.network.message.InvMessage;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.MinerNode;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerDLTNode;
import jabs.network.p2p.AbstractP2PConnections;
import jabs.simulator.Simulator;

public class IOTANode extends PeerDLTNode<TangleBlock, TangleTx> implements MinerNode {
    /**
     * Genesis do Tangle. ADR-0006, tarefa 5 (debito 7): a lista de pais era {@code null}, e
     * {@code new IOTANode(...)} dava NullPointerException na construcao (o TangleIOTA chama
     * {@code newIncomingBlock(genesis)}, que percorre {@code block.getParents()}; o {@code LocalBlockDAG.add}
     * tambem). Genesis nao tem pai: lista vazia e imutavel.
     */
    public static final TangleBlock TANGLE_GENESIS_BLOCK =
            new TangleBlock(0, 0, 0, null, java.util.List.of(), 32, new TangleTx(1650, 32),1, 0);

    protected Simulator.ScheduledEvent blockGenerationProcess;

    /**
     * @param simulator
     * @param network
     * @param nodeID
     * @param downloadBandwidth
     * @param uploadBandwidth
     * @param routingTable
     * @param tangleIOTAConsensusConfig
     */
    public IOTANode(Simulator simulator, Network network, int nodeID, long downloadBandwidth, long uploadBandwidth,
                    AbstractP2PConnections routingTable, TangleIOTAConsensusConfig tangleIOTAConsensusConfig) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth, routingTable,
                new TangleIOTA(new LocalBlockDAG<>(TANGLE_GENESIS_BLOCK), tangleIOTAConsensusConfig));
    }

    /**
     *
     */
    @Override
    public void generateNewTransaction() {

    }

    /**
     * @param tx
     * @param from
     */
    @Override
    protected void processNewTx(TangleTx tx, Node from) {

    }

    /**
     * @param block
     */
    @Override
    protected void processNewBlock(TangleBlock block) {
        this.consensusAlgorithm.newIncomingBlock(block);
        this.broadcastBlockInvMessage(block);
    }

    protected void broadcastBlockInvMessage(TangleBlock block) {
        for (Node neighbor:this.p2pConnections.getNeighbors()) {
            this.networkInterface.addToUpLinkQueue(
                    new Packet(this, neighbor,
                            new InvMessage(block.getHash().getSize(), block.getHash())
                    )
            );
        }
    }

    /**
     *
     */
    @Override
    public void startMining() {

    }

    /**
     *
     */
    @Override
    public void stopMining() {
        simulator.removeEvent(this.blockGenerationProcess);
    }

    /**
     * @param vote
     */
    @Override
    protected void processNewVote(Vote vote) {

    }

    @Override
    protected void processNewQuery(Query query) {

    }

    /**
     *
     */
    @Override
    public void generateNewBlock() {

    }

    /**
     * @return
     */
    @Override
    public double getHashPower() {
        return 0;
    }
}
