package jabs.consensus.algorithm.tendermint;

import jabs.consensus.algorithm.tendermint.TendermintAction.Kind;
import jabs.consensus.algorithm.tendermint.TendermintVote.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Varios nucleos ligados por uma rede de teste em memoria (sem JABS), com entrega em ordem aleatoria e semente fixa.
 *
 * <ul>
 *   <li><b>Safety</b>: com ate f bizantinos que equivocam (propostas e votos diferentes para nos diferentes) e
 *       timeouts disparando a qualquer momento (assincronia), dois nos corretos nunca decidem valores diferentes na
 *       mesma altura;</li>
 *   <li><b>Liveness</b>: com timeouts so quando a rede esta ociosa (sincronia apos o GST), os nos corretos avancam
 *       mesmo com proponente parado ou bizantino.</li>
 * </ul>
 */
class TendermintSafetyTest {

    /**
     * SILENT: parado. EQUIVOCATE: um voto por destinatario, valor sorteado. EQUIVOCATE_ALL: assina para cada
     * destinatario TODOS os valores candidatos e nil (equivocacao maxima: conta em todo quorum).
     */
    enum Byzantine { SILENT, EQUIVOCATE, EQUIVOCATE_ALL }

    private record Delivery(int to, Object msg) { }

    private record Timer(int node, TimeoutKind kind, long height, int round) { }

    /** Rede de teste. Valores sao Strings; todo valor e valido (o bizantino propoe valores validos). */
    static final class Net {
        final int n;
        final Set<Integer> byz;
        final Byzantine mode;
        final Random rnd;
        final boolean asyncTimers;
        final double timerProbability;
        /** Repasse (gossip) do que os corretos recebem de bizantinos: a suposicao de entrega do artigo. */
        boolean gossip = true;
        final Set<Delivery> sent = new HashSet<>();
        final List<TendermintCore<String>> cores = new ArrayList<>();
        final List<Delivery> queue = new ArrayList<>();
        final ArrayDeque<Timer> timers = new ArrayDeque<>();
        final Map<Long, Set<String>> seenValues = new HashMap<>();
        final Set<String> byzEmitted = new HashSet<>();

        Net(int n, Set<Integer> byz, Byzantine mode, long seed, boolean asyncTimers, double timerProbability) {
            this.n = n;
            this.byz = byz;
            this.mode = mode;
            this.rnd = new Random(seed);
            this.asyncTimers = asyncTimers;
            this.timerProbability = timerProbability;
            for (int i = 0; i < n; i++) {
                final int id = i;
                cores.add(byz.contains(i) ? null : new TendermintCore<>(i, n, TendermintCore.roundRobin(n),
                        h -> "h" + h + "-p" + id + "-" + rnd.nextInt(1_000_000), v -> true));
            }
        }

        void start() {
            for (int i = 0; i < n; i++) {
                if (cores.get(i) != null) {
                    handle(i, cores.get(i).start());
                }
            }
        }

        void handle(int from, List<TendermintAction<String>> actions) {
            for (TendermintAction<String> a : actions) {
                if (a.kind() == Kind.BROADCAST_PROPOSAL || a.kind() == Kind.BROADCAST_VOTE) {
                    Object msg = a.kind() == Kind.BROADCAST_PROPOSAL ? a.proposal() : a.vote();
                    if (a.value() != null) {
                        seenValues.computeIfAbsent(a.height(), k -> new LinkedHashSet<>()).add(a.value());
                    }
                    for (int j = 0; j < n; j++) {
                        if (j != from && cores.get(j) != null) {
                            enqueue(new Delivery(j, msg));
                        }
                    }
                    if (mode != Byzantine.SILENT) {
                        reactByzantine(a.height(), a.round());
                    }
                } else if (a.kind() == Kind.SCHEDULE_TIMEOUT) {
                    timers.add(new Timer(from, a.timeout(), a.height(), a.round()));
                }
            }
        }

        /**
         * Para cada (h, r) que aparece na rede, cada bizantino manda UMA proposta (se for o proponente) e UM voto de
         * cada tipo a cada no correto, escolhendo o valor ao acaso por destinatario entre os valores ja vistos na
         * altura, dois valores proprios e nil: equivocacao maxima com um voto por destinatario.
         */
        void reactByzantine(long h, int r) {
            for (int b : byz) {
                String key = b + ":" + h + ":" + r;
                if (!byzEmitted.add(key)) {
                    continue;
                }
                List<String> candidates = new ArrayList<>(seenValues.getOrDefault(h, Set.of()));
                String ownA = "byzA-" + b + "-" + h + "-" + r;
                String ownB = "byzB-" + b + "-" + h + "-" + r;
                candidates.add(ownA);
                candidates.add(ownB);
                boolean proposer = Math.floorMod(h + r, (long) n) == b;
                for (int j = 0; j < n; j++) {
                    if (cores.get(j) == null) {
                        continue;
                    }
                    if (mode == Byzantine.EQUIVOCATE_ALL) {
                        if (proposer) {
                            // ordem sorteada por destinatario: corretos diferentes votam em propostas diferentes
                            boolean aFirst = rnd.nextBoolean();
                            enqueue(new Delivery(j, new TendermintProposal<>(h, r, aFirst ? ownA : ownB, -1, b)));
                            enqueue(new Delivery(j, new TendermintProposal<>(h, r, aFirst ? ownB : ownA, -1, b)));
                        }
                        for (Type t : Type.values()) {
                            enqueue(new Delivery(j, new TendermintVote<>(t, h, r, null, b)));
                            for (String c : candidates) {
                                enqueue(new Delivery(j, new TendermintVote<>(t, h, r, c, b)));
                            }
                        }
                        continue;
                    }
                    if (proposer) {
                        String pv = rnd.nextBoolean() ? ownA : ownB;
                        enqueue(new Delivery(j, new TendermintProposal<>(h, r, pv, -1, b)));
                    }
                    for (Type t : Type.values()) {
                        String v = rnd.nextInt(4) == 0 ? null : candidates.get(rnd.nextInt(candidates.size()));
                        enqueue(new Delivery(j, new TendermintVote<>(t, h, r, v, b)));
                    }
                }
                seenValues.computeIfAbsent(h, k -> new LinkedHashSet<>()).add(ownA);
                seenValues.get(h).add(ownB);
            }
        }

        void enqueue(Delivery d) {
            if (sent.add(d)) {
                queue.add(d);
            }
        }

        private static int senderOf(Object msg) {
            return msg instanceof TendermintProposal ? ((TendermintProposal<?>) msg).sender()
                    : ((TendermintVote<?>) msg).sender();
        }

        @SuppressWarnings("unchecked")
        boolean step() {
            boolean fireTimer = !timers.isEmpty()
                    && (queue.isEmpty() || (asyncTimers && rnd.nextDouble() < timerProbability));
            if (fireTimer) {
                Timer t;
                if (asyncTimers) {
                    List<Timer> list = new ArrayList<>(timers);
                    t = list.get(rnd.nextInt(list.size()));
                    timers.remove(t);
                } else {
                    t = timers.poll(); // ociosa: na ordem em que foram armados
                }
                handle(t.node(), cores.get(t.node()).onTimeout(t.kind(), t.height(), t.round()));
                return true;
            }
            if (queue.isEmpty()) {
                return false;
            }
            Delivery d = queue.remove(rnd.nextInt(queue.size()));
            TendermintCore<String> c = cores.get(d.to());
            List<TendermintAction<String>> out = d.msg() instanceof TendermintProposal
                    ? c.onProposal((TendermintProposal<String>) d.msg())
                    : c.onVote((TendermintVote<String>) d.msg());
            if (gossip && byz.contains(senderOf(d.msg()))) {
                for (int j = 0; j < n; j++) {
                    if (cores.get(j) != null && j != d.to()) {
                        enqueue(new Delivery(j, d.msg()));
                    }
                }
            }
            handle(d.to(), out);
            return true;
        }

        void runUntilHeight(long target, int maxSteps) {
            for (int s = 0; s < maxSteps && minHonestHeight() < target; s++) {
                if (!step()) {
                    break;
                }
            }
        }

        long minHonestHeight() {
            long min = Long.MAX_VALUE;
            for (TendermintCore<String> c : cores) {
                if (c != null) {
                    min = Math.min(min, c.height());
                }
            }
            return min;
        }

        /** Falha se dois corretos decidiram valores diferentes na mesma altura. Devolve o numero de alturas. */
        int assertAgreement(String context) {
            Map<Long, String> agreed = new HashMap<>();
            for (TendermintCore<String> c : cores) {
                if (c == null) {
                    continue;
                }
                for (Map.Entry<Long, String> e : c.decisions().entrySet()) {
                    String prev = agreed.putIfAbsent(e.getKey(), e.getValue());
                    if (prev != null) {
                        assertEquals(prev, e.getValue(), "SAFETY violada na altura " + e.getKey() + " " + context);
                    }
                }
            }
            return agreed.size();
        }
    }

    private static Set<Integer> ids(int... ids) {
        Set<Integer> s = new LinkedHashSet<>();
        for (int i : ids) {
            s.add(i);
        }
        return s;
    }

    @Test
    void caminhoFelizTodosDecidemNoRoundZero() {
        for (int n : new int[]{4, 7, 10}) {
            Net net = new Net(n, Set.of(), Byzantine.SILENT, 1L, false, 0);
            net.start();
            net.runUntilHeight(5, 200_000);
            assertTrue(net.minHonestHeight() >= 5, "n=" + n);
            net.assertAgreement("n=" + n);
            for (TendermintCore<String> c : net.cores) {
                assertEquals(0, c.equivocationsObserved());
            }
        }
    }

    @Test
    void proponenteParadoNaoTravaARede() {
        // no 0 parado: e proponente de (h, r) com (h + r) mod 4 == 0, inclusive (0, 0)
        Net net = new Net(4, ids(0), Byzantine.SILENT, 7L, false, 0);
        net.start();
        net.runUntilHeight(8, 200_000);
        assertTrue(net.minHonestHeight() >= 8);
        net.assertAgreement("proponente parado");
    }

    @Test
    void livenessComBizantinosQueEquivocamAposGst() {
        for (long seed = 0; seed < 30; seed++) {
            Net net = new Net(7, ids(1, 4), Byzantine.EQUIVOCATE, seed, false, 0);
            net.start();
            net.runUntilHeight(4, 400_000);
            assertTrue(net.minHonestHeight() >= 4, "seed=" + seed + " altura=" + net.minHonestHeight());
            net.assertAgreement("seed=" + seed);
        }
    }

    /**
     * Evidencia para o adaptador: SEM repasse, bizantinos que equivocam podem deixar corretos para tras (decidiram
     * h contando votos que os outros nunca viram) e a rede para de decidir. Nao e defeito do nucleo; e a suposicao de
     * gossip do artigo sendo violada.
     */
    @Test
    void semRepasseBizantinosPodemPararARede() {
        int stalled = 0;
        for (long seed = 0; seed < 10; seed++) {
            Net net = new Net(7, ids(1, 4), Byzantine.EQUIVOCATE, seed, false, 0);
            net.gossip = false;
            net.start();
            net.runUntilHeight(12, 30_000);
            net.assertAgreement("sem repasse, seed=" + seed); // safety vale mesmo assim
            if (net.minHonestHeight() < 12) {
                stalled++;
            }
        }
        assertTrue(stalled > 0, "esperava ao menos uma execucao parada sem repasse");
    }

    /**
     * Fuzzing de safety. Complementa, nao substitui, os ataques roteirizados de
     * {@link TendermintAttackScenariosTest}: aqui a ordem e aleatoria e raramente monta o cenario que explora um
     * lock quebrado (medido: com o lock removido, 2 violacoes em 3.600 execucoes). O que este teste pega bem sao
     * erros de contagem (ex.: voto nil contado como polka foi pego na primeira bateria).
     */
    @Test
    void safetyComBizantinosEquivocandoETimeoutsAssincronos() {
        int[][] configs = {{4, 0}, {7, 1, 4}, {10, 2, 5, 9}};
        double[] probabilities = {0.02, 0.1, 0.3};
        int heightsChecked = 0;
        int runsWithRoundChanges = 0;
        for (Byzantine mode : new Byzantine[]{Byzantine.EQUIVOCATE, Byzantine.EQUIVOCATE_ALL}) {
            for (int[] cfg : configs) {
                int n = cfg[0];
                Set<Integer> byz = new LinkedHashSet<>();
                for (int i = 1; i < cfg.length; i++) {
                    byz.add(cfg[i]);
                }
                for (double p : probabilities) {
                    for (long seed = 0; seed < 20; seed++) {
                        Net net = new Net(n, byz, mode, seed * 31 + n, true, p);
                        net.start();
                        net.runUntilHeight(6, 15_000);
                        heightsChecked += net.assertAgreement(mode + " n=" + n + " p=" + p + " seed=" + seed);
                        for (TendermintCore<String> c : net.cores) {
                            if (c != null && c.discardCount(TendermintDiscardReason.STALE_TIMEOUT) > 0) {
                                runsWithRoundChanges++;
                                break;
                            }
                        }
                    }
                }
            }
        }
        // o teste so vale se de fato exercitou decisoes e trocas de round
        assertTrue(heightsChecked > 300, "alturas verificadas: " + heightsChecked);
        assertTrue(runsWithRoundChanges > 100, "execucoes com timeouts obsoletos: " + runsWithRoundChanges);
    }
}
