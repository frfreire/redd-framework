package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage.Phase;

import java.util.ArrayList;
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
 *   <li>visao futura, ou fase adiantada na visao corrente (a rede pode reordenar): <b>retida</b> em
 *       {@link LinkedHashMap} (ordem de chegada) por (visao, fase) e reavaliada quando a replica chega la.
 *       A retencao NAO tem teto de contagem (ver "Memoria da retencao" abaixo): uma proposta honesta valida nunca
 *       e perdida nem recusada, porque o simulador nao tem retransmissao e atraso finito nao viola a entrega
 *       confiavel do caminho honesto. Segunda mensagem da mesma (visao, fase): a primeira vale, a outra e contada
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
 *   <li>O consumo de memoria cresce com o atraso da replica: uma entrada por (visao, fase) pendente, no maximo
 *       uma mensagem por chave. Nao ha teto; o que existe e observabilidade: {@link #pendingSize()} (tamanho atual)
 *       e {@link #peakPending()} (maximo ja atingido), que o {@code HotStuff} expoe para o cenario relatar.</li>
 *   <li>O simulador tem horizonte finito ({@code duracao_sim_s}); logo a retencao e limitada pelo tempo simulado,
 *       nao por um parametro desta classe. Replica lenta num horizonte longo retem mais.</li>
 *   <li><b>RISCO PARA A PARTE 2d:</b> um lider bizantino de visao futura pode inflar a retencao de replicas
 *       honestas com visoes arbitrarias (uma entrada por visao em que ele e lider, mesmo com o QC coerente, pois
 *       a coerencia so olha a mensagem). A defesa (sincronizacao de visao por QC e/ou horizonte maximo de visoes
 *       futuras) e decisao da 2c-3/2d e fica <b>a definir com o humano</b>. Nenhum valor foi escolhido aqui.</li>
 *   <li>Limite declarado: a recuperacao de replica atrasada por QC (pacemaker, sincronizacao de visao) AINDA NAO
 *       EXISTE. A replica atrasada so alcanca as visoes futuras pelas proprias mensagens retidas.</li>
 * </ol>
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
    private final Map<PendingKey, LeaderMessage> pending = new LinkedHashMap<>();

    private long phaseView;
    private Phase expected = Phase.PREPARE;
    private int peakPending = 0;

    /**
     * @param n numero de replicas (&gt;= 4)
     * @param leaders lider por visao
     * @param state estado da replica
     * @param blocks acesso aos blocos da arvore local
     * @param discards contadores de descarte
     * @throws IllegalArgumentException se algum objeto for nulo ou {@code n} &lt; 4
     */
    public ReplicaCore(int n, ViewLeaders leaders, ReplicaState<HotStuffBlock> state, BlockAccess blocks,
                       DiscardCounters discards) {
        if (leaders == null || state == null || blocks == null || discards == null) {
            throw new IllegalArgumentException("leaders, state, blocks e discards sao obrigatorios");
        }
        if (n < 4) {
            throw new IllegalArgumentException("n deve ser >= 4: " + n);
        }
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
    /** Pico da retencao: maior {@link #pendingSize()} ja atingido por esta replica (observabilidade, nao limite). */
    public int peakPending() { return peakPending; }

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
                state.finalizeBlock(block);
                state.advanceView(view + 1); // expectedPhase() volta a PREPARE ao notar a visao nova
                out.add(ReplicaAction.finalizeBlock(view, block));
                break;
            default:
                throw new IllegalStateException("fase inesperada: " + m.getPhase());
        }
    }
}
