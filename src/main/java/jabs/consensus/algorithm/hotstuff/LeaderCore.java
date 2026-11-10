package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage.Phase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Nucleo de decisao do LADO DO LIDER do caminho honesto do HotStuff basico (Yin, Malkhi, Reiter, Gueta, Abraham,
 * PODC 2019, Algoritmo 2, papel LEADER). Par do {@link ReplicaCore}: classe pura, sem rede, sem simulador, sem
 * relogio, sem {@code Random}; recebe entradas (NEW_VIEW, votos) e devolve {@link LeaderAction} que o dono (o
 * {@code HotStuff}, na tarefa 2b-2) executa. NAO e thread-safe. Todo descarte e contado em {@link DiscardCounters}.
 *
 * <p><b>Fonte (conferida em 2026-11-09 contra Yin et al., arXiv:1803.05069v6 = PODC 2019, secao 4.1 "prepare phase"
 * e secao 4.3, Algoritmo 2, papel "as a leader").</b> Linhas do Algoritmo 2:
 * <ol>
 *   <li>PREPARE (linhas 3-8): <i>wait for (n-f) new-view messages</i> (linha 5); {@code highQC} = o
 *       {@code justify} de maior visao entre elas (linha 6); {@code curProposal = createLeaf(highQC.node, cmd)}
 *       (linha 7); {@code broadcast Msg(prepare, curProposal, highQC)} (linha 8);</li>
 *   <li>PRE_COMMIT (linhas 15-18): com {@code n-f} votos PREPARE da visao, forma {@code prepareQC} e difunde
 *       {@code pre-commit(prepareQC)};</li>
 *   <li>COMMIT (linhas 24-27): com {@code n-f} votos PRE_COMMIT, forma {@code precommitQC} e difunde
 *       {@code commit(precommitQC)};</li>
 *   <li>DECIDE (linhas 33-36): com {@code n-f} votos COMMIT, forma {@code commitQC} e difunde
 *       {@code decide(commitQC)}.</li>
 * </ol>
 * {@code n-f} = {@link HotStuffConsensusConfig#quorumSize(int)} = 2f+1, comparacao {@code >=}.
 *
 * <h3>Visao inicial: SEGUE O MESMO CAMINHO das demais</h3>
 * O artigo nao dispensa o quorum na primeira visao. Secao 4.1 (traducao livre): o new-view e enviado por uma replica
 * ao transitar para {@code viewNumber} (<b>including the first view</b>, no original) e carrega o maior prepareQC
 * que a replica recebeu (bottom se nenhum); Algoritmo 2, linha 4: <i>"we assume special new-view messages from view 0"</i>. Portanto o
 * lider da visao inicial SO propoe ao ter {@code n-f} NEW_VIEW distintos para ela, todos com prepareQC = bottom
 * (vazio), e entao {@code highQC = bottom} e o pai e o genesis. Nao ha atalho: {@link #onNewView} e a unica porta
 * de entrada do PREPARE, para a visao inicial e para as demais.
 *
 * <p><b>O que o nucleo exige e o que o dono (HotStuff, tarefa 2b-2) precisa fazer.</b>
 * <ul>
 *   <li>O nucleo exige: {@code onNewView(initialView, remetente, Optional.empty())} para {@code n-f} remetentes
 *       distintos. Repetidos do mesmo remetente sao contados como DUPLICATE_NEW_VIEW e nao somam para o quorum; o
 *       PREPARE sai no NEW_VIEW que completa {@code n-f} (responsivo), e os que chegam depois dele (alem de
 *       {@code n-f}) sao contados como UNNEEDED_NEW_VIEW.
 *       NEW_VIEW da visao inicial com prepareQC presente e incoerente (INCOHERENT_QC): na visao inicial nao ha QC.</li>
 *   <li>O dono precisa: no start, CADA replica (inclusive o proprio lider, por autoentrega local) emite um NEW_VIEW
 *       sem QC ({@code HotStuffQcMessage.newViewWithoutPrepareQc}) ao lider da visao inicial, pela rede simulada.
 *       Assim existe um pacote por replica (menos o lider), de tamanho de NEW_VIEW sem QC, que
 *       {@code HotStuff.getUnattributedTraffic} ja contabiliza: o trafego dos NEW_VIEW iniciais passa a ser
 *       MEDIDO, e nao omitido.</li>
 *   <li>Ponto de atencao de numeracao de visao (conferido no artigo): o NEW_VIEW e carimbado com a visao CORRENTE
 *       do remetente ({@code Msg} estampa {@code curView}, Algoritmo 1 linha 3) e o lider de {@code curView} espera
 *       os NEW_VIEW da visao {@code curView-1} (Algoritmo 2 linha 5). Secao 4.2: {@code viewNumber} comeca em 1, e
 *       os NEW_VIEW iniciais sao "da visao 0". {@code HotStuff.storeNewView} ja deriva o destino como
 *       {@code message.view + 1}, o que concorda com o artigo; mas {@code HotStuffQcMessage} exige
 *       {@code view >= 0}, entao os NEW_VIEW iniciais so sao representaveis com {@code message.view = 0}, isto e,
 *       {@code initialView = 1} (fiel ao artigo; o lider da primeira visao passa a ser {@code leaderOf(1)}). Com
 *       {@code initialView = 0} o NEW_VIEW inicial exigiria {@code view = -1}. Este nucleo aceita qualquer
 *       {@code initialView >= 0}; a escolha, e o ajuste de {@code ReplicaState}, sao do dono.</li>
 *   <li>Voto do proprio lider no NEW_VIEW: o lider tambem e replica (artigo, secao 3, "broadcast" inclui o
 *       proprio remetente); seu NEW_VIEW chega a {@link #onNewView} por autoentrega local, sem pacote e sem
 *       trafego (regra do projeto), e conta como UM dos {@code n-f}.</li>
 * </ul>
 *
 * <p>Se {@code highQC} e bottom numa visao posterior (todos os n-f NEW_VIEW sem QC), o pai e o genesis
 * (createLeaf do genesis; [HIPOTESE]: o artigo parte de prepareQC = bottom e do genesis conhecido).
 * Desempate de {@code highQC} com a mesma visao: o primeiro recebido ({@link NewViewAggregator}).
 *
 * <h3>Voto do lider em si (DECISAO)</h3>
 * O lider e tambem replica (artigo). O nucleo NAO conta voto proprio por conta propria: o voto do lider chega por
 * {@link #onVote} como o de qualquer outra replica, e conta como UM dos 2f+1. O dono entrega as mensagens de fase ao
 * proprio lider por <b>autoentrega local</b> (chamada direta ao {@link ReplicaCore}, sem pacote, sem latencia e sem
 * trafego; regra do projeto: pacote que nao passa pela rede simulada NAO e trafego) e a replica local vota pelo
 * caminho normal, que em {@code HotStuff.sendVote} ja chama {@code onVote} direto quando o lider e o proprio no.
 * Assim existe um unico caminho de contagem (o agregador) e nenhum voto fantasma. O dono deve evitar reentrada: a
 * execucao de uma {@link LeaderAction} gera votos locais que chamam {@link #onVote} de dentro da execucao; use fila
 * (ou evento de atraso 0) em vez de recursao.
 *
 * <h3>Propriedades</h3>
 * <ul>
 *   <li><b>Uma proposta por fase e visao.</b> A fase so avanca na primeira formacao do QC (o agregador forma o QC
 *       uma unica vez por balde, e o estado da visao so aceita voto de fase ainda nao concluida); {@link
 *       BlockProposer} e chamado no maximo uma vez por visao.</li>
 *   <li><b>Responsividade.</b> Nenhum temporizador: o lider avanca na chamada que completa o quorum, sem esperar
 *       Delta nem os votos restantes (os f votos tardios sao contados como {@link DiscardReason#LATE_VOTE}).</li>
 *   <li><b>Nada some em silencio.</b> Voto/NEW_VIEW rejeitado incrementa um {@link DiscardReason}.</li>
 * </ul>
 *
 * <h3>Visao abandonada pela replica (D3a-2a-2c-3, rodada 2)</h3>
 * Artigo (Yin et al., arXiv:1803.05069, secao 4.3, texto logo antes do Algoritmo 1): <i>"A nextView interrupt aborts
 * all operations in any as block, and jumps to the 'Finally' block."</i>; Algoritmo 2, linhas 41-43 ("Finally",
 * "nextView interrupt: goto this line if nextView(curView) is called during 'wait for' in any phase", "send
 * Msg(new-view, bottom, prepareQC) to leader(curView+1)"). Os papeis leader e replica dividem o mesmo {@code curView}
 * (um no e as duas coisas): quando {@code curView} passa de T, o bloco "as a leader" da visao T e abortado, seja ele
 * a espera dos NEW_VIEW (linha 5) ou a espera dos votos (linhas 16, 25, 34). Este nucleo nao conhece relogio nem
 * rede, entao le a visao corrente da PROPRIA replica por um ponto de leitura injetado ({@link LongSupplier}, somente
 * leitura, monotono). Regra exata, com {@code R} = visao corrente da replica deste no:
 * <ul>
 *   <li><b>NEW_VIEW para T:</b> {@code R > T} = visao abandonada: descartado e contado em
 *       {@link DiscardReason#LEADER_VIEW_ABANDONED}, e NAO e armazenado (nao entra no agregador; nunca completa
 *       quorum). {@code R == T} ou {@code R < T} NAO e abandono e segue o fluxo normal: os NEW_VIEW de T sao enviados
 *       por replicas que terminam T-1, e a replica deste lider pode terminar T-1 depois delas. A checagem vem DEPOIS
 *       de "visao ja iniciada" (que continua UNNEEDED_NEW_VIEW): o regime sem timeout nao muda de contador.</li>
 *   <li><b>Voto da visao V:</b> {@code R > V} e o voto seria o que conta para o quorum da fase pendente: descartado e
 *       contado em {@code LEADER_VIEW_ABANDONED} ANTES de entrar no agregador, entao nenhuma fase nova (PRE_COMMIT,
 *       COMMIT, DECIDE) sai de visao abandonada. Voto de fase ja concluida continua LATE_VOTE (a ordem das checagens
 *       existentes e preservada). Votos ja agregados antes do abandono ficam inertes: o QC so se forma na chamada que
 *       completa o quorum, e essa e recusada.</li>
 *   <li>Nao ha estado novo de "abandonada" no lider: {@code R} e monotono, entao a condicao e consultada a cada
 *       entrada. Seguranca ({@code safeNode}/{@code lockedQC}) e da replica e nao muda.</li>
 * </ul>
 *
 * <p><b>Memoria:</b> um {@code ViewState} por visao conduzida por este no, sem poda (limitado pelo horizonte
 * simulado e por 1/n das visoes); os agregadores guardam um balde por (fase, visao, bloco) e por visao de NEW_VIEW.
 * <b>Limites declarados (nao tratados aqui):</b> sincronizacao de blocos faltantes (se o lider nao tem o bloco do
 * highQC, a propria replica local o contara como UNKNOWN_PARENT) e o catch-up de visao (a troca de visao por timeout existe e e tratada acima).
 */
public final class LeaderCore {
    /** Ultima fase JA difundida na visao; os ordinais coincidem com {@link HotStuffVote.Phase}. */
    private enum Sent { PREPARE, PRE_COMMIT, COMMIT, DECIDE }

    private static final class ViewState {
        final HotStuffBlock curProposal;
        Sent sent = Sent.PREPARE;

        ViewState(HotStuffBlock curProposal) {
            this.curProposal = curProposal;
        }
    }

    private final int n;
    private final int ownId;
    private final long initialView;
    private final ViewLeaders leaders;
    private final HotStuffConsensusConfig config;
    private final HotStuffBlock genesis;
    private final BlockProposer proposer;
    private final VoteAggregator<HotStuffBlock> votes;
    private final NewViewAggregator newViews;
    private final DiscardCounters discards;
    private final LongSupplier replicaView;
    private final int quorum;
    private final Map<Long, ViewState> views = new LinkedHashMap<>();
    private long highestStarted = -1L;

    /**
     * @param n numero de replicas (&gt;= 4)
     * @param ownId id deste no em [0, n)
     * @param initialView visao inicial (a mesma do {@link ReplicaState}); &gt;= 0
     * @param leaders lider por visao
     * @param config configuracao (dona do tamanho dos QCs)
     * @param genesis bloco genesis (pai da primeira proposta quando nao ha highQC)
     * @param proposer cria o bloco da visao (createLeaf); ponto de extensao do dono
     * @param votes agregador de votos (o do dono, para que as consultas existentes continuem validas)
     * @param newViews agregador de NEW_VIEW (idem)
     * @param discards contadores de descarte (os mesmos do {@link ReplicaCore} do mesmo no)
     * @param replicaView leitura (somente leitura) da visao corrente da REPLICA deste mesmo no; obrigatorio, sem
     *                    padrao: esquecer de injetar desligaria a regra de visao abandonada em silencio
     * @throws IllegalArgumentException se algum objeto for nulo, {@code n} &lt; 4, {@code ownId} fora de [0, n) ou
     *                                  {@code initialView} &lt; 0
     */
    public LeaderCore(int n, int ownId, long initialView, ViewLeaders leaders, HotStuffConsensusConfig config,
                      HotStuffBlock genesis, BlockProposer proposer, VoteAggregator<HotStuffBlock> votes,
                      NewViewAggregator newViews, DiscardCounters discards, LongSupplier replicaView) {
        if (leaders == null || config == null || genesis == null || proposer == null || votes == null
                || newViews == null || discards == null || replicaView == null) {
            throw new IllegalArgumentException("leaders, config, genesis, proposer, votes, newViews, discards e replicaView sao obrigatorios");
        }
        this.quorum = HotStuffConsensusConfig.quorumSize(n); // lanca IllegalArgumentException se n < 4
        if (ownId < 0 || ownId >= n) {
            throw new IllegalArgumentException("ownId fora de [0," + n + "): " + ownId);
        }
        if (initialView < 0) {
            throw new IllegalArgumentException("initialView deve ser >= 0: " + initialView);
        }
        this.n = n;
        this.ownId = ownId;
        this.initialView = initialView;
        this.leaders = leaders;
        this.config = config;
        this.genesis = genesis;
        this.proposer = proposer;
        this.votes = votes;
        this.newViews = newViews;
        this.discards = discards;
        this.replicaView = replicaView;
    }

    public DiscardCounters discards() { return discards; }

    /** Este no ja difundiu PREPARE na visao? */
    public boolean hasStarted(long view) { return views.containsKey(view); }

    /** curProposal da visao, se este no ja propos nela. */
    public Optional<HotStuffBlock> curProposal(long view) {
        ViewState st = views.get(view);
        return st == null ? Optional.empty() : Optional.of(st.curProposal);
    }

    /**
     * NEW_VIEW (replica -&gt; lider) para a visao de destino {@code targetView}. Ordem das checagens, cada uma
     * contada: id fora de [0,n) (SENDER_OUT_OF_RANGE); este no nao e o lider de {@code targetView}
     * (NOT_VIEW_LEADER); QC na visao inicial (la so ha bottom), ou de fase diferente de PREPARE, de visao
     * &gt;= {@code targetView} ou de outro n (INCOHERENT_QC); visao ja iniciada, ou menor que a mais alta iniciada (UNNEEDED_NEW_VIEW: nao pode mais mudar
     * o highQC); visao que a propria replica ja abandonou, {@code replicaView > targetView}
     * (LEADER_VIEW_ABANDONED; {@code ==} e {@code <} NAO sao abandono); remetente repetido (DUPLICATE_NEW_VIEW). Ao completar {@code n-f} NEW_VIEW distintos, propoe
     * PREPARE com o highQC deles.
     *
     * @return acoes a executar (vazia, ou um PREPARE)
     */
    public List<LeaderAction> onNewView(long targetView, int senderId, Optional<HotStuffQuorumCertificate> prepareQc) {
        if (targetView < 0 || prepareQc == null) {
            throw new IllegalArgumentException("targetView >= 0 e prepareQc (Optional) obrigatorios");
        }
        List<LeaderAction> out = new ArrayList<>();
        if (senderId < 0 || senderId >= n) {
            discards.count(DiscardReason.SENDER_OUT_OF_RANGE);
            return out;
        }
        if (leaders.leaderOf(targetView) != ownId) {
            discards.count(DiscardReason.NOT_VIEW_LEADER);
            return out;
        }
        if (prepareQc.isPresent() && (targetView == initialView || !newViewQcCoherent(targetView, prepareQc.get()))) {
            discards.count(DiscardReason.INCOHERENT_QC);
            return out;
        }
        if (targetView <= highestStarted) {
            discards.count(DiscardReason.UNNEEDED_NEW_VIEW);
            return out;
        }
        if (replicaView.getAsLong() > targetView) { // nextView ja abortou o bloco de lider desta visao: nao armazena
            discards.count(DiscardReason.LEADER_VIEW_ABANDONED);
            return out;
        }
        if (!newViews.add(targetView, senderId, prepareQc)) {
            discards.count(DiscardReason.DUPLICATE_NEW_VIEW);
            return out;
        }
        if (newViews.count(targetView) >= quorum) { // quorum de NEW_VIEW: n-f, comparacao >=
            startView(targetView, newViews.highQc(targetView), out);
        }
        return out;
    }

    /**
     * Voto de uma replica (inclusive o do proprio lider, ver "Voto do lider em si"). Ordem das checagens, cada uma
     * contada: id fora de [0,n) (SENDER_OUT_OF_RANGE); este no nao conduz a visao do voto (NOT_VIEW_LEADER); a
     * visao ainda nao foi iniciada aqui (VOTE_VIEW_NOT_STARTED); bloco diferente do curProposal da visao
     * (VOTE_WRONG_BLOCK); fase que ainda nao foi difundida (VOTE_PHASE_OUT_OF_ORDER); fase cujo QC ja foi formado
     * (LATE_VOTE); visao que a propria replica ja abandonou, {@code replicaView > view}
     * (LEADER_VIEW_ABANDONED, antes de agregar); voto repetido (DUPLICATE_VOTE). Quando os votos distintos da fase chegam a {@code n-f}, forma o
     * QC e devolve a difusao da fase seguinte.
     *
     * @return acoes a executar (vazia, ou a proxima fase)
     */
    public List<LeaderAction> onVote(HotStuffVote vote) {
        if (vote == null) {
            throw new IllegalArgumentException("vote obrigatorio");
        }
        List<LeaderAction> out = new ArrayList<>();
        int voterId = vote.getVoter().getNodeID();
        if (voterId < 0 || voterId >= n) {
            discards.count(DiscardReason.SENDER_OUT_OF_RANGE);
            return out;
        }
        long view = vote.getView();
        if (leaders.leaderOf(view) != ownId) {
            discards.count(DiscardReason.NOT_VIEW_LEADER);
            return out;
        }
        ViewState st = views.get(view);
        if (st == null) {
            discards.count(DiscardReason.VOTE_VIEW_NOT_STARTED);
            return out;
        }
        if (vote.getBlock() != st.curProposal) {
            discards.count(DiscardReason.VOTE_WRONG_BLOCK);
            return out;
        }
        HotStuffVote.Phase phase = vote.getPhase();
        if (st.sent.ordinal() < phase.ordinal()) {
            discards.count(DiscardReason.VOTE_PHASE_OUT_OF_ORDER);
            return out;
        }
        if (st.sent.ordinal() > phase.ordinal()) {
            discards.count(DiscardReason.LATE_VOTE);
            return out;
        }
        if (replicaView.getAsLong() > view) { // nextView abortou a espera de votos: nao agrega, logo nao forma QC
            discards.count(DiscardReason.LEADER_VIEW_ABANDONED);
            return out;
        }
        if (!votes.add(vote)) {
            discards.count(DiscardReason.DUPLICATE_VOTE);
            return out;
        }
        if (votes.distinctVoters(phase, view, st.curProposal) < quorum) { // quorum: n-f, comparacao >=
            return out;
        }
        Optional<HotStuffQuorumCertificate> qc = votes.tryFormQc(phase, view, st.curProposal, config, n);
        if (qc.isEmpty()) {
            throw new IllegalStateException("quorum atingido mas o agregador nao formou o QC: fase " + phase
                    + ", visao " + view);
        }
        advance(view, st, qc.get(), out);
        return out;
    }

    private boolean newViewQcCoherent(long targetView, HotStuffQuorumCertificate qc) {
        return qc.getPhase() == HotStuffVote.Phase.PREPARE && qc.getView() < targetView && qc.getNetworkSize() == n;
    }

    private void startView(long view, Optional<HotStuffQuorumCertificate> highQc, List<LeaderAction> out) {
        if (views.containsKey(view)) {
            throw new IllegalStateException("PREPARE ja difundido na visao " + view + " (uma proposta por fase e visao)");
        }
        HotStuffBlock parent = highQc.isPresent() ? highQc.get().getBlock() : genesis;
        HotStuffBlock block = proposer.propose(view, parent);
        if (block == null || block.getFixedParent() != parent || block.getHeight() != parent.getHeight() + 1) {
            throw new IllegalStateException("BlockProposer deve devolver filho direto do pai pedido (visao " + view + ")");
        }
        views.put(view, new ViewState(block));
        highestStarted = Math.max(highestStarted, view);
        out.add(LeaderAction.prepare(view, block, highQc));
    }

    private void advance(long view, ViewState st, HotStuffQuorumCertificate qc, List<LeaderAction> out) {
        switch (st.sent) {
            case PREPARE:
                st.sent = Sent.PRE_COMMIT;
                out.add(LeaderAction.preCommit(view, st.curProposal, qc));
                break;
            case PRE_COMMIT:
                st.sent = Sent.COMMIT;
                out.add(LeaderAction.commit(view, st.curProposal, qc));
                break;
            case COMMIT:
                st.sent = Sent.DECIDE;
                out.add(LeaderAction.decide(view, st.curProposal, qc));
                break;
            default:
                throw new IllegalStateException("QC formado depois do DECIDE na visao " + view);
        }
    }

    /** Fase da proposta que as replicas esperam depois do QC de {@code phase} (apoio a testes e ao dono). */
    public static Phase nextPhase(HotStuffVote.Phase phase) {
        if (phase == null) {
            throw new IllegalArgumentException("phase obrigatoria");
        }
        switch (phase) {
            case PREPARE: return Phase.PRE_COMMIT;
            case PRE_COMMIT: return Phase.COMMIT;
            case COMMIT: return Phase.DECIDE;
            default: throw new IllegalStateException("fase desconhecida: " + phase);
        }
    }
}
