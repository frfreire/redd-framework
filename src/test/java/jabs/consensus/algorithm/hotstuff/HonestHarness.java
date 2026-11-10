package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.node.nodes.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Harness em memoria SEM JABS: n nos, cada um com {@link ReplicaCore} (sobre {@link TreeBlockAccess} real) e
 * {@link LeaderCore}. Entrega FIFO e determinista; o lider recebe o proprio voto pelo mesmo {@code onVote} (sem
 * pacote). {@code muted}: votantes cujos votos nunca chegam ao lider.
 */
final class HonestHarness {
    static final class Rep {
        final int id;
        final LocalBlockTree<HotStuffBlock> tree;
        final TreeBlockAccess access;
        final ReplicaState<HotStuffBlock> state = new ReplicaState<>(0L);
        final DiscardCounters discards = new DiscardCounters();
        final ReplicaCore core;
        final List<HotStuffBlock> finalized = new ArrayList<>();

        Rep(int id, int n, ViewLeaders leaders, HotStuffBlock genesis) {
            this.id = id;
            this.tree = new LocalBlockTree<>(genesis);
            this.access = new TreeBlockAccess(tree);
            this.core = new ReplicaCore(n, leaders, state, access, discards, Fixtures.cfg().getMaxFutureViewHorizon());
        }
    }

    final int n;
    final HotStuffConsensusConfig cfg = Fixtures.cfg();
    final ViewLeaders leaders;
    final HotStuffBlock genesis = new HotStuffBlock(0, 0, 0.0, null, null);
    final Node[] nodes;
    final List<Rep> reps = new ArrayList<>();
    final LeaderCore[] leaderCores;
    final DiscardCounters[] leaderDiscards;
    final VoteAggregator<HotStuffBlock>[] voteAggs;
    final List<HotStuffBlock> proposed = new ArrayList<>();
    final List<LeaderAction> emitted = new ArrayList<>();
    final Set<Integer> muted = new HashSet<>();
    int proposerCalls = 0;

    @SuppressWarnings("unchecked")
    HonestHarness(int n) {
        this.n = n;
        this.leaders = new ViewLeaders(cfg, n);
        this.nodes = new Node[n];
        this.leaderCores = new LeaderCore[n];
        this.leaderDiscards = new DiscardCounters[n];
        this.voteAggs = new VoteAggregator[n];
        for (int i = 0; i < n; i++) {
            nodes[i] = new Fixtures.StubNode(i);
            reps.add(new Rep(i, n, leaders, genesis));
            leaderDiscards[i] = new DiscardCounters();
            voteAggs[i] = new VoteAggregator<>();
            final Rep rep = reps.get(i);
            leaderCores[i] = new LeaderCore(n, i, 0L, leaders, cfg, genesis, this::propose, voteAggs[i],
                    new NewViewAggregator(), leaderDiscards[i], () -> rep.state.getCurrentView());
        }
    }

    private HotStuffBlock propose(long view, HotStuffBlock parent) {
        proposerCalls++;
        HotStuffBlock b = new HotStuffBlock(1000, parent.getHeight() + 1, 0.0, null, parent);
        proposed.add(b);
        return b;
    }

    /**
     * Conduz UMA visao ate o fim (todas as mensagens entregues). Toda visao, a inicial inclusive, comeca com o
     * NEW_VIEW de todas as replicas ao lider (na visao inicial, sem QC: o que as replicas fariam no start; o dono
     * real fara isso na 2b-2). O lider so propoe ao completar n-f.
     */
    void runView(long view) {
        int l = leaders.leaderOf(view);
        Deque<LeaderAction> queue = new ArrayDeque<>();
        for (Rep r : reps) {
            queue.addAll(leaderCores[l].onNewView(view, r.id, r.state.getPrepareQc()));
        }
        while (!queue.isEmpty()) {
            LeaderAction a = queue.poll();
            emitted.add(a);
            LeaderMessage msg = LeaderMessage.fromProposal(toMessage(a, nodes[l]));
            for (Rep r : reps) {
                for (ReplicaAction ra : r.core.onLeaderMessage(msg)) {
                    if (ra.kind() == ReplicaAction.Kind.FINALIZE || ra.kind() == ReplicaAction.Kind.FINALIZE_ANCESTOR) {
                        r.finalized.add(ra.block());
                    } else if (ra.kind() == ReplicaAction.Kind.NEW_VIEW) {
                        continue; // o harness envia os NEW_VIEW de cada visao em runView, a partir do estado da replica
                    } else if (!muted.contains(r.id)) {
                        queue.addAll(leaderCores[l].onVote(
                                new HotStuffVote(nodes[r.id], ra.votePhase(), ra.view(), ra.block(), cfg)));
                    }
                }
            }
        }
    }

    void runViews(int count) {
        for (int v = 0; v < count; v++) {
            runView(v);
        }
    }

    HotStuffProposalMessage toMessage(LeaderAction a, Node leader) {
        switch (a.phase()) {
            case PREPARE:
                return a.qc().isPresent()
                        ? HotStuffProposalMessage.prepare(a.view(), leader, a.block(), a.qc().get(), n)
                        : HotStuffProposalMessage.prepareWithoutHighQc(a.view(), leader, a.block(), cfg, n);
            case PRE_COMMIT:
                return HotStuffProposalMessage.preCommit(a.view(), leader, a.block(), a.qc().get());
            case COMMIT:
                return HotStuffProposalMessage.commit(a.view(), leader, a.block(), a.qc().get());
            default:
                return HotStuffProposalMessage.decide(a.view(), leader, a.block(), a.qc().get());
        }
    }

    long replicaDiscards() {
        long t = 0;
        for (Rep r : reps) {
            t += r.discards.total();
        }
        return t;
    }
}
