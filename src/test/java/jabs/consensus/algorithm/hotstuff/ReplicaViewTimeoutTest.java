package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffProposalMessage.Phase;
import jabs.network.node.nodes.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2c-3, nucleo puro: troca de visao por TIMEOUT na replica ({@code ReplicaState#advanceViewByTimeout},
 * {@code ReplicaCore#onViewTimeout}), seguranca (lockedQC) apos a troca e finalizacao do ramo no DECIDE.
 */
class ReplicaViewTimeoutTest {
    private static final int N = 4;

    private static final class Access implements BlockAccess {
        final Fixtures.Tree tree;
        final List<HotStuffBlock> admitted = new ArrayList<>();

        Access(Fixtures.Tree tree) { this.tree = tree; }

        @Override public boolean contains(HotStuffBlock block) { return admitted.contains(block); }
        @Override public boolean parentKnown(HotStuffBlock candidate) { return true; }
        @Override public ParentLookup lookupFor(HotStuffBlock candidate) { return tree; }
        @Override public void admit(HotStuffBlock block) { admitted.add(block); }
    }

    private final HotStuffConsensusConfig cfg = Fixtures.cfg();
    private final Fixtures.Tree tree = new Fixtures.Tree();
    private final HotStuffBlock genesis = tree.genesis();
    private final Access access = new Access(tree);
    private final DiscardCounters discards = new DiscardCounters();
    private final ReplicaState<HotStuffBlock> state = new ReplicaState<>(1L);
    private final ReplicaCore core = new ReplicaCore(N, new ViewLeaders(cfg, N), state, access, discards, cfg.getMaxFutureViewHorizon());

    private LeaderMessage msg(Phase fase, long view, HotStuffBlock b, Optional<HotStuffQuorumCertificate> highQc) {
        Node l = new Fixtures.StubNode((int) (view % N));
        HotStuffProposalMessage p;
        switch (fase) {
            case PREPARE:
                p = highQc.isPresent()
                        ? HotStuffProposalMessage.prepare(view, l, b, highQc.get(), N)
                        : HotStuffProposalMessage.prepareWithoutHighQc(view, l, b, cfg, N);
                break;
            case PRE_COMMIT:
                p = HotStuffProposalMessage.preCommit(view, l, b, Fixtures.qc(HotStuffVote.Phase.PREPARE, view, b, N));
                break;
            case COMMIT:
                p = HotStuffProposalMessage.commit(view, l, b, Fixtures.qc(HotStuffVote.Phase.PRE_COMMIT, view, b, N));
                break;
            default:
                p = HotStuffProposalMessage.decide(view, l, b, Fixtures.qc(HotStuffVote.Phase.COMMIT, view, b, N));
                break;
        }
        return LeaderMessage.fromProposal(p);
    }

    private List<ReplicaAction> envia(Phase fase, long view, HotStuffBlock b) {
        return core.onLeaderMessage(msg(fase, view, b, Optional.empty()));
    }

    // ---------- (iv) ReplicaState: avanco por timeout nunca regride nem fica parado ----------

    @Test
    void estadoAvancaUmaVisaoPorTimeoutDaVisaoCorrente() {
        ReplicaState<HotStuffBlock> s = new ReplicaState<>(1L);
        assertEquals(2L, s.advanceViewByTimeout(1L));
        assertEquals(2L, s.getCurrentView());
        assertEquals(3L, s.advanceViewByTimeout(2L));
    }

    @Test
    void estadoRecusaTimeoutObsoletoEDeVisaoFuturaENegativo() {
        ReplicaState<HotStuffBlock> s = new ReplicaState<>(5L);
        assertThrows(IllegalStateException.class, () -> s.advanceViewByTimeout(4L), "obsoleto: regrediria");
        assertThrows(IllegalStateException.class, () -> s.advanceViewByTimeout(6L), "futuro: nao existe timer");
        assertThrows(IllegalArgumentException.class, () -> s.advanceViewByTimeout(-1L));
        assertEquals(5L, s.getCurrentView(), "recusado nao muda a visao");
        s.advanceViewByTimeout(5L);
        assertThrows(IllegalStateException.class, () -> s.advanceViewByTimeout(5L), "ja avancou: igual a antiga");
    }

    // ---------- (iv) ReplicaCore: timeout obsoleto filtrado e contado ----------

    @Test
    void timeoutDaVisaoCorrenteAvancaEEmiteNewViewSemQcQuandoBottom() {
        List<ReplicaAction> acoes = core.onViewTimeout(1L);
        assertEquals(1, acoes.size());
        ReplicaAction a = acoes.get(0);
        assertEquals(ReplicaAction.Kind.NEW_VIEW, a.kind());
        assertEquals(1L, a.view(), "NEW_VIEW da visao que TERMINOU");
        assertTrue(a.qc().isEmpty(), "bottom: sem prepareQC");
        assertEquals(2L, state.getCurrentView());
        assertEquals(1L, core.timeoutAdvances());
        assertEquals(0, discards.total());
    }

    @Test
    void timeoutObsoletoEFiltradoEContadoSemMudarEstado() {
        core.onViewTimeout(1L);                 // 1 -> 2
        List<ReplicaAction> repetido = core.onViewTimeout(1L);
        assertTrue(repetido.isEmpty());
        assertEquals(2L, state.getCurrentView(), "a replica nao regride nem avanca duas vezes");
        assertEquals(1L, discards.get(DiscardReason.STALE_VIEW_TIMEOUT));
        core.onViewTimeout(0L);
        assertEquals(2L, discards.get(DiscardReason.STALE_VIEW_TIMEOUT));
        assertEquals(2L, state.getCurrentView());
        assertEquals(1L, core.timeoutAdvances());
    }

    @Test
    void timeoutDeVisaoFuturaFalhaAlto() {
        assertThrows(IllegalStateException.class, () -> core.onViewTimeout(2L));
        assertThrows(IllegalArgumentException.class, () -> core.onViewTimeout(-1L));
        assertEquals(1L, state.getCurrentView());
    }

    @Test
    void decideDeixaTimerDaVisaoObsoletoEOTimeoutDelaEFiltrado() {
        HotStuffBlock b1 = tree.child(genesis);
        envia(Phase.PREPARE, 1, b1);
        envia(Phase.PRE_COMMIT, 1, b1);
        envia(Phase.COMMIT, 1, b1);
        List<ReplicaAction> d = envia(Phase.DECIDE, 1, b1);
        assertEquals(ReplicaAction.Kind.FINALIZE, d.get(0).kind());
        assertEquals(ReplicaAction.Kind.NEW_VIEW, d.get(1).kind());
        assertEquals(2L, state.getCurrentView());
        assertTrue(core.onViewTimeout(1L).isEmpty(), "o timer da visao 1 ainda dispara e e filtrado");
        assertEquals(1L, discards.get(DiscardReason.STALE_VIEW_TIMEOUT));
        assertEquals(2L, state.getCurrentView());
        assertEquals(0L, core.timeoutAdvances(), "avanco por DECIDE nao e avanco por timeout");
    }

    @Test
    void newViewPorTimeoutLevaOPrepareQcDaReplica() {
        HotStuffBlock b1 = tree.child(genesis);
        envia(Phase.PREPARE, 1, b1);
        envia(Phase.PRE_COMMIT, 1, b1);          // prepareQC := (PREPARE, 1, b1)
        List<ReplicaAction> acoes = core.onViewTimeout(1L);
        assertEquals(1, acoes.size());
        assertTrue(acoes.get(0).qc().isPresent());
        assertSame(b1, acoes.get(0).qc().get().getBlock());
        assertEquals(1L, acoes.get(0).qc().get().getView());
        assertEquals(HotStuffVote.Phase.PREPARE, acoes.get(0).qc().get().getPhase());
    }

    @Test
    void timeoutReavaliaRetidasDaNovaVisaoEPurgaAsUltrapassadas() {
        HotStuffBlock b2 = tree.child(genesis);
        HotStuffBlock b3 = tree.child(genesis);
        envia(Phase.PREPARE, 2, b2);              // visao futura: retida
        envia(Phase.PREPARE, 3, b3);              // idem
        assertEquals(2, core.pendingSize());
        List<ReplicaAction> acoes = core.onViewTimeout(1L);   // vai para a visao 2
        assertEquals(ReplicaAction.Kind.NEW_VIEW, acoes.get(0).kind());
        assertEquals(ReplicaAction.Kind.VOTE, acoes.get(1).kind(), "a retida da visao 2 e processada na hora");
        assertEquals(2L, acoes.get(1).view());
        assertEquals(1, core.pendingSize());
        core.onViewTimeout(2L);                   // visao 3
        assertEquals(0, core.pendingSize());
    }

    @Test
    void retidaUltrapassadaPorTimeoutsSeguidosEContadaComoOldView() {
        HotStuffBlock b2 = tree.child(genesis);
        envia(Phase.PREPARE, 2, b2);
        // a replica ja esta na visao 2 depois do primeiro timeout: a retida e processada. Para ultrapassar, retem a 3.
        HotStuffBlock b3 = tree.child(genesis);
        envia(Phase.COMMIT, 3, b3);               // fase adiantada da visao futura 3 (QC coerente)
        core.onViewTimeout(1L);                   // 2 (processa PREPARE de b2)
        core.onViewTimeout(2L);                   // 3
        core.onViewTimeout(3L);                   // 4: a retida COMMIT da visao 3 nunca pode ser processada
        assertEquals(0, core.pendingSize());
        assertEquals(1L, discards.get(DiscardReason.OLD_VIEW));
    }

    // ---------- (vi) seguranca: lockedQC respeitado apos troca de visao ----------

    /** Replica trava em b1 na visao 1 (PREPARE, PRE_COMMIT, COMMIT) e o lider cai antes do DECIDE: timeout. */
    private HotStuffBlock travaEmB1ETrocaDeVisao() {
        HotStuffBlock b1 = tree.child(genesis);
        envia(Phase.PREPARE, 1, b1);
        envia(Phase.PRE_COMMIT, 1, b1);
        List<ReplicaAction> c = envia(Phase.COMMIT, 1, b1);
        assertEquals(HotStuffVote.Phase.COMMIT, c.get(0).votePhase());
        assertTrue(state.getLockedQc().isPresent());
        assertSame(b1, state.getLockedQc().get().getBlock());
        core.onViewTimeout(1L);
        assertEquals(2L, state.getCurrentView());
        return b1;
    }

    @Test
    void travadaEmB1NaoVotaPropostaConflitanteSemJustifySuperior() {
        travaEmB1ETrocaDeVisao();
        HotStuffBlock conflito = tree.child(genesis); // irmao de b1: conflita com o travado
        List<ReplicaAction> acoes = core.onLeaderMessage(msg(Phase.PREPARE, 2, conflito, Optional.empty()));
        assertTrue(acoes.isEmpty(), "sem voto");
        assertEquals(1L, discards.get(DiscardReason.SAFE_NODE_FAILED));
        assertTrue(access.admitted.stream().noneMatch(x -> x == conflito), "nao admitiu o bloco conflitante");
    }

    @Test
    void travadaEmB1NaoVotaConflitanteComJustifyDeVisaoNaoSuperiorAoTravamento() {
        travaEmB1ETrocaDeVisao();
        HotStuffBlock conflito = tree.child(genesis);
        // justify de visao 1 == lockedQC.view (1): a regra de vivacidade exige ESTRITAMENTE maior
        HotStuffQuorumCertificate justify = Fixtures.qc(HotStuffVote.Phase.PREPARE, 1, genesis, N);
        List<ReplicaAction> acoes = core.onLeaderMessage(msg(Phase.PREPARE, 2, conflito, Optional.of(justify)));
        assertTrue(acoes.isEmpty());
        assertEquals(1L, discards.get(DiscardReason.SAFE_NODE_FAILED));
    }

    @Test
    void travadaEmB1VotaPropostaQueEstendeOTravado() {
        HotStuffBlock b1 = travaEmB1ETrocaDeVisao();
        HotStuffBlock b2 = tree.child(b1);
        HotStuffQuorumCertificate justify = Fixtures.qc(HotStuffVote.Phase.PREPARE, 1, b1, N);
        List<ReplicaAction> acoes = core.onLeaderMessage(msg(Phase.PREPARE, 2, b2, Optional.of(justify)));
        assertEquals(1, acoes.size());
        assertEquals(ReplicaAction.Kind.VOTE, acoes.get(0).kind());
        assertEquals(HotStuffVote.Phase.PREPARE, acoes.get(0).votePhase());
        assertEquals(0, discards.total());
    }

    @Test
    void travadaEmB1VotaConflitanteSeOJustifyEDeVisaoSuperior() {
        travaEmB1ETrocaDeVisao();
        // um prepareQC de visao 3 num ramo conflitante prova que um quorum largou b1 (regra de vivacidade)
        core.onViewTimeout(2L); // visao 3
        core.onViewTimeout(3L); // visao 4
        HotStuffBlock alt = tree.child(genesis);
        HotStuffQuorumCertificate justify = Fixtures.qc(HotStuffVote.Phase.PREPARE, 3, alt, N);
        HotStuffBlock b4 = tree.child(alt);
        List<ReplicaAction> acoes = core.onLeaderMessage(msg(Phase.PREPARE, 4, b4, Optional.of(justify)));
        assertEquals(1, acoes.size());
        assertEquals(ReplicaAction.Kind.VOTE, acoes.get(0).kind());
        assertEquals(0, discards.get(DiscardReason.SAFE_NODE_FAILED));
    }

    // ---------- finalizacao do ramo ----------

    @Test
    void decideDeBlocoCujoPaiNaoFoiFinalizadoFinalizaOAncestralAntes() {
        HotStuffBlock b1 = tree.child(genesis);
        envia(Phase.PREPARE, 1, b1);
        envia(Phase.PRE_COMMIT, 1, b1);
        core.onViewTimeout(1L);                       // o lider da visao 1 caiu antes do DECIDE
        HotStuffBlock b2 = tree.child(b1);
        HotStuffQuorumCertificate justify = Fixtures.qc(HotStuffVote.Phase.PREPARE, 1, b1, N);
        core.onLeaderMessage(msg(Phase.PREPARE, 2, b2, Optional.of(justify)));
        envia(Phase.PRE_COMMIT, 2, b2);
        envia(Phase.COMMIT, 2, b2);
        List<ReplicaAction> d = envia(Phase.DECIDE, 2, b2);
        assertEquals(List.of(ReplicaAction.Kind.FINALIZE_ANCESTOR, ReplicaAction.Kind.FINALIZE,
                ReplicaAction.Kind.NEW_VIEW), d.stream().map(ReplicaAction::kind).toList());
        assertSame(b1, d.get(0).block());
        assertSame(b2, d.get(1).block());
        assertSame(b2, state.getHighestFinalized().get());
        assertEquals(3L, state.getCurrentView());
    }

    @Test
    void decideEmRamoQueNaoEstendeOFinalizadoEViolacaoDeSegurancaEFalhaAlto() {
        HotStuffBlock b1 = tree.child(genesis);
        envia(Phase.PREPARE, 1, b1);
        envia(Phase.PRE_COMMIT, 1, b1);
        envia(Phase.COMMIT, 1, b1);
        envia(Phase.DECIDE, 1, b1);                   // finalizou b1
        HotStuffBlock conflito = tree.child(genesis);
        // forca a replica a processar um ramo conflitante ate o DECIDE (nao ocorre com a regra safeNode: aqui
        // o bloco entra por justify superior, simulando um quorum bizantino > f)
        core.onViewTimeout(2L); // visao 3
        HotStuffQuorumCertificate justify = Fixtures.qc(HotStuffVote.Phase.PREPARE, 2, genesis, N);
        List<ReplicaAction> voto = core.onLeaderMessage(msg(Phase.PREPARE, 3, conflito, Optional.of(justify)));
        assertEquals(1, voto.size(), "justify de visao 2 > lockedQC.view 1: safeNode deixa passar");
        envia(Phase.PRE_COMMIT, 3, conflito);
        envia(Phase.COMMIT, 3, conflito);
        assertThrows(IllegalStateException.class, () -> envia(Phase.DECIDE, 3, conflito));
    }
}
