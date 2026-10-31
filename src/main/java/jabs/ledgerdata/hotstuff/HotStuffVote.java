package jabs.ledgerdata.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.Vote;
import jabs.network.node.nodes.Node;

/**
 * Voto de uma replica (assinatura parcial sobre fase, visao e bloco). O tamanho e fixado no construtor:
 * hash do bloco + overhead + assinatura do modo configurado (individual ou parcela agregavel).
 * Mensagens que carregam QC e nao sao voto (NEW_VIEW e DECIDE) usam {@link HotStuffQcMessage}.
 *
 * <p>Invariantes impostas na construcao ({@link IllegalArgumentException}): votante, fase, bloco e config nao
 * nulos e {@code view >= 0}. So existem as fases PREPARE, PRE_COMMIT e COMMIT (o enum). Que o votante esteja em
 * [0, n) e checado ao formar o {@link HotStuffQuorumCertificate} (o voto nao conhece n).
 */
public final class HotStuffVote extends Vote {
    /**
     * Fases em que a replica VOTA no HotStuff basico (Yin et al., alg. 2). Ficam de fora, de proposito,
     * NEW_VIEW (carrega o prepareQC mais alto que a replica conhece, nao um voto) e DECIDE (carrega o
     * commitQC): nao existem neste enum, entao nao podem ser construidas como voto. Ver
     * {@link HotStuffQcMessage}. Num {@link HotStuffQuorumCertificate}, a fase e a dos votos certificados.
     */
    public enum Phase { PREPARE, PRE_COMMIT, COMMIT }

    private final Phase phase;
    private final long view;
    private final HotStuffBlock block;

    public HotStuffVote(Node voter, Phase phase, long view, HotStuffBlock block, HotStuffConsensusConfig config) {
        super(computeSize(block, config), voter);
        if (voter == null) {
            throw new IllegalArgumentException("voter obrigatorio");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase obrigatoria");
        }
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
        this.phase = phase;
        this.view = view;
        this.block = block;
    }

    /** Tamanho em bytes de um voto sobre {@code block} sob {@code config}. */
    public static int computeSize(HotStuffBlock block, HotStuffConsensusConfig config) {
        if (block == null || config == null) {
            throw new IllegalArgumentException("block e config obrigatorios");
        }
        return block.getHash().getSize() + config.getMessageOverheadBytes()
                + config.voteSignatureSize(config.getSignatureMode());
    }

    public Phase getPhase() { return phase; }
    public long getView() { return view; }
    public HotStuffBlock getBlock() { return block; }
}
