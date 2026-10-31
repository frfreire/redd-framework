package jabs.ledgerdata.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.BasicData;
import jabs.network.node.nodes.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quorum Certificate: prova de que um quorum votou (fase, visao, bloco).
 * Tamanho = hash + overhead + identificadores dos votantes + assinatura(s):
 * <ul>
 *   <li>identificadores: BITMAP de {@code ceil(n/8)} bytes, com n o numero de nos da rede informado ao QC,
 *       IGUAL em INDIVIDUAL e AGGREGATED (a vantagem do agregado fica so na assinatura). A codificacao e
 *       suposicao: a definir com o humano (codificacao dos votantes); escolhida por
 *       {@link HotStuffConsensusConfig.VoterEncoding} no config (obrigatorio, sem padrao).</li>
 *   <li>assinatura: INDIVIDUAL = soma das assinaturas dos votantes (cresce com o quorum);
 *       AGGREGATED = assinatura agregada constante.</li>
 * </ul>
 *
 * <p>Um QC invalido nao existe: o construtor (e {@link #computeSize}) lanca {@link IllegalArgumentException} se
 * (a) {@code n < 4} (via {@link HotStuffConsensusConfig#validateFor(int)}); (b) algum votante for nulo ou tiver id
 * fora de [0, n); (c) houver votantes repetidos (por id do no); (d) houver menos que
 * {@link HotStuffConsensusConfig#quorumSize(int)} (2f+1) votantes; (e) fase ou bloco nulos, ou visao &lt; 0.
 * PREPARE, PRE_COMMIT e COMMIT sao QCs legitimos no HotStuff basico (prepareQC, precommitQC, commitQC); qual fase
 * cada MENSAGEM aceita e imposto em {@link HotStuffQcMessage}. Que os votantes tenham de fato votado (assinatura
 * valida) e do algoritmo.
 */
public final class HotStuffQuorumCertificate extends BasicData {
    private final HotStuffVote.Phase phase;
    private final long view;
    private final HotStuffBlock block;
    private final List<Node> voters;
    private final int n;
    private final HotStuffConsensusConfig config;

    public HotStuffQuorumCertificate(HotStuffVote.Phase phase, long view, HotStuffBlock block,
                                     List<Node> voters, int n, HotStuffConsensusConfig config) {
        super(computeSize(block, voters, n, config));
        if (phase == null) {
            throw new IllegalArgumentException("phase obrigatoria");
        }
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
        this.phase = phase;
        this.view = view;
        this.block = block;
        this.n = n;
        this.config = config; // nao nulo: computeSize (no super) ja lancou; o config e imutavel
        this.voters = Collections.unmodifiableList(new ArrayList<>(voters));
    }

    /**
     * Tamanho em bytes de um QC de {@code voters.size()} votantes distintos sobre {@code block}, numa rede
     * de {@code n} nos (n define o tamanho do bitmap de votantes).
     */
    public static int computeSize(HotStuffBlock block, List<Node> voters, int n, HotStuffConsensusConfig config) {
        if (block == null || voters == null || config == null) {
            throw new IllegalArgumentException("block, voters e config obrigatorios");
        }
        config.validateFor(n); // n >= 4
        Set<Integer> ids = new HashSet<>(); // so para detectar duplicata; nunca iterado
        for (Node v : voters) {
            if (v == null) {
                throw new IllegalArgumentException("votante nulo no QC");
            }
            if (v.getNodeID() < 0 || v.getNodeID() >= n) {
                throw new IllegalArgumentException("votante fora de [0," + n + "): " + v.getNodeID());
            }
            if (!ids.add(v.getNodeID())) {
                throw new IllegalArgumentException("votantes duplicados no QC: id " + v.getNodeID());
            }
        }
        int quorum = HotStuffConsensusConfig.quorumSize(n);
        if (voters.size() < quorum) {
            throw new IllegalArgumentException("QC sem quorum: " + voters.size() + " votantes < 2f+1=" + quorum
                    + " (n=" + n + ")");
        }
        long size = (long) block.getHash().getSize() + config.getMessageOverheadBytes()
                + config.voterIdentifiersSize(n)
                + config.qcSignatureSize(config.getSignatureMode(), voters.size());
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tamanho do QC excede int: " + size);
        }
        return (int) size;
    }

    public HotStuffVote.Phase getPhase() { return phase; }
    public long getView() { return view; }
    public HotStuffBlock getBlock() { return block; }
    public List<Node> getVoters() { return voters; }
    /** Numero de nos da rede com que o tamanho do bitmap de votantes foi calculado. */
    public int getNetworkSize() { return n; }
    /**
     * Config com que o QC foi construido (e com que {@link #getSize()} foi calculado). Nunca nulo. E a mesma
     * instancia (o config e imutavel), sem copia; tamanho e semantica do QC nao mudam. Mensagens que carregam este
     * QC devem usar ESTE config para o overhead de cabecalho, em vez de aceitar outro.
     */
    public HotStuffConsensusConfig getConfig() { return config; }
    public int getVoterCount() { return voters.size(); }
}
