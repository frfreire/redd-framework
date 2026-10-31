package jabs.network.node.nodes.bitcoin;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.algorithm.AbstractChainBasedConsensus;
import jabs.consensus.algorithm.NakamotoConsensus;
import jabs.consensus.config.NakamotoConsensusConfig;
import jabs.ledgerdata.Vote;
import jabs.ledgerdata.bitcoin.BitcoinBlockWithoutTx;
import jabs.ledgerdata.bitcoin.BitcoinTx;
import jabs.network.message.InvMessage;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.ledgerdata.TransactionFactory;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.Node;
import jabs.network.p2p.BitcoinCoreP2P;
import jabs.simulator.Simulator;

public class BitcoinNode extends PeerBlockchainNode<BitcoinBlockWithoutTx, BitcoinTx> {
    public BitcoinNode(Simulator simulator, Network network, int nodeID, long downloadBandwidth, long uploadBandwidth,
                       BitcoinBlockWithoutTx genesisBlock, NakamotoConsensusConfig nakamotoConsensusConfig) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth,
                new BitcoinCoreP2P(),
                new NakamotoConsensus<>(new LocalBlockTree<>(genesisBlock), nakamotoConsensusConfig));
    }

    public BitcoinNode(Simulator simulator, Network network, int nodeID, long downloadBandwidth, long uploadBandwidth,
                       AbstractChainBasedConsensus<BitcoinBlockWithoutTx, BitcoinTx> consensusAlgorithm) {
        super(simulator, network, nodeID, downloadBandwidth, uploadBandwidth,
                new BitcoinCoreP2P(), consensusAlgorithm);
    }

    @Override
    protected void processNewTx(BitcoinTx bitcoinTx, Node from) {
        // when a transaction arrives, propagate it further and add to miner pools
        this.broadcastTxInvMessage(bitcoinTx);

        // if this node is a miner keep it in the local mempool for inclusion.
        // Criterio 4: o cast e o memPool nao falham (instanceof + campo inicializado); sem catch que engula.
        if (this instanceof BitcoinMinerNode miner) {
            miner.memPool.add(bitcoinTx);
        }
    }

    @Override
    protected void processNewBlock(BitcoinBlockWithoutTx bitcoinBlock) {
        this.consensusAlgorithm.newIncomingBlock(bitcoinBlock);
        
        // Byzantine behavior: Block withholding attack
        // If this node is Byzantine and the block creator is this node, withhold the block
        boolean shouldWithhold = false;
        if (bitcoinBlock.getCreator() != null && bitcoinBlock.getCreator().nodeID == this.nodeID) {
            // Check if this node is Byzantine
            if (this.consensusAlgorithm.isByzantineValidator(this.nodeID)) {
                String attackType = this.consensusAlgorithm.getByzantineAttackType();
                // WITHHOLD or EQUIVOCATION attack: don't broadcast
                if (attackType != null && 
                    ("WITHHOLD".equalsIgnoreCase(attackType) || "EQUIVOCATION".equalsIgnoreCase(attackType))) {
                    shouldWithhold = true;
                    // System.out.printf("[Byzantine-PoW] Node %d withholding block at height %d (attack: %s)%n", 
                    //     this.nodeID, bitcoinBlock.getHeight(), attackType);
                }
            }
        }
        
        if (!shouldWithhold) {
            this.broadcastBlockInvMessage(bitcoinBlock);
        }
    }

    @Override
    protected void processNewVote(Vote vote) {

    }

    @Override
    protected void processNewQuery(jabs.ledgerdata.Query query) {

    }

    protected void broadcastTxInvMessage(BitcoinTx tx) {
        for (Node neighbor:this.p2pConnections.getNeighbors()) {
            this.networkInterface.addToUpLinkQueue(
                    new Packet(this, neighbor,
                            new InvMessage(tx.getHash().getSize(), tx.getHash())
                    )
            );
        }
    }

    protected void broadcastBlockInvMessage(BitcoinBlockWithoutTx block) {
        for (Node neighbor:this.p2pConnections.getNeighbors()) {
            this.networkInterface.addToUpLinkQueue(
                    new Packet(this, neighbor,
                            new InvMessage(block.getHash().getSize(), block.getHash())
                    )
            );
        }
    }

    @Override
    public void generateNewTransaction() {
        BitcoinTx tx = TransactionFactory.sampleBitcoinTransaction(network.getRandom());
        // register submission time (used by Tt metric). TransactionSubmissionTracker cria o registro sob demanda,
        // entao nao ha "tracker ausente" a tolerar; falha real (criterio 4) propaga.
        jabs.log.TransactionSubmissionTracker.registerSubmission(tx.getHash(), simulator.getSimulationTime());
        this.alreadySeenTxs.put(tx.getHash(), tx);
        // if we are also a miner add to our mempool immediately
        if (this instanceof BitcoinMinerNode miner) {
            miner.memPool.add(tx);
        }
        broadcastTxInvMessage(tx);
    }
}
