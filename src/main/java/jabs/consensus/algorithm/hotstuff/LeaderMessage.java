package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.node.nodes.Node;

import java.util.Optional;

/**
 * Forma canonica de "o lider mandou a fase F da visao V sobre o bloco B com este QC", venha como
 * {@link HotStuffProposalMessage} (PREPARE, PRE_COMMIT, COMMIT, DECIDE) ou como {@link HotStuffQcMessage#decide}
 * (DECIDE por mensagem de QC). O desenho da rede tem os dois caminhos para o DECIDE; a replica os trata pelo MESMO
 * codigo, e por isso a retencao de visao futura tambem guarda so este tipo. Imutavel.
 */
public final class LeaderMessage {
    private final HotStuffProposalMessage.Phase phase;
    private final long view;
    private final Node sender;
    private final HotStuffBlock block;
    private final HotStuffQuorumCertificate qc; // nulo somente em PREPARE sem highQC

    private LeaderMessage(HotStuffProposalMessage.Phase phase, long view, Node sender, HotStuffBlock block,
                          HotStuffQuorumCertificate qc) {
        this.phase = phase;
        this.view = view;
        this.sender = sender;
        this.block = block;
        this.qc = qc;
    }

    /** A partir de uma proposta (qualquer fase). */
    public static LeaderMessage fromProposal(HotStuffProposalMessage m) {
        if (m == null) {
            throw new IllegalArgumentException("proposta obrigatoria");
        }
        return new LeaderMessage(m.getPhase(), m.getView(), m.getSender(), m.getBlock(), m.getQc().orElse(null));
    }

    /**
     * A partir de um DECIDE entregue como mensagem de QC; o bloco e o do commitQC.
     *
     * @throws IllegalArgumentException se a mensagem nao for DECIDE (NEW_VIEW nao e mensagem do lider)
     */
    public static LeaderMessage fromDecide(HotStuffQcMessage m) {
        if (m == null) {
            throw new IllegalArgumentException("mensagem obrigatoria");
        }
        if (m.getType() != HotStuffQcMessage.Type.DECIDE) {
            throw new IllegalArgumentException("so DECIDE vira LeaderMessage, recebido " + m.getType());
        }
        HotStuffQuorumCertificate qc = m.getQc().orElseThrow(
                () -> new IllegalStateException("DECIDE sem QC: irrepresentavel pela fabrica de HotStuffQcMessage"));
        return new LeaderMessage(HotStuffProposalMessage.Phase.DECIDE, m.getView(), m.getSender(), qc.getBlock(), qc);
    }

    public HotStuffProposalMessage.Phase getPhase() { return phase; }
    public long getView() { return view; }
    public Node getSender() { return sender; }
    public HotStuffBlock getBlock() { return block; }
    public Optional<HotStuffQuorumCertificate> getQc() { return Optional.ofNullable(qc); }
}
