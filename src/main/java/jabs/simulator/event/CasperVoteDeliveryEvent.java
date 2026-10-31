package jabs.simulator.event;

import jabs.network.node.nodes.Node;
import jabs.ledgerdata.casper.CasperFFGLink;
import jabs.ledgerdata.casper.CasperFFGVote;
import jabs.consensus.algorithm.CasperFFG;

/**
 * Delivery event that simulates a stakeholder casting a Casper vote after a small delay.
 */
public class CasperVoteDeliveryEvent implements Event {
    private final Node validator;
    private final CasperFFGLink link;
    private final CasperFFG consensus;

    public CasperVoteDeliveryEvent(Node validator, CasperFFGLink link, CasperFFG consensus) {
        this.validator = validator;
        this.link = link;
        this.consensus = consensus;
    }

    @Override
    public void execute() {
        try {
            this.consensus.newIncomingVote(new CasperFFGVote(this.validator, this.link));
        } catch (Exception ignored) {}
    }
}
