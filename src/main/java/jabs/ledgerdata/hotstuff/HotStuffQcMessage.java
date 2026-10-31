package jabs.ledgerdata.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.BasicData;
import jabs.network.node.nodes.Node;

import java.util.Optional;

/**
 * Mensagem do HotStuff basico cujo conteudo e um QC e que NAO e voto: NEW_VIEW (replica -> lider, com o
 * prepareQC mais alto que a replica conhece) e DECIDE (lider -> replicas, com o commitQC).
 * Tamanho = tamanho do QC carregado (que ja inclui hash do bloco, overhead de cabecalho, bitmap de votantes e assinatura(s)); nao ha
 * assinatura individual de voto adicional. Este tipo so modela a mensagem; o fluxo e do algoritmo.
 *
 * <p>Combinacoes invalidas sao irrepresentaveis: nao ha construtor publico, so as fabricas abaixo, e cada uma
 * impoe a regra do seu tipo ({@link IllegalArgumentException} na construcao):
 * <ul>
 *   <li><b>DECIDE</b> ({@link #decide}): exige QC de fase {@link HotStuffVote.Phase#COMMIT} (o commitQC) e
 *       {@code view >= qc.view}. No artigo (Yin et al., 2019, alg. 2) o DECIDE e enviado na visao em que o commitQC
 *       se formou, ou seja, {@code view == qc.view}; a igualdade estrita NAO e imposta aqui (a conferir com o
 *       artigo), so a coerencia minima: a mensagem nao carrega QC de visao futura.</li>
 *   <li><b>NEW_VIEW com QC</b> ({@link #newView}): exige QC de fase {@link HotStuffVote.Phase#PREPARE} (o
 *       prepareQC mais alto da replica) e {@code view >= qc.view}: a mensagem nunca carrega QC de visao futura.
 *       {@code view} e a visao corrente da replica remetente (a que expirou) ao enviar; o destino e o lider da
 *       visao seguinte. Se o artigo definir {@code view} como a visao de destino, a regra {@code view >= qc.view}
 *       continua valendo (e a mais fraca que serve as duas leituras); a conferir com o artigo.</li>
 *   <li><b>NEW_VIEW sem QC</b> ({@link #newViewWithoutPrepareQc}): modelo EXPLICITO da ausencia de prepareQC
 *       (o valor inicial "⊥" do artigo: a replica ainda nao viu nenhum prepareQC, por exemplo na primeira visao ou
 *       quando todas as visoes anteriores expiraram antes do PREPARE; a conferir com o artigo que o valor inicial
 *       seja ⊥ e nao o QC do genesis). Sem {@code null} silencioso: {@link #hasQc()} diz se ha QC e
 *       {@link #getQc()} devolve {@link Optional}. DECIDE nunca pode ficar sem QC. Tamanho dessa mensagem = apenas
 *       {@code messageOverheadBytes} (suposicao: a definir com o humano).</li>
 *   <li>Remetente obrigatorio e com id em [0, n), n = tamanho de rede do QC (ou o informado em
 *       {@link #newViewWithoutPrepareQc}). Que o remetente do DECIDE seja o lider da visao e do algoritmo, nao
 *       desta camada.</li>
 * </ul>
 */
public final class HotStuffQcMessage extends BasicData {
    public enum Type { NEW_VIEW, DECIDE }

    private final Node sender;
    private final Type type;
    private final long view;
    private final HotStuffQuorumCertificate qc; // nulo somente em NEW_VIEW sem prepareQC; exposto via Optional

    private HotStuffQcMessage(Node sender, Type type, long view, HotStuffQuorumCertificate qc, int size) {
        super(size);
        this.sender = sender;
        this.type = type;
        this.view = view;
        this.qc = qc;
    }

    /** DECIDE do lider com o commitQC: {@code qc.phase == COMMIT} e {@code view >= qc.view}. */
    public static HotStuffQcMessage decide(Node sender, long view, HotStuffQuorumCertificate commitQc) {
        requireSenderAndView(sender, view);
        requireQc(commitQc);
        if (commitQc.getPhase() != HotStuffVote.Phase.COMMIT) {
            throw new IllegalArgumentException("DECIDE exige QC de fase COMMIT, recebido " + commitQc.getPhase());
        }
        requireViewNotBeforeQc(view, commitQc);
        requireSenderInNetwork(sender, commitQc.getNetworkSize());
        return new HotStuffQcMessage(sender, Type.DECIDE, view, commitQc, computeSize(commitQc));
    }

    /** NEW_VIEW da replica com o prepareQC mais alto que ela conhece: {@code qc.phase == PREPARE} e {@code view >= qc.view}. */
    public static HotStuffQcMessage newView(Node sender, long view, HotStuffQuorumCertificate prepareQc) {
        requireSenderAndView(sender, view);
        requireQc(prepareQc);
        if (prepareQc.getPhase() != HotStuffVote.Phase.PREPARE) {
            throw new IllegalArgumentException("NEW_VIEW exige prepareQC (fase PREPARE), recebido "
                    + prepareQc.getPhase());
        }
        requireViewNotBeforeQc(view, prepareQc);
        requireSenderInNetwork(sender, prepareQc.getNetworkSize());
        return new HotStuffQcMessage(sender, Type.NEW_VIEW, view, prepareQc, computeSize(prepareQc));
    }

    /**
     * NEW_VIEW de replica que ainda nao conhece nenhum prepareQC (⊥ do artigo). Ausencia explicita, sem QC.
     *
     * @param n numero de nos da rede (&gt;= 4); o remetente deve estar em [0, n)
     */
    public static HotStuffQcMessage newViewWithoutPrepareQc(Node sender, long view, int n,
                                                            HotStuffConsensusConfig config) {
        requireSenderAndView(sender, view);
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        config.validateFor(n);
        requireSenderInNetwork(sender, n);
        return new HotStuffQcMessage(sender, Type.NEW_VIEW, view, null, computeSizeWithoutQc(config));
    }

    private static void requireSenderAndView(Node sender, long view) {
        if (sender == null) {
            throw new IllegalArgumentException("sender obrigatorio");
        }
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
    }

    private static void requireQc(HotStuffQuorumCertificate qc) {
        if (qc == null) {
            throw new IllegalArgumentException("qc obrigatorio");
        }
    }

    private static void requireViewNotBeforeQc(long view, HotStuffQuorumCertificate qc) {
        if (view < qc.getView()) {
            throw new IllegalArgumentException("mensagem nao pode carregar QC de visao futura: view=" + view
                    + ", qc.view=" + qc.getView());
        }
    }

    private static void requireSenderInNetwork(Node sender, int n) {
        if (sender.getNodeID() < 0 || sender.getNodeID() >= n) {
            throw new IllegalArgumentException("sender fora de [0," + n + "): " + sender.getNodeID());
        }
    }

    /** Tamanho em bytes de uma mensagem que carrega {@code qc}: o do proprio QC. */
    public static int computeSize(HotStuffQuorumCertificate qc) {
        requireQc(qc);
        return qc.getSize();
    }

    /** Tamanho de um NEW_VIEW sem QC: so o overhead de cabecalho (suposicao: a definir com o humano). */
    public static int computeSizeWithoutQc(HotStuffConsensusConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        return config.getMessageOverheadBytes();
    }

    public Node getSender() { return sender; }
    public Type getType() { return type; }
    public long getView() { return view; }
    /** {@code true} se a mensagem carrega QC (sempre em DECIDE; em NEW_VIEW, salvo ⊥). */
    public boolean hasQc() { return qc != null; }
    /** O QC carregado; vazio somente em NEW_VIEW sem prepareQC. */
    public Optional<HotStuffQuorumCertificate> getQc() { return Optional.ofNullable(qc); }
}
