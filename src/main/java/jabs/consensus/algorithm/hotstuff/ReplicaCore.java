package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage.Phase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nucleo de decisao do LADO DA REPLICA do caminho honesto do HotStuff basico (Yin et al., PODC 2019, Algoritmo 2).
 * Classe pura: sem rede, sem simulador. Recebe a mensagem do lider ({@link LeaderMessage}) e devolve as
 * {@link ReplicaAction} que o dono (o {@code HotStuff}) deve executar; muda o {@link ReplicaState} e a arvore
 * local (via {@link BlockAccess}); conta todo descarte em {@link DiscardCounters}. NAO e thread-safe.
 *
 * <h3>Ordem das fases e retencao</h3>
 * Em cada visao a replica espera as fases na ordem PREPARE, PRE_COMMIT, COMMIT, DECIDE ({@link #expectedPhase()}).
 * Uma mensagem do lider e tratada assim, nesta ordem:
 * <ol>
 *   <li>visao menor que a corrente: descartada (OLD_VIEW);</li>
 *   <li>remetente diferente do lider da visao da mensagem: descartada (WRONG_LEADER);</li>
 *   <li>coerencia do QC (fase, visao, bloco, n): incoerente descartada (INCOHERENT_QC). E checada ANTES de reter,
 *       para que mensagem invalida nao ocupe a retencao;</li>
 *   <li>visao futura ALEM do horizonte ({@code visao - visaoCorrente > maxFutureViewHorizon}): descartada e contada
 *       (FUTURE_VIEW_BEYOND_HORIZON) SEM armazenar nada (ver "Memoria da retencao"). Checada depois da coerencia do
 *       QC e antes de reter;</li>
 *   <li>visao futura DENTRO do horizonte, ou fase adiantada na visao corrente (a rede pode reordenar): <b>retida</b>
 *       em {@link LinkedHashMap} (ordem de chegada) por (visao, fase) e reavaliada quando a replica chega la.
 *       Uma proposta honesta valida dentro do horizonte nunca e perdida nem recusada, porque o simulador nao tem
 *       retransmissao e atraso finito nao viola a entrega confiavel do caminho honesto. Segunda mensagem da mesma (visao, fase): a primeira vale, a outra e contada
 *       (DUPLICATE_PENDING). Como o remetente ja foi conferido como o lider da visao da mensagem (passo
 *       anterior), duas mensagens da mesma (visao, fase) so podem vir do MESMO lider; de um lider honesto elas
 *       sao identicas (uma proposta por fase e visao; DECIDE por proposta e por mensagem de QC carregam o mesmo
 *       bloco e QC). Duas diferentes seriam equivocacao do lider (bizantino, ataque de 2d): vale a primeira, e a
 *       segunda e contada, nunca descartada sem contar;</li>
 *   <li>fase ja respondida nesta visao: descartada (DUPLICATE_PHASE);</li>
 *   <li>a fase esperada: processada.</li>
 * </ol>
 * Mensagem retida cuja visao a replica ultrapassa sem ter chegado nela e contada como OLD_VIEW ao ser purgada
 * (descarte correto: ela nao pode mais produzir efeito).
 *
 * <h3>Memoria da retencao</h3>
 * <ol>
 *   <li><b>Defesa implementada (2c-3, rodada 3):</b> a retencao tem horizonte de visoes futuras
 *       {@code H = maxFutureViewHorizon}, parametro OBRIGATORIO do config, sem padrao e validado ({@code > 0}).
 *       Mensagem com {@code visao - visaoCorrente > H} e descartada e contada em
 *       {@link DiscardReason#FUTURE_VIEW_BEYOND_HORIZON} antes de qualquer insercao: o descarte nao consome memoria.
 *       Como a visao corrente so cresce, nenhuma chave retida fica alem de {@code corrente + H}; logo a retencao
 *       tem cota: no maximo {@code 4 * (H + 1) - 1} entradas (4 fases em cada uma das visoes corrente..corrente+H,
 *       menos a fase esperada, que nao e retida), independentemente do que um lider bizantino enviar.
 *       {@link #pendingSize()} e {@link #peakPending()} continuam expostos para observabilidade.</li>
 *   <li><b>Valor de H: a definir com o humano.</b> Nenhum valor de producao foi escolhido nem calibrado; os testes
 *       usam fixtures de teste e os cenarios recebem o valor da config explicita.</li>
 *   <li><b>Custo sobre vivacidade, declarado:</b> uma replica atrasada mais de H visoes em relacao as propostas em
 *       voo perde as propostas alem do horizonte (nao ha retransmissao). Ela so avanca por DECIDE das visoes que
 *       reteve ou por timeout local, entao fica ate H visoes atras do resto e so volta a acompanhar quando o lider
 *       enviar mensagem dentro do novo horizonte; as visoes que ela perdeu nunca chegam a ela e um bloco filho
 *       dessas visoes falha em UNKNOWN_PARENT. Para o quorum ela conta como falha (n-f continua valendo se f nao
 *       for excedido), mas sua participacao so e restabelecida com catch-up.</li>
 *   <li>O horizonte nao impede por si so que um lider bizantino ocupe a retencao ate a cota H com mensagens
 *       coerentes das visoes em que e lider (a cota limita, nao elimina); tambem nao ha como saber, sem catch-up
 *       por QC, se a visao anunciada e real. O consumo de memoria honesto cresce com o atraso ate a cota.</li>
 *   <li>Limite declarado: a recuperacao de replica atrasada por QC (state transfer / catch-up, sincronizacao de
 *       visao tipo Pacemaker/Cogsworth) AINDA NAO EXISTE e continua em aberto (2d). A replica atrasada so alcanca
 *       as visoes futuras pelas proprias mensagens retidas. A troca de visao por TIMEOUT existe ({@link
 *       #onViewTimeout}), mas e LOCAL a cada replica: nao ha mensagem que puxe uma replica para a visao dos
 *       outros. Consequencia: proposta de uma visao que a replica ja deixou por timeout e perdida (OLD_VIEW) e o
 *       bloco dela nunca chega a replica; um bloco filho dele entao falha em UNKNOWN_PARENT nela.</li>
 * </ol>
 *
 * <h3>Troca de visao por timeout (2c-3; Yin et al., secao 4.1, "nextView interrupt")</h3>
 * {@link #onViewTimeout}: ao expirar o timer da visao CORRENTE v, a replica avanca para v+1
 * ({@link ReplicaState#advanceViewByTimeout}), devolve a acao {@link ReplicaAction.Kind#NEW_VIEW} (o dono envia
 * NEW_VIEW com o prepareQC ao lider de v+1 e arma o timer de v+1) e reavalia as mensagens retidas (as de v+1 ja
 * podem ser processadas; as de visoes ultrapassadas sao purgadas como OLD_VIEW). Timeout de visao menor que a
 * corrente e obsoleto: contado em {@link DiscardReason#STALE_VIEW_TIMEOUT}, sem efeito. O timer NAO e cancelado
 * quando a visao termina por DECIDE (mesmo estilo do Tendermint): o evento chega e e filtrado aqui.
 * <p><b>Timeout de duracao constante por visao (sem backoff) e PROVISORIO:</b> o crescimento do timeout e o valor
 * de Delta ficam <b>a definir com o humano</b> (ADR-0015 Q12, A-24). O valor vem do config; nada e embutido.
 * <p><b>Seguranca com troca de visao:</b> o travamento ({@code lockedQC}) sobrevive a troca de visao; a replica so
 * vota no PREPARE de v+1 se {@link SafetyRule#safeNode} valer (estende o bloco travado, ou o QC do highQC e de
 * visao maior que a do travamento).
 *
 * <h3>Finalizacao do ramo no DECIDE</h3>
 * O artigo (alg. 2, linha 40) executa os comandos "through m.justify.node": o ramo inteiro. Sem troca de visao
 * cada DECIDE finaliza um bloco cujo pai ja esta finalizado. COM troca de visao um bloco pode ter o prepareQC/
 * lockedQC formado numa visao cujo DECIDE esta replica nao viu (o lider caiu antes do DECIDE, ou ela ja tinha
 * expirado); o bloco da visao seguinte o estende, e o DECIDE dele finaliza tambem os ancestrais ainda nao
 * finalizados ({@link ReplicaAction.Kind#FINALIZE_ANCESTOR}, do mais baixo ao mais alto, antes do bloco do DECIDE).
 * Se o ramo do DECIDE nao estende o bloco ja finalizado, e violacao de seguranca e falha alto.
 *
 * <h3>Efeitos por fase (alg. 2)</h3>
 * <ul>
 *   <li>PREPARE: bloco com pai conhecido; "estende o bloco do QC" (se houver QC) E {@code safeNode}; admite o bloco na
 *       arvore local; voto PREPARE. Nenhum QC do estado muda;</li>
 *   <li>PRE_COMMIT (prepareQC da visao): {@code prepareQC := QC}; voto PRE_COMMIT;</li>
 *   <li>COMMIT (precommitQC da visao): {@code lockedQC := QC}; voto COMMIT;</li>
 *   <li>DECIDE (commitQC da visao): finaliza o bloco e avanca a visao para v+1; sem voto.</li>
 * </ul>
 *
 * <p><b>A conferir com o artigo:</b> (a) que o QC de PRE_COMMIT/COMMIT/DECIDE deve ter visao IGUAL a da mensagem (as
 * fabricas so impoem {@code >=}); (b) que o highQC do PREPARE tem visao ESTRITAMENTE menor que a da mensagem; (c)
 * "estende o bloco do justify" tambem no PREPARE sem trocar de visao; (d) que DECIDE finaliza so o bloco do QC,
 * sem finalizar separadamente ancestrais nao finalizados (com um bloco por visao e sem troca de visao nao existem).
 *
 * <p><b>Limite declarado:</b> nao ha sincronizacao de blocos faltantes. PREPARE cujo pai a replica nao tem conta
 * UNKNOWN_PARENT; fase posterior para bloco nao admitido conta UNKNOWN_BLOCK.
 */
public final class ReplicaCore {
    private record PendingKey(long view, Phase phase) { }

    private final int n;
    private final ViewLeaders leaders;
    private final ReplicaState<HotStuffBlock> state;
    private final BlockAccess blocks;
    private final DiscardCounters discards;
    private final long maxFutureViewHorizon;
    private final Map<PendingKey, LeaderMessage> pending = new LinkedHashMap<>();

    private long phaseView;
    private Phase expected = Phase.PREPARE;
    private int peakPending = 0;
    private long timeoutAdvances = 0L;

    /**
     * @param n numero de replicas (&gt;= 4)
     * @param leaders lider por visao
     * @param state estado da replica
     * @param blocks acesso aos blocos da arvore local
     * @param discards contadores de descarte
     * @param maxFutureViewHorizon horizonte de visoes futuras retidas (&gt; 0; sem padrao; valor de producao a
     *                             definir com o humano; vem de {@code HotStuffConsensusConfig})
     * @throws IllegalArgumentException se algum objeto for nulo, {@code n} &lt; 4 ou o horizonte for &lt;= 0
     */
    public ReplicaCore(int n, ViewLeaders leaders, ReplicaState<HotStuffBlock> state, BlockAccess blocks,
                       DiscardCounters discards, long maxFutureViewHorizon) {
        if (leaders == null || state == null || blocks == null || discards == null) {
            throw new IllegalArgumentException("leaders, state, blocks e discards sao obrigatorios");
        }
        if (n < 4) {
            throw new IllegalArgumentException("n deve ser >= 4: " + n);
        }
        if (maxFutureViewHorizon <= 0L) {
            throw new IllegalArgumentException("maxFutureViewHorizon deve ser > 0: " + maxFutureViewHorizon);
        }
        this.maxFutureViewHorizon = maxFutureViewHorizon;
        this.n = n;
        this.leaders = leaders;
        this.state = state;
        this.blocks = blocks;
        this.discards = discards;
        this.phaseView = state.getCurrentView();
    }

    public ReplicaState<HotStuffBlock> state() { return state; }
    public DiscardCounters discards() { return discards; }
    /** Tamanho atual da retencao: chaves (visao, fase) pendentes. */
    public int pendingSize() { return pending.size(); }
    /** Horizonte de visoes futuras aceitas para retencao (parametro obrigatorio; valor de producao a definir com o humano). */
    public long maxFutureViewHorizon() { return maxFutureViewHorizon; }
    /** Pico da retencao: maior {@link #pendingSize()} ja atingido por esta replica (observabilidade; o limite e o horizonte). */
    public int peakPending() { return peakPending; }

    /** Quantas vezes esta replica avancou de visao por TIMEOUT (nao conta o avanco por DECIDE). */
    public long timeoutAdvances() { return timeoutAdvances; }

    /** Fase que a replica espera na visao corrente (PREPARE no inicio de cada visao). */
    public Phase expectedPhase() {
        if (phaseView != state.getCurrentView()) {
            phaseView = state.getCurrentView();
            expected = Phase.PREPARE;
        }
        return expected;
    }

    /**
     * Trata uma mensagem do lider e devolve as acoes a executar, na ordem. Inclui as acoes de mensagens retidas que
     * esta mensagem destravou.
     */
    public List<ReplicaAction> onLeaderMessage(LeaderMessage m) {
        if (m == null) {
            throw new IllegalArgumentException("mensagem obrigatoria");
        }
        List<ReplicaAction> out = new ArrayList<>();
        step(m, out);
        drain(out);
        return out;
    }

    /**
     * O timer da visao {@code view} expirou (Basic HotStuff, secao 4.1, "nextView interrupt").
     * <ul>
     *   <li>{@code view} &lt; visao corrente: obsoleto, contado em {@link DiscardReason#STALE_VIEW_TIMEOUT}, sem
     *       efeito;</li>
     *   <li>{@code view} == visao corrente: a replica avanca para {@code view + 1}; a acao
     *       {@link ReplicaAction.Kind#NEW_VIEW} (com o prepareQC de AGORA) vem primeiro, depois as acoes das
     *       mensagens retidas que a nova visao destravou;</li>
     *   <li>{@code view} &gt; visao corrente: impossivel (so se arma timer de visao em que a replica entrou) e falha
     *       alto.</li>
     * </ul>
     *
     * @throws IllegalArgumentException se {@code view} &lt; 0
     * @throws IllegalStateException se {@code view} for maior que a visao corrente
     */
    public List<ReplicaAction> onViewTimeout(long view) {
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
        List<ReplicaAction> out = new ArrayList<>();
        long cur = state.getCurrentView();
        if (view < cur) {
            discards.count(DiscardReason.STALE_VIEW_TIMEOUT);
            return out;
        }
        Optional<HotStuffQuorumCertificate> prepareQc = state.getPrepareQc();
        state.advanceViewByTimeout(view); // lanca IllegalStateException se view > cur
        timeoutAdvances++;
        out.add(ReplicaAction.newView(view, prepareQc));
        drain(out);
        return out;
    }

    private void step(LeaderMessage m, List<ReplicaAction> out) {
        long cur = state.getCurrentView();
        if (m.getView() < cur) {
            discards.count(DiscardReason.OLD_VIEW);
            return;
        }
        if (m.getSender().getNodeID() != leaders.leaderOf(m.getView())) {
            discards.count(DiscardReason.WRONG_LEADER);
            return;
        }
        if (!qcCoherent(m)) {
            discards.count(DiscardReason.INCOHERENT_QC);
            return;
        }
        if (m.getView() - cur > maxFutureViewHorizon) { // m.getView() >= cur aqui: sem overflow
            discards.count(DiscardReason.FUTURE_VIEW_BEYOND_HORIZON);
            return; // nao armazena: o descarte nao consome memoria
        }
        Phase exp = expectedPhase();
        if (m.getView() > cur || m.getPhase().ordinal() > exp.ordinal()) {
            retain(m);
            return;
        }
        if (m.getPhase().ordinal() < exp.ordinal()) {
            discards.count(DiscardReason.DUPLICATE_PHASE);
            return;
        }
        process(m, out);
    }

    private void retain(LeaderMessage m) {
        PendingKey key = new PendingKey(m.getView(), m.getPhase());
        if (pending.containsKey(key)) {
            discards.count(DiscardReason.DUPLICATE_PENDING);
        } else {
            pending.put(key, m);
            peakPending = Math.max(peakPending, pending.size());
        }
    }

    /** Purga retidas ultrapassadas (OLD_VIEW) e processa a retida que bate com (visao, fase esperada), em laco. */
    private void drain(List<ReplicaAction> out) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            long cur = state.getCurrentView();
            pending.keySet().removeIf(k -> {
                if (k.view() < cur) {
                    discards.count(DiscardReason.OLD_VIEW);
                    return true;
                }
                return false;
            });
            LeaderMessage ready = pending.remove(new PendingKey(cur, expectedPhase()));
            if (ready != null) {
                step(ready, out);
                progressed = true;
            }
        }
    }

    private boolean qcCoherent(LeaderMessage m) {
        Optional<HotStuffQuorumCertificate> qc = m.getQc();
        switch (m.getPhase()) {
            case PREPARE:
                return qc.isEmpty() || (qc.get().getPhase() == HotStuffVote.Phase.PREPARE
                        && qc.get().getView() < m.getView() && qc.get().getNetworkSize() == n);
            case PRE_COMMIT:
                return chained(m, qc, HotStuffVote.Phase.PREPARE);
            case COMMIT:
                return chained(m, qc, HotStuffVote.Phase.PRE_COMMIT);
            case DECIDE:
                return chained(m, qc, HotStuffVote.Phase.COMMIT);
            default:
                throw new IllegalStateException("fase desconhecida: " + m.getPhase());
        }
    }

    private boolean chained(LeaderMessage m, Optional<HotStuffQuorumCertificate> qc, HotStuffVote.Phase required) {
        return qc.isPresent() && qc.get().getPhase() == required && qc.get().getView() == m.getView()
                && qc.get().getBlock() == m.getBlock() && qc.get().getNetworkSize() == n;
    }

    private void process(LeaderMessage m, List<ReplicaAction> out) {
        HotStuffBlock block = m.getBlock();
        long view = m.getView();
        if (m.getPhase() == Phase.PREPARE) {
            if (!blocks.parentKnown(block)) {
                discards.count(DiscardReason.UNKNOWN_PARENT);
                return;
            }
            ParentLookup lookup = blocks.lookupFor(block);
            Optional<HotStuffQuorumCertificate> justify = m.getQc();
            if (justify.isPresent() && !SafetyRule.extendsBlock(block, justify.get().getBlock(), lookup)) {
                discards.count(DiscardReason.NOT_EXTENDING_JUSTIFY);
                return;
            }
            if (!SafetyRule.safeNode(block, justify, state.getLockedQc(), lookup)) {
                discards.count(DiscardReason.SAFE_NODE_FAILED);
                return;
            }
            blocks.admit(block);
            out.add(ReplicaAction.vote(HotStuffVote.Phase.PREPARE, view, block));
            expected = Phase.PRE_COMMIT;
            return;
        }
        if (!blocks.contains(block)) {
            discards.count(DiscardReason.UNKNOWN_BLOCK);
            return;
        }
        HotStuffQuorumCertificate qc = m.getQc().get(); // presente: qcCoherent exigiu
        switch (m.getPhase()) {
            case PRE_COMMIT:
                if (state.wouldAdvancePrepareQc(qc)) {
                    state.updatePrepareQc(qc); // prepareQC := m.justify
                }
                out.add(ReplicaAction.vote(HotStuffVote.Phase.PRE_COMMIT, view, block));
                expected = Phase.COMMIT;
                break;
            case COMMIT:
                state.updateLockedQc(qc); // lockedQC := m.justify
                out.add(ReplicaAction.vote(HotStuffVote.Phase.COMMIT, view, block));
                expected = Phase.DECIDE;
                break;
            case DECIDE:
                finalizeBranch(view, block, out);
                if (state.getPrepareQc().isEmpty()) {
                    throw new IllegalStateException("DECIDE da visao " + view + " sem prepareQC na replica");
                }
                state.advanceView(view + 1); // expectedPhase() volta a PREPARE ao notar a visao nova
                out.add(ReplicaAction.newView(view, state.getPrepareQc()));
                break;
            default:
                throw new IllegalStateException("fase inesperada: " + m.getPhase());
        }
    }

    /**
     * Finaliza o ramo do DECIDE: ancestrais ainda nao finalizados (do mais baixo ao mais alto) e depois o proprio
     * bloco. Sem troca de visao so o proprio bloco (o pai ja esta finalizado). O ramo tem de estender o bloco ja
     * finalizado; senao seria violacao de seguranca (dois ramos finalizados) e falha alto.
     */
    private void finalizeBranch(long view, HotStuffBlock block, List<ReplicaAction> out) {
        Optional<HotStuffBlock> done = state.getHighestFinalized();
        int floor = done.map(HotStuffBlock::getHeight).orElse(0);
        ParentLookup lookup = blocks.lookupFor(block);
        Deque<HotStuffBlock> ancestors = new ArrayDeque<>(); // do mais baixo ao mais alto, sem o proprio bloco
        HotStuffBlock cur = block;
        while (cur.getHeight() > floor) {
            Optional<HotStuffBlock> parent = lookup.parentOf(cur);
            if (parent.isEmpty()) {
                throw new IllegalStateException("ramo do DECIDE da visao " + view + " desconectado na altura "
                        + cur.getHeight() + " (bloco admitido deveria ter ascendencia conhecida)");
            }
            cur = parent.get();
            if (cur.getHeight() > floor) {
                ancestors.addFirst(cur);
            }
        }
        if (done.isPresent() && cur != done.get()) {
            throw new IllegalStateException("violacao de seguranca: DECIDE da visao " + view
                    + " em ramo que nao estende o bloco finalizado (altura " + floor + ")");
        }
        for (HotStuffBlock ancestor : ancestors) {
            state.finalizeBlock(ancestor);
            out.add(ReplicaAction.finalizeAncestor(view, ancestor));
        }
        state.finalizeBlock(block); // DECIDE repetido do mesmo bloco e no-op; altura que regride lanca
        out.add(ReplicaAction.finalizeBlock(view, block));
    }
}
