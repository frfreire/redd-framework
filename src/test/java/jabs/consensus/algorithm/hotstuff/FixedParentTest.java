package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffBlockWithTx;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffProposalMessage.Phase;
import jabs.network.node.nodes.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pai IMUTAVEL do HotStuffBlock: mutar {@code Block#getParents()} depois da construcao NAO muda a decisao da
 * replica (TreeBlockAccess le {@code getFixedParent()}), nem para aceitar o que seria recusado, nem para recusar o
 * que seria aceito.
 */
class FixedParentTest {
    private static final int N = 4;
    private final HonestHarness h = new HonestHarness(N);
    private final Node leader1 = h.nodes[1];

    private HonestHarness.Rep replicaComAdmitido(HotStuffBlock a) {
        HonestHarness.Rep r = h.reps.get(2);
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.prepareWithoutHighQc(0, h.nodes[0], a, h.cfg, N)));
        return r;
    }

    private static boolean votou(List<ReplicaAction> acoes) {
        return acoes.size() == 1 && acoes.get(0).kind() == ReplicaAction.Kind.VOTE;
    }

    @Test
    void camposDePaiFixoPreenchidosNoConstrutorEGenesisTemPaiNulo() {
        HotStuffBlock a = new HotStuffBlock(1000, 1, 0.0, null, h.genesis);
        HotStuffBlockWithTx tx = new HotStuffBlockWithTx(1000, 2, 0.0, null, a, Set.of());
        assertNull(h.genesis.getFixedParent());
        assertSame(h.genesis, a.getFixedParent());
        assertSame(a, tx.getFixedParent());
    }

    @Test
    void listaDePaisMutadaParaUmEstranhoAntesDaAdmissaoNaoImpedeAReplica() {
        HotStuffBlock a = new HotStuffBlock(1000, 1, 0.0, null, h.genesis);
        HonestHarness.Rep r = replicaComAdmitido(a);
        HotStuffBlock estranho = new HotStuffBlock(1000, 1, 0.0, null, h.genesis); // nunca admitido
        HotStuffBlock b = new HotStuffBlock(1000, 2, 0.0, null, a);
        b.getParents().set(0, estranho); // mutacao ANTES da admissao
        r.core.onLeaderMessage(LeaderMessage.fromProposal( // visao 0 ja respondida (PREPARE): avanca a replica ate a visao 1
                HotStuffProposalMessage.preCommit(0, h.nodes[0], a, qc(HotStuffVote.Phase.PREPARE, 0, a))));
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.commit(0, h.nodes[0], a, qc(HotStuffVote.Phase.PRE_COMMIT, 0, a))));
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.decide(0, h.nodes[0], a, qc(HotStuffVote.Phase.COMMIT, 0, a))));
        List<ReplicaAction> acoes = r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.prepareWithoutHighQc(1, leader1, b, h.cfg, N)));
        assertEquals(true, votou(acoes), "pai decidido por getFixedParent: a lista mutada nao conta");
        assertEquals(0, r.discards.total());
        assertSame(a, r.access.lookupFor(b).parentOf(b).get());
    }

    @Test
    void listaDePaisMutadaParaUmAdmitidoNaoLegitimaUmBlocoOrfao() {
        HotStuffBlock a = new HotStuffBlock(1000, 1, 0.0, null, h.genesis);
        HonestHarness.Rep r = replicaComAdmitido(a);
        HotStuffBlock desconhecido = new HotStuffBlock(1000, 1, 0.0, null, h.genesis); // nao admitido
        HotStuffBlock orfao = new HotStuffBlock(1000, 2, 0.0, null, desconhecido);
        orfao.getParents().set(0, a); // a lista "diz" que o pai e o admitido; o campo imutavel diz que nao
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.preCommit(0, h.nodes[0], a, qc(HotStuffVote.Phase.PREPARE, 0, a))));
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.commit(0, h.nodes[0], a, qc(HotStuffVote.Phase.PRE_COMMIT, 0, a))));
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.decide(0, h.nodes[0], a, qc(HotStuffVote.Phase.COMMIT, 0, a))));
        List<ReplicaAction> acoes = r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.prepareWithoutHighQc(1, leader1, orfao, h.cfg, N)));
        assertEquals(0, acoes.size());
        assertEquals(1, r.discards.get(DiscardReason.UNKNOWN_PARENT));
    }

    @Test
    void listaDePaisMutadaDepoisDaAdmissaoNaoMudaPaiNemAscendenciaNemDecisao() {
        HotStuffBlock a = new HotStuffBlock(1000, 1, 0.0, null, h.genesis);
        HonestHarness.Rep r = replicaComAdmitido(a);
        a.getParents().set(0, null);
        assertSame(h.genesis, r.access.lookupFor(a).parentOf(a).get());
        HotStuffBlock b = new HotStuffBlock(1000, 2, 0.0, null, a);
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.preCommit(0, h.nodes[0], a, qc(HotStuffVote.Phase.PREPARE, 0, a))));
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.commit(0, h.nodes[0], a, qc(HotStuffVote.Phase.PRE_COMMIT, 0, a))));
        r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.decide(0, h.nodes[0], a, qc(HotStuffVote.Phase.COMMIT, 0, a))));
        assertEquals(true, votou(r.core.onLeaderMessage(LeaderMessage.fromProposal(
                HotStuffProposalMessage.prepareWithoutHighQc(1, leader1, b, h.cfg, N)))));
        assertEquals(0, r.discards.total());
    }

    private HotStuffQuorumCertificate qc(HotStuffVote.Phase phase, long view, HotStuffBlock block) {
        return Fixtures.qc(phase, view, block, N);
    }
}
