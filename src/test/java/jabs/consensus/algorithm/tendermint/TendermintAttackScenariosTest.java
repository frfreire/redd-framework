package jabs.consensus.algorithm.tendermint;

import jabs.consensus.algorithm.tendermint.TendermintAction.Kind;
import jabs.consensus.algorithm.tendermint.TendermintVote.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ataques roteirizados: a entrega de cada mensagem e escolhida a mao para montar exatamente o cenario em que um
 * mecanismo de safety e necessario. Se o mecanismo for removido, o roteiro leva dois corretos a decidir valores
 * diferentes. (O fuzzing aleatorio do {@link TendermintSafetyTest} quase nunca chega nesses cenarios: numa medicao
 * com o lock removido, 2 violacoes em 3.600 execucoes.)
 */
class TendermintAttackScenariosTest {

    /** Nos corretos alimentados a mao; o bizantino e so o que o roteiro injeta em nome dele. */
    private static final class Script {
        final int n;
        final Map<Integer, TendermintCore<String>> cores = new HashMap<>();
        final Map<Integer, List<TendermintAction<String>>> outbox = new HashMap<>();

        Script(int n, int byzantine) {
            this.n = n;
            for (int i = 0; i < n; i++) {
                if (i == byzantine) {
                    continue;
                }
                final int id = i;
                cores.put(i, new TendermintCore<>(i, n, TendermintCore.roundRobin(n), h -> "fresco-p" + id,
                        v -> true));
                outbox.put(i, new ArrayList<>());
            }
        }

        void startAll() {
            cores.forEach((id, c) -> outbox.get(id).addAll(c.start()));
        }

        TendermintCore<String> core(int id) {
            return cores.get(id);
        }

        void deliver(int to, TendermintProposal<String> p) {
            outbox.get(to).addAll(core(to).onProposal(p));
        }

        void deliver(int to, TendermintVote<String> v) {
            outbox.get(to).addAll(core(to).onVote(v));
        }

        void timeout(int node, TimeoutKind kind, int round) {
            outbox.get(node).addAll(core(node).onTimeout(kind, core(node).height(), round));
        }

        /** O voto que o correto {@code from} difundiu em (h=0, round, tipo): precisa existir. */
        TendermintVote<String> sentVote(int from, Type type, int round) {
            for (TendermintAction<String> a : outbox.get(from)) {
                if (a.kind() == Kind.BROADCAST_VOTE && a.vote().type() == type && a.vote().round() == round
                        && a.vote().height() == 0) {
                    return a.vote();
                }
            }
            throw new AssertionError("no " + from + " nao difundiu " + type + " no round " + round);
        }

        TendermintProposal<String> sentProposal(int from, int round) {
            for (TendermintAction<String> a : outbox.get(from)) {
                if (a.kind() == Kind.BROADCAST_PROPOSAL && a.proposal().round() == round) {
                    return a.proposal();
                }
            }
            throw new AssertionError("no " + from + " nao propos no round " + round);
        }

        /** Entrega a {@code to} o voto de {@code from}, se ele o difundiu (sob mutacao, votos a mais aparecem). */
        void relayIfSent(int from, Type type, int round, int to) {
            for (TendermintAction<String> a : new ArrayList<>(outbox.get(from))) {
                if (a.kind() == Kind.BROADCAST_VOTE && a.vote().type() == type && a.vote().round() == round
                        && a.vote().height() == 0) {
                    deliver(to, a.vote());
                    return;
                }
            }
        }

        /** Entrega a {@code to} o voto difundido por {@code from}. */
        void relay(int from, Type type, int round, int to) {
            deliver(to, sentVote(from, type, round));
        }
    }

    private static TendermintVote<String> byz(Type t, int round, String v, int byzId) {
        return new TendermintVote<>(t, 0, round, v, byzId);
    }

    /**
     * Lock (linha 23): n = 4, bizantino = 1 (proponente do round 1). No round 0 os corretos 0 e 2 travam em v e o
     * correto 3 decide v; no round 1 o bizantino propoe w. Travados em v, 0 e 2 votam nil; sem o lock, votariam w,
     * formariam polka com o bizantino e decidiriam w.
     */
    @Test
    void lockImpedeDecisaoConflitanteEmRoundSeguinte() {
        final int b = 1;
        Script s = new Script(4, b);
        s.startAll();
        TendermintProposal<String> pv = s.sentProposal(0, 0); // proponente 0 (correto) propoe v
        String v = pv.value();
        s.deliver(2, pv);
        s.deliver(3, pv);
        // prevotes do round 0: todos os corretos votaram v
        s.relay(2, Type.PREVOTE, 0, 0);
        s.relay(3, Type.PREVOTE, 0, 0);   // 0 ve polka (0, 2, 3): trava v, precommita v
        s.relay(0, Type.PREVOTE, 0, 2);
        s.relay(3, Type.PREVOTE, 0, 2);   // 2 idem
        assertEquals(v, s.core(0).lockedValue());
        assertEquals(v, s.core(2).lockedValue());
        // 3 nao ve a polka: so o proprio v, o v de 2 e um nil do bizantino -> timeout -> precommit nil
        s.relay(2, Type.PREVOTE, 0, 3);
        s.deliver(3, byz(Type.PREVOTE, 0, null, b));
        s.timeout(3, TimeoutKind.PREVOTE, 0);
        assertNull(s.sentVote(3, Type.PRECOMMIT, 0).value());
        // 3 decide v com os precommits de 0, 2 e do bizantino
        s.relay(0, Type.PRECOMMIT, 0, 3);
        s.relay(2, Type.PRECOMMIT, 0, 3);
        s.deliver(3, byz(Type.PRECOMMIT, 0, v, b));
        assertEquals(v, s.core(3).decision(0));
        // 0 e 2 nao decidem (v, v, nil de 3, nil do bizantino) e passam ao round 1
        for (int x : new int[]{0, 2}) {
            int other = x == 0 ? 2 : 0;
            s.relay(other, Type.PRECOMMIT, 0, x);
            s.relay(3, Type.PRECOMMIT, 0, x);
            s.deliver(x, byz(Type.PRECOMMIT, 0, null, b));
            assertNull(s.core(x).decision(0));
            s.timeout(x, TimeoutKind.PRECOMMIT, 0);
            assertEquals(1, s.core(x).round());
        }
        // round 1: o bizantino propoe w a 0 e 2
        String w = "w-bizantino";
        for (int x : new int[]{0, 2}) {
            s.deliver(x, new TendermintProposal<>(0, 1, w, -1, b));
        }
        assertNull(s.sentVote(0, Type.PREVOTE, 1).value(), "travado em v: prevote nil em w");
        assertNull(s.sentVote(2, Type.PREVOTE, 1).value(), "travado em v: prevote nil em w");
        // o bizantino empurra w em tudo; mesmo assim 0 e 2 nao podem decidir w
        for (int x : new int[]{0, 2}) {
            s.relay(x == 0 ? 2 : 0, Type.PREVOTE, 1, x);
            s.deliver(x, byz(Type.PREVOTE, 1, w, b));
            s.deliver(x, byz(Type.PRECOMMIT, 1, w, b));
        }
        for (int x : new int[]{0, 2}) {
            s.relayIfSent(x == 0 ? 2 : 0, Type.PRECOMMIT, 1, x);
        }
        for (int x : new int[]{0, 2}) {
            assertTrue(s.core(x).decision(0) == null || s.core(x).decision(0).equals(v),
                    "SAFETY: no " + x + " decidiu " + s.core(x).decision(0) + ", o no 3 decidiu " + v);
        }
    }

    /**
     * Destravamento so com polka POSTERIOR ao lock (linha 29, {@code lockedRound <= vr}): n = 4, bizantino = 2
     * (proponente do round 2). Existe uma polka real de w no round 0 que ninguem viu a tempo; no round 1 os corretos
     * 0 e 1 travam em v e o correto 3 decide v; no round 2 o bizantino repropoe w com vr = 0. Como 0 &lt; lockedRound
     * = 1, 0 e 1 votam nil; sem a comparacao, destravariam e decidiriam w.
     */
    @Test
    void polkaAnteriorAoLockNaoDestrava() {
        final int b = 2;
        Script s = new Script(4, b);
        s.startAll();
        // round 0: 0 propoe w; 0 e 1 votam w, 3 vota nil por timeout; todos saem do round sem travar
        TendermintProposal<String> pw = s.sentProposal(0, 0);
        String w = pw.value();
        s.deliver(1, pw);
        s.timeout(3, TimeoutKind.PROPOSE, 0);
        for (int x : new int[]{0, 1, 3}) {
            for (int y : new int[]{0, 1, 3}) {
                if (x != y && !(x != 3 && y != 3)) { // so os votos envolvendo o 3 chegam agora
                    s.relay(y, Type.PREVOTE, 0, x);
                }
            }
            s.deliver(x, byz(Type.PREVOTE, 0, null, b));
        }
        for (int x : new int[]{0, 1}) {
            assertEquals(-1, s.core(x).lockedRound());
            s.timeout(x, TimeoutKind.PREVOTE, 0);
        }
        s.timeout(3, TimeoutKind.PREVOTE, 0);
        for (int x : new int[]{0, 1, 3}) {
            assertEquals(TendermintStep.PRECOMMIT, s.core(x).step());
            s.timeout(x, TimeoutKind.PRECOMMIT, 0);
            assertEquals(1, s.core(x).round());
        }
        // round 1: 1 propoe v; 0 e 1 travam v; 3 nao ve a polka e precommita nil
        TendermintProposal<String> pv = s.sentProposal(1, 1);
        String v = pv.value();
        s.deliver(0, pv);
        s.deliver(3, pv);
        s.relay(1, Type.PREVOTE, 1, 0);
        s.relay(3, Type.PREVOTE, 1, 0);
        s.relay(0, Type.PREVOTE, 1, 1);
        s.relay(3, Type.PREVOTE, 1, 1);
        assertEquals(1, s.core(0).lockedRound());
        assertEquals(1, s.core(1).lockedRound());
        s.relay(0, Type.PREVOTE, 1, 3);
        s.deliver(3, byz(Type.PREVOTE, 1, null, b));
        s.timeout(3, TimeoutKind.PREVOTE, 1);
        s.relay(0, Type.PRECOMMIT, 1, 3);
        s.relay(1, Type.PRECOMMIT, 1, 3);
        s.deliver(3, byz(Type.PRECOMMIT, 1, v, b));
        assertEquals(v, s.core(3).decision(0));
        for (int x : new int[]{0, 1}) {
            s.relay(x == 0 ? 1 : 0, Type.PRECOMMIT, 1, x);
            s.relay(3, Type.PRECOMMIT, 1, x);
            s.deliver(x, byz(Type.PRECOMMIT, 1, null, b));
            assertNull(s.core(x).decision(0));
            s.timeout(x, TimeoutKind.PRECOMMIT, 1);
            assertEquals(2, s.core(x).round());
        }
        // a polka de w no round 0 se completa agora nos logs de 0 e 1 (voto atrasado + w do bizantino)
        s.relay(1, Type.PREVOTE, 0, 0);
        s.relay(0, Type.PREVOTE, 0, 1);
        for (int x : new int[]{0, 1}) {
            s.deliver(x, byz(Type.PREVOTE, 0, w, b));
        }
        // round 2: o bizantino repropoe w com vr = 0
        for (int x : new int[]{0, 1}) {
            s.deliver(x, new TendermintProposal<>(0, 2, w, 0, b));
        }
        assertNull(s.sentVote(0, Type.PREVOTE, 2).value(), "lockedRound 1 > vr 0: nil");
        assertNull(s.sentVote(1, Type.PREVOTE, 2).value(), "lockedRound 1 > vr 0: nil");
        for (int x : new int[]{0, 1}) {
            s.relay(x == 0 ? 1 : 0, Type.PREVOTE, 2, x);
            s.deliver(x, byz(Type.PREVOTE, 2, w, b));
            s.deliver(x, byz(Type.PRECOMMIT, 2, w, b));
        }
        for (int x : new int[]{0, 1}) {
            s.relayIfSent(x == 0 ? 1 : 0, Type.PRECOMMIT, 2, x);
        }
        for (int x : new int[]{0, 1}) {
            assertTrue(s.core(x).decision(0) == null || s.core(x).decision(0).equals(v),
                    "SAFETY: no " + x + " decidiu " + s.core(x).decision(0) + ", o no 3 decidiu " + v);
        }
    }

    /**
     * Quorum (mais de 2/3): n = 5, bizantino = 0 (proponente do round 0) manda v a {1, 2} e w a {3, 4} e vota nos
     * dois. Com o 2f+1 = 3 de PBFT/HotStuff, cada metade formaria polka e decidiria, com valores diferentes. Com
     * mais de 2/3 (= 4), ninguem forma polka.
     */
    @Test
    void quorumDeMaisDeDoisTercosImpedeDecisaoDividida() {
        final int b = 0;
        Script s = new Script(5, b);
        s.startAll();
        int[][] halves = {{1, 2}, {3, 4}};
        String[] values = {"v", "w"};
        for (int k = 0; k < 2; k++) {
            for (int x : halves[k]) {
                s.deliver(x, new TendermintProposal<>(0, 0, values[k], -1, b));
            }
        }
        for (int k = 0; k < 2; k++) {
            int x = halves[k][0];
            int y = halves[k][1];
            s.relay(y, Type.PREVOTE, 0, x);
            s.relay(x, Type.PREVOTE, 0, y);
            for (int z : halves[k]) {
                s.deliver(z, byz(Type.PREVOTE, 0, values[k], b));
                s.deliver(z, byz(Type.PRECOMMIT, 0, values[k], b));
            }
        }
        for (int x = 1; x < 5; x++) {
            assertEquals(-1, s.core(x).lockedRound(), "3 de 5 nao e mais de 2/3: sem polka no no " + x);
            assertNull(s.core(x).decision(0));
        }
    }
}
