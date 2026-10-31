package jabs.network.message;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.node.nodes.Node;

import java.util.Optional;

/**
 * Proposta do lider em uma fase do HotStuff basico (Yin et al., PODC 2019, alg. 2): PREPARE, PRE_COMMIT, COMMIT e
 * DECIDE. Cada fase carrega o QC formado pela fase anterior; e isso que encadeia as fases de fato.
 *
 * <h3>Enum de fase proprio</h3>
 * {@link HotStuffVote.Phase} so tem as fases em que a replica VOTA (PREPARE, PRE_COMMIT, COMMIT); a proposta
 * tambem existe em DECIDE (sem voto de resposta). Por isso esta classe tem o seu {@link Phase} (as quatro fases),
 * em vez de ampliar o enum de voto (que ja esta aprovado e cuja ausencia de DECIDE e invariante dele).
 *
 * <h3>Fabricas e invariantes</h3>
 * Nao ha construtor publico; cada fabrica impoe a regra da sua fase ({@link IllegalArgumentException} na
 * construcao). Qual QC cada fase carrega (a conferir com o artigo, alg. 2):
 * <ul>
 *   <li><b>PREPARE</b>: carrega o {@code highQC} (o prepareQC mais alto entre os NEW_VIEW recebidos) OU nada na
 *       primeira visao / quando nenhuma replica conhece prepareQC. Os dois casos sao fabricas distintas e
 *       explicitas ({@link #prepare} exige QC de fase PREPARE; {@link #prepareWithoutHighQc} nao tem QC), sem
 *       {@code null} silencioso; {@link #getQc()} devolve {@link Optional}. O bloco proposto estende o do highQC
 *       no artigo (a conferir); isso e do algoritmo e NAO e exigido aqui, por isso o bloco pode diferir do do
 *       QC.</li>
 *   <li><b>PRE_COMMIT</b>: exige QC de fase PREPARE (o prepareQC recem-formado), sobre o MESMO bloco
 *       (mesma instancia: {@code HotStuffBlock} nao define equals/hashCode e o simulador compartilha o objeto).</li>
 *   <li><b>COMMIT</b>: exige QC de fase PRE_COMMIT (precommitQC), sobre o MESMO bloco.</li>
 *   <li><b>DECIDE</b>: exige QC de fase COMMIT (commitQC), sobre o MESMO bloco. Existe tambem o DECIDE como
 *       {@link jabs.ledgerdata.hotstuff.HotStuffQcMessage#decide}, sem bloco proprio; qual dos dois o algoritmo
 *       usara e decisao da parte seguinte.</li>
 *   <li>{@code view >= qc.view} em todas (a mensagem nunca carrega QC de visao futura; a igualdade estrita nao e
 *       imposta, a conferir com o artigo); {@code view >= 0}.</li>
 *   <li>Remetente, bloco e config nao nulos; id do remetente em [0, n), com n = tamanho de rede do QC quando ha QC
 *       (em PREPARE com QC o {@code n} informado deve coincidir com o do QC) ou o informado em
 *       {@link #prepareWithoutHighQc}. Que o remetente seja o lider da visao
 *       ({@link HotStuffConsensusConfig#leaderFor}) e do algoritmo, nao desta camada.</li>
 * </ul>
 *
 * <h3>Config: um so, o do QC</h3>
 * O overhead de cabecalho vem de {@link HotStuffConsensusConfig#getMessageOverheadBytes()}. O QC ja incorpora o
 * overhead do config com que foi construido ({@link HotStuffQuorumCertificate#getConfig()}); se a proposta somasse
 * o overhead de OUTRO config, o tamanho seria invalido para o modelo. Por isso as fabricas que recebem QC
 * ({@link #prepare}, {@link #preCommit}, {@link #commit}, {@link #decide}) NAO aceitam {@code config}: usam o do QC,
 * e a divergencia e irrepresentavel. So {@link #prepareWithoutHighQc}, que nao tem QC, recebe o config explicito.
 *
 * <h3>Tamanhos (dois valores)</h3>
 * <ul>
 *   <li>{@link #getConsensusSize()}: parte de protocolo = hash do bloco ({@code block.getHash().getSize()}, 32 B)
 *       + {@code messageOverheadBytes} + {@code qc.getSize()} se houver QC. O overhead da proposta e o mesmo do
 *       voto (suposicao do config: a definir com o humano; a conferir com o artigo o que mais a proposta carrega,
 *       p.ex. numero de visao e assinatura do lider, hoje dentro do overhead).</li>
 *   <li>{@link #getTotalSize()} (= {@link #getSize()}, o que vai ao fio e alimenta o pacote): em PREPARE soma
 *       tambem {@code block.getSize()}; nas demais fases e igual a {@link #getConsensusSize()}.</li>
 * </ul>
 * A carga do bloco entra so no total, nunca no tamanho de consenso: ela conta apenas em C_B_total (convencao do
 * ADR-0006, a mesma do PBFT). Nao ha constante nova: tudo vem do config, do hash, do bloco e do QC.
 */
public final class HotStuffProposalMessage extends Message {

    /** Fases em que o lider propoe no HotStuff basico (inclui DECIDE, que {@link HotStuffVote.Phase} nao tem). */
    public enum Phase { PREPARE, PRE_COMMIT, COMMIT, DECIDE }

    private final Phase phase;
    private final long view;
    private final Node sender;
    private final HotStuffBlock block;
    private final HotStuffQuorumCertificate qc; // nulo somente em PREPARE sem highQC; exposto via Optional
    private final int consensusSize;

    private HotStuffProposalMessage(Phase phase, long view, Node sender, HotStuffBlock block,
                                    HotStuffQuorumCertificate qc, int consensusSize, int totalSize) {
        super(totalSize);
        this.phase = phase;
        this.view = view;
        this.sender = sender;
        this.block = block;
        this.qc = qc;
        this.consensusSize = consensusSize;
    }

    /**
     * PREPARE com highQC: {@code highQc.phase == PREPARE}, {@code view >= highQc.view} e {@code n} igual ao tamanho
     * de rede do QC. O bloco pode diferir do do QC (estende-o, a conferir com o artigo). O config e o do QC
     * ({@link HotStuffQuorumCertificate#getConfig()}).
     */
    public static HotStuffProposalMessage prepare(long view, Node leader, HotStuffBlock block,
                                                  HotStuffQuorumCertificate highQc, int n) {
        requireCommon(view, leader, block);
        requireQc(highQc, HotStuffVote.Phase.PREPARE, Phase.PREPARE);
        requireViewNotBeforeQc(view, highQc);
        if (n != highQc.getNetworkSize()) {
            throw new IllegalArgumentException("n=" + n + " difere do tamanho de rede do QC: " + highQc.getNetworkSize());
        }
        highQc.getConfig().validateFor(n);
        requireSenderInNetwork(leader, n);
        return build(Phase.PREPARE, view, leader, block, highQc, highQc.getConfig());
    }

    /**
     * PREPARE sem highQC (ausencia EXPLICITA: primeira visao, ou nenhuma replica conhece prepareQC; a conferir
     * com o artigo que o valor inicial e ⊥). O lider deve ter id em [0, n), n &gt;= 4.
     */
    public static HotStuffProposalMessage prepareWithoutHighQc(long view, Node leader, HotStuffBlock block,
                                                               HotStuffConsensusConfig config, int n) {
        requireCommon(view, leader, block);
        requireConfig(config);
        config.validateFor(n);
        requireSenderInNetwork(leader, n);
        return build(Phase.PREPARE, view, leader, block, null, config);
    }

    /** PRE_COMMIT com o prepareQC: {@code qc.phase == PREPARE}, mesmo bloco, {@code view >= qc.view}. */
    public static HotStuffProposalMessage preCommit(long view, Node leader, HotStuffBlock block,
                                                    HotStuffQuorumCertificate prepareQc) {
        return chained(Phase.PRE_COMMIT, HotStuffVote.Phase.PREPARE, view, leader, block, prepareQc);
    }

    /** COMMIT com o precommitQC: {@code qc.phase == PRE_COMMIT}, mesmo bloco, {@code view >= qc.view}. */
    public static HotStuffProposalMessage commit(long view, Node leader, HotStuffBlock block,
                                                 HotStuffQuorumCertificate precommitQc) {
        return chained(Phase.COMMIT, HotStuffVote.Phase.PRE_COMMIT, view, leader, block, precommitQc);
    }

    /** DECIDE com o commitQC: {@code qc.phase == COMMIT}, mesmo bloco, {@code view >= qc.view}. */
    public static HotStuffProposalMessage decide(long view, Node leader, HotStuffBlock block,
                                                 HotStuffQuorumCertificate commitQc) {
        return chained(Phase.DECIDE, HotStuffVote.Phase.COMMIT, view, leader, block, commitQc);
    }

    private static HotStuffProposalMessage chained(Phase phase, HotStuffVote.Phase requiredQcPhase, long view,
                                                   Node leader, HotStuffBlock block,
                                                   HotStuffQuorumCertificate qc) {
        requireCommon(view, leader, block);
        requireQc(qc, requiredQcPhase, phase);
        requireViewNotBeforeQc(view, qc);
        // Blocos nao tem equals/hashCode: identidade e o criterio (o simulador compartilha a instancia).
        if (qc.getBlock() != block) {
            throw new IllegalArgumentException(phase + " exige QC sobre o mesmo bloco da proposta");
        }
        requireSenderInNetwork(leader, qc.getNetworkSize());
        return build(phase, view, leader, block, qc, qc.getConfig());
    }

    private static HotStuffProposalMessage build(Phase phase, long view, Node leader, HotStuffBlock block,
                                                 HotStuffQuorumCertificate qc, HotStuffConsensusConfig config) {
        long consensus = (long) block.getHash().getSize() + config.getMessageOverheadBytes()
                + (qc == null ? 0L : qc.getSize());
        long total = consensus + (phase == Phase.PREPARE ? block.getSize() : 0L);
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tamanho da proposta excede int: " + total);
        }
        return new HotStuffProposalMessage(phase, view, leader, block, qc, (int) consensus, (int) total);
    }

    private static void requireCommon(long view, Node leader, HotStuffBlock block) {
        if (leader == null) {
            throw new IllegalArgumentException("leader obrigatorio");
        }
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
    }

    private static void requireConfig(HotStuffConsensusConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
    }

    private static void requireQc(HotStuffQuorumCertificate qc, HotStuffVote.Phase required, Phase phase) {
        if (qc == null) {
            throw new IllegalArgumentException(phase + " exige QC (fase " + required + ")");
        }
        if (qc.getPhase() != required) {
            throw new IllegalArgumentException(phase + " exige QC de fase " + required + ", recebido " + qc.getPhase());
        }
    }

    private static void requireViewNotBeforeQc(long view, HotStuffQuorumCertificate qc) {
        if (view < qc.getView()) {
            throw new IllegalArgumentException("proposta nao pode carregar QC de visao futura: view=" + view
                    + ", qc.view=" + qc.getView());
        }
    }

    private static void requireSenderInNetwork(Node sender, int n) {
        if (sender.getNodeID() < 0 || sender.getNodeID() >= n) {
            throw new IllegalArgumentException("leader fora de [0," + n + "): " + sender.getNodeID());
        }
    }

    public Phase getPhase() { return phase; }
    public long getView() { return view; }
    public Node getSender() { return sender; }
    public HotStuffBlock getBlock() { return block; }
    /** {@code true} se a proposta carrega QC (sempre, salvo PREPARE sem highQC). */
    public boolean hasQc() { return qc != null; }
    /** QC da fase anterior; vazio somente em PREPARE sem highQC. */
    public Optional<HotStuffQuorumCertificate> getQc() { return Optional.ofNullable(qc); }
    /** Bytes de protocolo: hash + overhead + QC (se houver); NAO inclui a carga do bloco. */
    public int getConsensusSize() { return consensusSize; }
    /** Bytes que vao ao fio (== {@link #getSize()}): consenso, mais {@code block.getSize()} em PREPARE. */
    public int getTotalSize() { return getSize(); }
}
