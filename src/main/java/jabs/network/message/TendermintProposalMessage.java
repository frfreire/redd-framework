package jabs.network.message;

import jabs.consensus.algorithm.tendermint.TendermintProposal;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.network.node.nodes.Node;

/**
 * {@code <PROPOSAL, h, r, v, vr>} no fio. A proposta leva o bloco inteiro (nao ha DataMessage separado no
 * protocolo), por isso o tamanho total inclui {@code block.getSize()}; a parte de consenso e so o cabecalho
 * (hash do bloco + overhead do config), como no PBFT/HotStuff.
 */
public final class TendermintProposalMessage extends Message {
    private final Node sender;
    private final long height;
    private final int round;
    private final int validRound;
    private final TendermintBlock block;
    private final int consensusSize;

    public TendermintProposalMessage(Node sender, long height, int round, TendermintBlock block, int validRound,
                                     TendermintConsensusConfig config) {
        this(sender, height, round, block, validRound, config, sizeOf(block, config));
    }

    private TendermintProposalMessage(Node sender, long height, int round, TendermintBlock block, int validRound,
                                      TendermintConsensusConfig config, int consensusSize) {
        super(totalSize(consensusSize, block));
        if (sender == null || block == null || config == null) {
            throw new IllegalArgumentException("sender, block e config obrigatorios");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height deve ser >= 0: " + height);
        }
        if (round < 0) {
            throw new IllegalArgumentException("round deve ser >= 0: " + round);
        }
        if (validRound < -1 || validRound >= round) {
            throw new IllegalArgumentException("validRound deve estar em [-1, round): vr=" + validRound
                    + ", round=" + round);
        }
        this.sender = sender;
        this.height = height;
        this.round = round;
        this.validRound = validRound;
        this.block = block;
        this.consensusSize = consensusSize;
    }

    public TendermintProposal<TendermintBlock> toProposal() {
        return new TendermintProposal<>(height, round, block, validRound, sender.getNodeID());
    }

    private static int sizeOf(TendermintBlock block, TendermintConsensusConfig config) {
        if (block == null || config == null) {
            throw new IllegalArgumentException("block e config obrigatorios");
        }
        return block.getHash().getSize() + config.getMessageOverheadBytes();
    }

    private static int totalSize(int consensusSize, TendermintBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        long total = (long) consensusSize + block.getSize();
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tamanho da proposta excede int: " + total);
        }
        return (int) total;
    }

    public Node getSender() { return sender; }
    public long getHeight() { return height; }
    public int getRound() { return round; }
    public int getValidRound() { return validRound; }
    public TendermintBlock getBlock() { return block; }
    /** Bytes de protocolo: hash do bloco + overhead; NAO inclui a carga do bloco. */
    public int getConsensusSize() { return consensusSize; }
    /** Bytes que vao ao fio (== {@link #getSize()}). */
    public int getTotalSize() { return getSize(); }
}
