package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.node.nodes.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Acumulador de votos do lider no HotStuff basico (Yin et al., PODC 2019, alg. 2): junta votos por
 * (fase, visao, bloco) e forma o {@link HotStuffQuorumCertificate} quando ha quorum. Classe pura: sem rede, sem
 * simulador, sem estado global; NAO e thread-safe.
 *
 * <h3>Regras</h3>
 * <ul>
 *   <li><b>Balde por (fase, visao, bloco).</b> Voto de outra fase, visao ou bloco vai para OUTRO balde e nunca
 *       conta para este. O bloco e comparado por identidade de referencia ({@code HotStuffBlock} nao define
 *       {@code equals/hashCode}; o simulador compartilha a instancia). Dois votos do mesmo no em blocos
 *       diferentes na mesma (fase, visao) (equivocacao) caem em baldes distintos e cada um conta no seu; detectar
 *       equivocacao e do algoritmo, nao do acumulador.</li>
 *   <li><b>Votante distinto por {@code nodeID}.</b> Dentro do balde, o primeiro voto de um nodeID vale e os
 *       seguintes sao descartados (mesmo que venham de outro objeto {@code Node} com o mesmo id).</li>
 *   <li><b>Votos adiantados.</b> {@link #add} aceita o voto sem exigir que a proposta tenha chegado; a ordem
 *       proposta/voto e problema da camada de rede (tarefa 2c-2).</li>
 *   <li><b>Quorum.</b> {@link #tryFormQc} forma o QC exatamente quando votos distintos &gt;=
 *       {@link HotStuffConsensusConfig#quorumSize(int)} (2f+1, comparacao {@code >=}; 2f nao basta), e SO UMA VEZ
 *       por (fase, visao, bloco): depois disso devolve vazio para o mesmo balde, mesmo que cheguem mais votos.</li>
 *   <li><b>Quais votantes entram no QC.</b> Os PRIMEIROS {@code quorumSize(n)} em ordem de insercao
 *       ({@link LinkedHashMap}), e nao todos os acumulados: assim o QC (inclusive seu tamanho, que no modo
 *       INDIVIDUAL cresce com o numero de assinaturas) nao depende de quando {@code tryFormQc} foi chamado.
 *       Mesma sequencia de votos produz o mesmo QC e a mesma ordem de votantes. <b>A conferir com o artigo</b>:
 *       o artigo diz "n-f votos"; escolher exatamente n-f (e nao todos) e decisao de modelagem.</li>
 *   <li>Nenhuma iteracao depende de {@code hashCode} de identidade: o unico {@code Map} iterado e
 *       {@link LinkedHashMap}; o mapa externo e so consultado por chave.</li>
 * </ul>
 *
 * <p><b>Assinatura de {@code tryFormQc}.</b> O acumulador guarda varios baldes, entao a consulta nomeia o balde:
 * {@code tryFormQc(phase, view, block, config, n)}. Votante com id fora de [0, n) e erro de programacao do chamador
 * (o QC o recusaria) entre os que entrariam no QC: {@link IllegalArgumentException}, e o balde NAO e marcado como formado.
 *
 * <p><b>A conferir com o artigo:</b> que o lider conta o proprio voto como um voto qualquer (aqui conta, se for
 * adicionado via {@link #add}); que o QC de uma fase so e formado a partir de votos DA MESMA fase.
 *
 * @param <B> tipo do bloco chaveado (subtipo de {@link HotStuffBlock})
 */
public final class VoteAggregator<B extends HotStuffBlock> {

    /** Identidade do balde. {@code block} e comparado por {@code equals} de Object (identidade). */
    private record Key(HotStuffVote.Phase phase, long view, HotStuffBlock block) { }

    private static final class Bucket {
        final Map<Integer, HotStuffVote> votesByNodeId = new LinkedHashMap<>();
        boolean formed;
    }

    private final Map<Key, Bucket> buckets = new LinkedHashMap<>();

    /**
     * Acumula um voto no balde de (fase, visao, bloco) do proprio voto.
     *
     * @param vote voto (nao nulo, com votante)
     * @return {@code true} se o voto foi contado; {@code false} se o nodeID ja tinha votado neste balde
     * @throws IllegalArgumentException se {@code vote} ou seu votante forem nulos
     */
    public boolean add(HotStuffVote vote) {
        if (vote == null) {
            throw new IllegalArgumentException("vote obrigatorio");
        }
        Node voter = vote.getVoter();
        if (voter == null) {
            throw new IllegalArgumentException("voto sem votante");
        }
        Bucket bucket = buckets.computeIfAbsent(new Key(vote.getPhase(), vote.getView(), vote.getBlock()),
                k -> new Bucket());
        return bucket.votesByNodeId.putIfAbsent(voter.getNodeID(), vote) == null;
    }

    /**
     * Numero de votantes distintos no balde (0 se nunca houve voto).
     *
     * @param phase fase
     * @param view visao
     * @param block bloco
     * @return contagem
     * @throws IllegalArgumentException se algum argumento for nulo
     */
    public int distinctVoters(HotStuffVote.Phase phase, long view, B block) {
        requireKey(phase, block);
        Bucket bucket = buckets.get(new Key(phase, view, block));
        return bucket == null ? 0 : bucket.votesByNodeId.size();
    }

    /**
     * Forma o QC do balde se, e so se, ele ainda nao foi formado e tem votantes distintos &gt;= quorumSize(n).
     *
     * @param phase fase dos votos
     * @param view visao dos votos
     * @param block bloco votado
     * @param config configuracao (dona do tamanho do QC)
     * @param n numero de nos (&gt;= 4)
     * @return o QC, na primeira chamada em que ha quorum; vazio antes disso e em toda chamada posterior
     * @throws IllegalArgumentException se algum argumento for nulo, {@code n} &lt; 4 ou houver votante com id fora de [0,n)
     */
    public Optional<HotStuffQuorumCertificate> tryFormQc(HotStuffVote.Phase phase, long view, B block,
                                                         HotStuffConsensusConfig config, int n) {
        requireKey(phase, block);
        if (config == null) {
            throw new IllegalArgumentException("config obrigatorio");
        }
        int quorum = HotStuffConsensusConfig.quorumSize(n); // lanca IllegalArgumentException se n < 4
        Bucket bucket = buckets.get(new Key(phase, view, block));
        if (bucket == null || bucket.formed || bucket.votesByNodeId.size() < quorum) {
            return Optional.empty();
        }
        List<Node> voters = new ArrayList<>(quorum);
        for (HotStuffVote v : bucket.votesByNodeId.values()) {
            if (voters.size() == quorum) {
                break;
            }
            int id = v.getVoter().getNodeID();
            if (id < 0 || id >= n) {
                throw new IllegalArgumentException("votante fora de [0," + n + "): " + id);
            }
            voters.add(v.getVoter());
        }
        HotStuffQuorumCertificate qc = new HotStuffQuorumCertificate(phase, view, block, voters, n, config);
        bucket.formed = true;
        return Optional.of(qc);
    }

    private static void requireKey(HotStuffVote.Phase phase, HotStuffBlock block) {
        if (phase == null || block == null) {
            throw new IllegalArgumentException("phase e block obrigatorios");
        }
    }
}
