package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffProposalMessage.Phase;
import jabs.network.node.nodes.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retencao do {@link ReplicaCore}: SEM teto de contagem. Uma replica atrasada por muitas visoes retem tudo, sem lancar
 * e sem perder proposta honesta, e processa as retidas na ordem (visao, fase) quando a visao alcanca. A retencao e
 * observavel por {@code pendingSize()} e {@code peakPending()}.
 */
class ReplicaCoreRetentionTest {

    /** BlockAccess de teste: tudo conhecido; registra a ordem de admissao. */
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
    private final Access access = new Access(tree);
    private final DiscardCounters discards = new DiscardCounters();
    private ReplicaState<HotStuffBlock> state;
    private ReplicaCore core;
    private int n;

    private void monta(int n) {
        this.n = n;
        this.state = new ReplicaState<>(0L);
        this.core = new ReplicaCore(n, new ViewLeaders(cfg, n), state, access, discards);
    }

    private Node leader(long view) {
        return new Fixtures.StubNode((int) (view % n));
    }

    private LeaderMessage msg(Phase fase, long view, HotStuffBlock b) {
        Node l = leader(view);
        HotStuffProposalMessage p;
        switch (fase) {
            case PREPARE:
                p = HotStuffProposalMessage.prepareWithoutHighQc(view, l, b, cfg, n);
                break;
            case PRE_COMMIT:
                p = HotStuffProposalMessage.preCommit(view, l, b, Fixtures.qc(HotStuffVote.Phase.PREPARE, view, b, n));
                break;
            case COMMIT:
                p = HotStuffProposalMessage.commit(view, l, b, Fixtures.qc(HotStuffVote.Phase.PRE_COMMIT, view, b, n));
                break;
            default:
                p = HotStuffProposalMessage.decide(view, l, b, Fixtures.qc(HotStuffVote.Phase.COMMIT, view, b, n));
                break;
        }
        return LeaderMessage.fromProposal(p);
    }

    /** Cadeia: blocos[v] e o bloco da visao v, cada um filho do anterior (o da visao 0 filho do genesis). */
    private List<HotStuffBlock> cadeia(int visoes) {
        return tree.chain(tree.genesis(), visoes);
    }

    // ---------- sem teto: nao lanca, pico e tamanho atual ----------

    @Test
    void pendingSizeEPeakPendingAcompanhamARetencao() {
        monta(4);
        List<HotStuffBlock> b = cadeia(3);
        assertEquals(0, core.pendingSize());
        assertEquals(0, core.peakPending());
        core.onLeaderMessage(msg(Phase.PREPARE, 1, b.get(1)));
        core.onLeaderMessage(msg(Phase.PRE_COMMIT, 1, b.get(1)));
        core.onLeaderMessage(msg(Phase.COMMIT, 1, b.get(1)));
        assertEquals(3, core.pendingSize());
        assertEquals(3, core.peakPending());
        // a visao 0 chega e tudo e processado: tamanho volta a 0, o pico fica
        for (Phase f : Phase.values()) {
            core.onLeaderMessage(msg(f, 0, b.get(0)));
        }
        assertEquals(0, core.pendingSize());
        assertEquals(3, core.peakPending(), "o pico nao diminui");
    }

    @Test
    void picoRefleOMaximoNaoOUltimoTamanho() {
        monta(4);
        List<HotStuffBlock> b = cadeia(4);
        core.onLeaderMessage(msg(Phase.PREPARE, 2, b.get(2)));
        core.onLeaderMessage(msg(Phase.PRE_COMMIT, 2, b.get(2)));
        core.onLeaderMessage(msg(Phase.COMMIT, 2, b.get(2)));
        core.onLeaderMessage(msg(Phase.DECIDE, 2, b.get(2)));
        assertEquals(4, core.peakPending());
        state.advanceView(3); // pula a visao 2
        core.onLeaderMessage(msg(Phase.PREPARE, 3, b.get(3))); // purga as 4 e processa esta
        assertEquals(0, core.pendingSize());
        assertEquals(4, core.peakPending());
        assertEquals(4, discards.get(DiscardReason.OLD_VIEW));
    }

    @Test
    void duplicataNaoAumentaARetencaoNemOPicoESoEContada() {
        monta(4);
        List<HotStuffBlock> b = cadeia(2);
        core.onLeaderMessage(msg(Phase.PREPARE, 1, b.get(1)));
        core.onLeaderMessage(msg(Phase.PRE_COMMIT, 1, b.get(1)));
        assertDoesNotThrow(() -> core.onLeaderMessage(msg(Phase.PRE_COMMIT, 1, b.get(1))));
        assertEquals(1, discards.get(DiscardReason.DUPLICATE_PENDING));
        assertEquals(2, core.pendingSize());
        assertEquals(2, core.peakPending());
    }

    // ---------- DUPLICATE_PENDING e LEADER mismatch ----------

    @Test
    void remetenteQueNaoELiderDaVisaoNuncaChegaAReter() {
        monta(4);
        List<HotStuffBlock> b = cadeia(2);
        core.onLeaderMessage(msg(Phase.PREPARE, 1, b.get(1)));
        LeaderMessage impostor = LeaderMessage.fromProposal(HotStuffProposalMessage.prepareWithoutHighQc(
                1, new Fixtures.StubNode(3), b.get(1), cfg, 4));
        core.onLeaderMessage(impostor);
        assertEquals(1, discards.get(DiscardReason.WRONG_LEADER));
        assertEquals(0, discards.get(DiscardReason.DUPLICATE_PENDING));
        assertEquals(1, core.pendingSize());
    }

    @Test
    void segundaDoMesmoLiderParaAMesmaVisaoEFaseEDuplicataEAPrimeiraVale() {
        monta(4);
        List<HotStuffBlock> b = cadeia(2);
        HotStuffBlock outro = tree.child(tree.genesis());
        core.onLeaderMessage(msg(Phase.PREPARE, 1, b.get(1)));
        core.onLeaderMessage(msg(Phase.PREPARE, 1, outro)); // equivocacao do lider: contada, nao substitui
        assertEquals(1, discards.get(DiscardReason.DUPLICATE_PENDING));
        // a visao 0 chega: o bloco retido da visao 1 e o primeiro
        core.onLeaderMessage(msg(Phase.PREPARE, 0, b.get(0)));
        core.onLeaderMessage(msg(Phase.PRE_COMMIT, 0, b.get(0)));
        core.onLeaderMessage(msg(Phase.COMMIT, 0, b.get(0)));
        core.onLeaderMessage(msg(Phase.DECIDE, 0, b.get(0)));
        assertEquals(List.of(b.get(0), b.get(1)), access.admitted);
    }

    // ---------- obsoleta: OLD_VIEW ----------

    @Test
    void retidaUltrapassadaPelaVisaoEPurgadaEContadaComoOldView() {
        monta(4);
        List<HotStuffBlock> b = cadeia(4);
        core.onLeaderMessage(msg(Phase.PREPARE, 2, b.get(2)));
        core.onLeaderMessage(msg(Phase.PRE_COMMIT, 2, b.get(2)));
        assertEquals(2, core.pendingSize());
        state.advanceView(3); // a replica pulou a visao 2 (sincronizacao externa)
        core.onLeaderMessage(msg(Phase.PREPARE, 4, b.get(3))); // qualquer mensagem aciona a purga
        assertEquals(2, discards.get(DiscardReason.OLD_VIEW));
        assertEquals(1, core.pendingSize(), "so a mensagem nova (visao 4) fica");
    }

    @Test
    void construtorValidaArgumentos() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicaCore(4, new ViewLeaders(cfg, 4),
                new ReplicaState<>(0L), access, null));
        assertThrows(IllegalArgumentException.class, () -> new ReplicaCore(3, new ViewLeaders(cfg, 4),
                new ReplicaState<>(0L), access, discards));
    }

    // ---------- muitas visoes futuras: sem teto ----------

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 16})
    void replicaAtrasadaPorMuitasVisoesNaoLancaEProcessaTodasNaOrdem(int nn) {
        int visoes = 10 * (nn + 1); // bem alem de qualquer limite 4 * (n + 1) do desenho anterior
        monta(nn);
        List<HotStuffBlock> b = cadeia(visoes);

        // visoes em ordem inversa, fases em ordem inversa; so falta o PREPARE da visao 0
        List<ReplicaAction> acoes = new ArrayList<>();
        Phase[] fases = Phase.values();
        for (int v = visoes - 1; v >= 0; v--) {
            for (int i = fases.length - 1; i >= (v == 0 ? 1 : 0); i--) {
                final int vv = v;
                final int ii = i;
                assertDoesNotThrow(() -> acoes.addAll(core.onLeaderMessage(msg(fases[ii], vv, b.get(vv)))));
            }
        }
        int esperadoRetidas = 4 * visoes - 1;
        assertEquals(esperadoRetidas, core.pendingSize());
        assertEquals(esperadoRetidas, core.peakPending());
        assertTrue(esperadoRetidas > 4 * (nn + 1), "alem do antigo teto");
        assertTrue(acoes.isEmpty(), "nada processado enquanto falta o PREPARE da visao 0");

        acoes.addAll(core.onLeaderMessage(msg(Phase.PREPARE, 0, b.get(0)))); // a visao alcanca

        List<ReplicaAction> esperadas = new ArrayList<>();
        for (int v = 0; v < visoes; v++) {
            esperadas.add(ReplicaAction.vote(HotStuffVote.Phase.PREPARE, v, b.get(v)));
            esperadas.add(ReplicaAction.vote(HotStuffVote.Phase.PRE_COMMIT, v, b.get(v)));
            esperadas.add(ReplicaAction.vote(HotStuffVote.Phase.COMMIT, v, b.get(v)));
            esperadas.add(ReplicaAction.finalizeBlock(v, b.get(v)));
        }
        assertEquals(esperadas, acoes, "todas as retidas processadas na ordem (visao, fase)");
        assertEquals(visoes, state.getCurrentView());
        assertEquals(0, core.pendingSize());
        assertEquals(esperadoRetidas, core.peakPending(), "o pico guarda o maximo");
        assertEquals(0, discards.total(), "nenhum descarte, nenhuma perda");
        assertEquals(b.subList(0, visoes), access.admitted);
    }
}
