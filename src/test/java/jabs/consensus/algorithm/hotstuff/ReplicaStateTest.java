package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote.Phase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ReplicaState: estado inicial com bottom explicito, monotonicidade e todas as combinacoes invalidas. */
class ReplicaStateTest {

    private final Fixtures.Tree tree = new Fixtures.Tree();
    private final HotStuffBlock g = tree.genesis();
    private final HotStuffBlock b1 = tree.child(g);
    private final HotStuffBlock b2 = tree.child(b1);
    private final HotStuffBlock b2alt = tree.child(b1);

    private ReplicaState<HotStuffBlock> novo() {
        return new ReplicaState<>(1);
    }

    @Test
    void estadoInicialTemBottomExplicito() {
        ReplicaState<HotStuffBlock> s = novo();
        assertEquals(1, s.getCurrentView());
        assertTrue(s.getPrepareQc().isEmpty());
        assertTrue(s.getLockedQc().isEmpty());
        assertTrue(s.getHighestFinalized().isEmpty());
    }

    @Test
    void visaoInicialNegativaLanca() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicaState<HotStuffBlock>(-1));
        new ReplicaState<HotStuffBlock>(0); // zero e valido
    }

    @Test
    void visaoAvancaEIgualEhNoOpMasNaoRegride() {
        ReplicaState<HotStuffBlock> s = novo();
        s.advanceView(5);
        assertEquals(5, s.getCurrentView());
        s.advanceView(5);
        assertEquals(5, s.getCurrentView());
        assertThrows(IllegalStateException.class, () -> s.advanceView(4));
        assertEquals(5, s.getCurrentView());
        assertThrows(IllegalArgumentException.class, () -> s.advanceView(-1));
    }

    @Test
    void prepareQcSoAvancaEmVisao() {
        ReplicaState<HotStuffBlock> s = novo();
        HotStuffQuorumCertificate q3 = Fixtures.qc(Phase.PREPARE, 3, b1);
        HotStuffQuorumCertificate q5 = Fixtures.qc(Phase.PREPARE, 5, b2);
        assertTrue(s.wouldAdvancePrepareQc(q3));
        s.updatePrepareQc(q3);
        assertSame(q3, s.getPrepareQc().orElseThrow());
        assertTrue(!s.wouldAdvancePrepareQc(Fixtures.qc(Phase.PREPARE, 3, b1)));
        assertThrows(IllegalStateException.class, () -> s.updatePrepareQc(Fixtures.qc(Phase.PREPARE, 3, b2alt))); // igual
        assertThrows(IllegalStateException.class, () -> s.updatePrepareQc(Fixtures.qc(Phase.PREPARE, 2, b1)));    // menor
        assertSame(q3, s.getPrepareQc().orElseThrow());
        s.updatePrepareQc(q5);
        assertSame(q5, s.getPrepareQc().orElseThrow());
    }

    @Test
    void prepareQcExigeFasePrepare() {
        ReplicaState<HotStuffBlock> s = novo();
        assertThrows(IllegalArgumentException.class, () -> s.updatePrepareQc(Fixtures.qc(Phase.PRE_COMMIT, 3, b1)));
        assertThrows(IllegalArgumentException.class, () -> s.updatePrepareQc(Fixtures.qc(Phase.COMMIT, 3, b1)));
        assertTrue(!s.wouldAdvancePrepareQc(Fixtures.qc(Phase.COMMIT, 3, b1)));
        assertTrue(s.getPrepareQc().isEmpty());
    }

    @Test
    void lockedQcNaoRegrideEmVisao() {
        ReplicaState<HotStuffBlock> s = novo();
        HotStuffQuorumCertificate q4 = Fixtures.qc(Phase.PRE_COMMIT, 4, b1);
        s.updateLockedQc(q4);
        assertSame(q4, s.getLockedQc().orElseThrow());
        assertThrows(IllegalStateException.class, () -> s.updateLockedQc(Fixtures.qc(Phase.PRE_COMMIT, 3, b2)));
        assertSame(q4, s.getLockedQc().orElseThrow());
        // visao igual, mesmo bloco: no-op, mantem a instancia original
        s.updateLockedQc(Fixtures.qc(Phase.PRE_COMMIT, 4, b1));
        assertSame(q4, s.getLockedQc().orElseThrow());
        // visao igual, bloco diferente: violacao de seguranca
        assertThrows(IllegalStateException.class, () -> s.updateLockedQc(Fixtures.qc(Phase.PRE_COMMIT, 4, b2)));
        HotStuffQuorumCertificate q9 = Fixtures.qc(Phase.PRE_COMMIT, 9, b2);
        s.updateLockedQc(q9);
        assertSame(q9, s.getLockedQc().orElseThrow());
    }

    @Test
    void lockedQcExigeFasePreCommit() {
        ReplicaState<HotStuffBlock> s = novo();
        assertThrows(IllegalArgumentException.class, () -> s.updateLockedQc(Fixtures.qc(Phase.PREPARE, 3, b1)));
        assertThrows(IllegalArgumentException.class, () -> s.updateLockedQc(Fixtures.qc(Phase.COMMIT, 3, b1)));
        assertTrue(s.getLockedQc().isEmpty());
    }

    @Test
    void blocoFinalizadoNaoRegrideEmAltura() {
        ReplicaState<HotStuffBlock> s = novo();
        s.finalizeBlock(b1);
        assertSame(b1, s.getHighestFinalized().orElseThrow());
        s.finalizeBlock(b1); // mesma referencia: no-op
        assertThrows(IllegalStateException.class, () -> s.finalizeBlock(g));
        assertThrows(IllegalStateException.class, () -> s.finalizeBlock(tree.child(g))); // mesma altura, outra referencia
        s.finalizeBlock(b2);
        assertSame(b2, s.getHighestFinalized().orElseThrow());
        assertThrows(IllegalStateException.class, () -> s.finalizeBlock(b1));
        assertThrows(IllegalStateException.class, () -> s.finalizeBlock(b2alt));
        assertSame(b2, s.getHighestFinalized().orElseThrow());
    }

    @Test
    void argumentosNulosLancam() {
        ReplicaState<HotStuffBlock> s = novo();
        assertThrows(IllegalArgumentException.class, () -> s.updatePrepareQc(null));
        assertThrows(IllegalArgumentException.class, () -> s.updateLockedQc(null));
        assertThrows(IllegalArgumentException.class, () -> s.finalizeBlock(null));
        assertThrows(IllegalArgumentException.class, () -> s.wouldAdvancePrepareQc(null));
    }

    @Test
    void sequenciaTipicaDeUmaVisaoMantemInvariantes() {
        ReplicaState<HotStuffBlock> s = novo();
        s.updatePrepareQc(Fixtures.qc(Phase.PREPARE, 1, b1));
        s.updateLockedQc(Fixtures.qc(Phase.PRE_COMMIT, 1, b1));
        s.finalizeBlock(b1);
        s.advanceView(2);
        s.updatePrepareQc(Fixtures.qc(Phase.PREPARE, 2, b2));
        s.updateLockedQc(Fixtures.qc(Phase.PRE_COMMIT, 2, b2));
        s.finalizeBlock(b2);
        assertEquals(2, s.getCurrentView());
        assertEquals(2, s.getPrepareQc().orElseThrow().getView());
        assertEquals(2, s.getLockedQc().orElseThrow().getView());
        assertSame(b2, s.getHighestFinalized().orElseThrow());
    }

    @Test
    void estadosDeReplicasDiferentesSaoIndependentes() {
        ReplicaState<HotStuffBlock> a = novo();
        ReplicaState<HotStuffBlock> b = novo();
        a.advanceView(7);
        assertEquals(1, b.getCurrentView());
    }
}
