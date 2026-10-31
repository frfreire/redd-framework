package jabs.simulator.event;

import jabs.ledgerdata.Block;
import jabs.network.node.nodes.Node;

/**
 * Event fired when a block is proposed/created by a miner/validator.
 * This allows loggers to observe proposed blocks.
 */
public class BlockProposalEvent extends AbstractLogEvent {
    private final Node node;
    private final Block block;

    public BlockProposalEvent(double time, Node node, Block block) {
        super(time);
        this.node = node;
        this.block = block;
    }

    public Node getNode() {
        return node;
    }

    public Block getBlock() {
        return block;
    }
}
