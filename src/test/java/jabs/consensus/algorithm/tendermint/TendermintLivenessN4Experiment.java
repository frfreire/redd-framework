package jabs.consensus.algorithm.tendermint;

import jabs.consensus.algorithm.tendermint.TendermintAction.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayDeque;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXP-liveness-n4-instrumento: INSTRUMENTO (nao conclusao) para a pergunta "com n=4 e 1 bizantino equivocador que e o
 * proponente, o Tendermint do JABS nao decide nenhuma altura; a causa e a falta de repasse (gossip) no modelo de rede
 * do adaptador (H_rede) ou um defeito do {@link TendermintCore} (H_nucleo)?".
 *
 * <h3>Desenho (fixado antes de ver dados)</h3>
 * Nucleos {@link TendermintCore} reais ligados por uma rede de teste EM MEMORIA (copia local da {@code Net} de
 * {@code TendermintSafetyTest}, que nao foi alterada): entrega em ordem pseudoaleatoria por semente, timeouts so
 * quando a rede esta ociosa e na ordem armada (sincronia parcial apos o GST; sem variante de timeouts assincronos).
 * O no bizantino roda um nucleo real como os corretos e, nas acoes BROADCAST_PROPOSAL e BROADCAST_VOTE de valor nao
 * nil, entrega a versao original (A) ao 1o vizinho e a versao B ({@code valor + "#B"}, valida, mesma altura e round;
 * proposta B com validRound = -1) aos demais, onde vizinhos = ids {0..n-1} menos o proprio em ordem crescente e
 * {@code half = max(1, size/2)} (n=4: size=3, half=1). Votos nil vao a todos sem mudanca. Mesma regra de
 * {@code Tendermint.broadcastProposalEquivocation/broadcastVoteEquivocation}.
 *
 * <h3>Fatores</h3>
 * <ul>
 *   <li>n=4 com f=1 (bizantino b em todos os ids) e, como replicacao, n=7 com f=2 (todos os pares de ids);</li>
 *   <li>gossip: OFF (nenhum repasse); BYZ_ONLY (no correto que recebe mensagem de remetente bizantino a repassa a
 *       todos os outros corretos, como na {@code Net} existente); FULL (todo no correto repassa a todos os outros nos,
 *       exceto a si e ao remetente, a primeira vez que ve cada mensagem distinta, dedupe por
 *       (tipo, altura, round, remetente, valor); nos bizantinos nao repassam);</li>
 *   <li>semente 0..{@link #SEEDS_N4}-1 por celula em n=4 e 0..{@link #SEEDS_N7}-1 em n=7.</li>
 * </ul>
 *
 * <h3>Bracos</h3>
 * C1_SEM_BYZ (controle: sem bizantino, deve decidir), C2_SILENT (controle: bizantino parado, sem nucleo, nada e
 * entregue a ele), T_EQUIVOCA (tratamento: equivocador acima). Para C3 (reproduzir o JABS) ver o braco T com n=4 e
 * gossip OFF no resumo.
 *
 * <h3>Metricas (uma linha de CSV por execucao, sem agregacao)</h3>
 * {@code stalled} = altura minima dos corretos &lt; {@link #H_MIN_ALVO} ao fim do orcamento de
 * {@link #MAX_PASSOS} passos (o laco para antes se todos os corretos alcancam a altura alvo). {@code agree} = nenhum
 * par de corretos decidiu valores diferentes na mesma altura. {@code maxRound} = maior round corrente observado em
 * qualquer correto, em qualquer passo. Descartes por razao = soma sobre os corretos.
 *
 * <h3>O que este modelo NAO captura do JABS</h3>
 * Latencia e banda (a ordem de entrega e uma permutacao aleatoria, sem tempo); a ordem real de eventos do simulador;
 * timeouts em segundos (aqui um timeout dispara so com a rede ociosa, na ordem armada, sem relacao com
 * {@code TendermintTimeouts}); o fato de o adaptador usar broadcast por pacote com fila de uplink (aqui a entrega e
 * instantanea e atomica por mensagem); a malha de vizinhanca do P2P (aqui, malha completa). Alem disso, os modos de
 * gossip BYZ_ONLY e FULL sao MODELOS de repasse na rede de teste, nao implementacoes no adaptador. Como corretos ja
 * enviam a todos, FULL difere de BYZ_ONLY so por repassar tambem a nos bizantinos (relevante apenas com f &gt;= 2).
 */
class TendermintLivenessN4Experiment {

    // ---- constantes do desenho (fixas; nao ajustar depois de ver os dados) ----
    /** Altura alvo: a execucao e dada como viva se todos os corretos alcancam esta altura. */
    static final long H_MIN_ALVO = 6;
    /** Orcamento de passos (entregas + timeouts) por execucao, como no teste existente. */
    static final int MAX_PASSOS = 30_000;
    static final int SEEDS_N4 = 100;
    static final int SEEDS_N7 = 20;

    enum Gossip { OFF, BYZ_ONLY, FULL }

    enum Arm { C1_SEM_BYZ, C2_SILENT, T_EQUIVOCA }

    record Cell(Arm arm, int n, Set<Integer> byz, Gossip gossip) { }

    private record Delivery(int to, Object msg) { }

    private record Timer(int node, TimeoutKind kind, long height, int round) { }

    /** Resultado bruto de uma execucao. */
    record Result(Cell cell, long seed, int steps, long minHeight, long maxHeight, int maxRound,
                  Map<Integer, Integer> decisions, boolean stalled, boolean agree,
                  Map<TendermintDiscardReason, Long> discards, long equivocations, boolean byzProposesH0R0) {

        int maxDecisions() {
            return decisions.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        String csvRow() {
            StringBuilder sb = new StringBuilder();
            sb.append(cell.arm()).append(',').append(cell.n()).append(',').append(cell.byz().size()).append(',')
                    .append(cell.byz().isEmpty() ? "-" : cell.byz().stream().map(String::valueOf)
                            .collect(Collectors.joining("+")))
                    .append(',').append(cell.gossip()).append(',').append(seed).append(',').append(steps).append(',')
                    .append(minHeight).append(',').append(maxHeight).append(',').append(maxRound).append(',')
                    .append(decisions.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                            .collect(Collectors.joining(";")))
                    .append(',').append(stalled).append(',').append(agree).append(',').append(byzProposesH0R0);
            for (TendermintDiscardReason r : TendermintDiscardReason.values()) {
                sb.append(',').append(discards.getOrDefault(r, 0L));
            }
            sb.append(',').append(equivocations);
            return sb.toString();
        }
    }

    static String csvHeader() {
        StringBuilder sb = new StringBuilder("arm,n,f,byz_ids,gossip,seed,steps,min_height,max_height,max_round,"
                + "decisions_per_correct,stalled,agree,byz_proposes_h0r0");
        for (TendermintDiscardReason r : TendermintDiscardReason.values()) {
            sb.append(",discard_").append(r.name());
        }
        return sb.append(",equivocations_observed").toString();
    }

    /** Rede de teste em memoria (copia local, adaptada, da Net de TendermintSafetyTest). */
    static final class Rede {
        final int n;
        final Set<Integer> byz;
        final Arm arm;
        final Gossip gossip;
        final Random rnd;
        final Set<Delivery> sent = new HashSet<>();
        final List<TendermintCore<String>> cores = new ArrayList<>();
        final List<Delivery> queue = new ArrayList<>();
        final ArrayDeque<Timer> timers = new ArrayDeque<>();
        /** Por no, mensagens ja vistas (FULL): chave (tipo, altura, round, remetente, valor). */
        final List<Set<String>> seen = new ArrayList<>();
        int maxRound;

        Rede(Cell cell, long seed) {
            this.n = cell.n();
            this.byz = cell.byz();
            this.arm = cell.arm();
            this.gossip = cell.gossip();
            this.rnd = new Random(seed);
            for (int i = 0; i < n; i++) {
                final int id = i;
                seen.add(new HashSet<>());
                boolean silent = arm == Arm.C2_SILENT && byz.contains(i);
                cores.add(silent ? null : new TendermintCore<>(i, n, TendermintCore.roundRobin(n),
                        h -> "h" + h + "-p" + id + "-" + rnd.nextInt(1_000_000), (h, v) -> true, 1000L));
            }
        }

        boolean equivocator(int node) {
            return arm == Arm.T_EQUIVOCA && byz.contains(node);
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
                    boolean proposal = a.kind() == Kind.BROADCAST_PROPOSAL;
                    if (equivocator(from) && a.value() != null) {
                        sendSplit(from, a, proposal);
                    } else {
                        Object msg = proposal ? a.proposal() : a.vote();
                        for (int j = 0; j < n; j++) {
                            if (j != from && cores.get(j) != null) {
                                enqueue(new Delivery(j, msg));
                            }
                        }
                    }
                } else if (a.kind() == Kind.SCHEDULE_TIMEOUT) {
                    timers.add(new Timer(from, a.timeout(), a.height(), a.round()));
                }
            }
        }

        /** Original ao 1o vizinho, versao B aos demais (vizinhos = ids menos o proprio, ordem crescente). */
        void sendSplit(int from, TendermintAction<String> a, boolean proposal) {
            List<Integer> neighbors = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j != from) {
                    neighbors.add(j);
                }
            }
            int half = Math.max(1, neighbors.size() / 2);
            Object original;
            Object alternative;
            if (proposal) {
                TendermintProposal<String> p = a.proposal();
                original = p;
                alternative = new TendermintProposal<>(p.height(), p.round(), p.value() + "#B", -1, from);
            } else {
                TendermintVote<String> v = a.vote();
                original = v;
                alternative = new TendermintVote<>(v.type(), v.height(), v.round(), v.value() + "#B", from);
            }
            for (int i = 0; i < neighbors.size(); i++) {
                enqueue(new Delivery(neighbors.get(i), i < half ? original : alternative));
            }
        }

        void enqueue(Delivery d) {
            if (sent.add(d)) {
                queue.add(d);
            }
        }

        static int senderOf(Object msg) {
            return msg instanceof TendermintProposal ? ((TendermintProposal<?>) msg).sender()
                    : ((TendermintVote<?>) msg).sender();
        }

        static String keyOf(Object msg) {
            if (msg instanceof TendermintProposal<?> p) {
                return "P," + p.height() + "," + p.round() + "," + p.sender() + "," + p.value();
            }
            TendermintVote<?> v = (TendermintVote<?>) msg;
            return v.type() + "," + v.height() + "," + v.round() + "," + v.sender() + "," + v.value();
        }

        @SuppressWarnings("unchecked")
        boolean step() {
            if (!timers.isEmpty() && queue.isEmpty()) {
                Timer t = timers.poll(); // rede ociosa: na ordem em que foram armados
                handle(t.node(), cores.get(t.node()).onTimeout(t.kind(), t.height(), t.round()));
                trackRounds();
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
            int sender = senderOf(d.msg());
            if (gossip == Gossip.BYZ_ONLY && byz.contains(sender) && !byz.contains(d.to())) {
                for (int j = 0; j < n; j++) {
                    if (cores.get(j) != null && !byz.contains(j) && j != d.to()) {
                        enqueue(new Delivery(j, d.msg()));
                    }
                }
            } else if (gossip == Gossip.FULL && !byz.contains(d.to()) && seen.get(d.to()).add(keyOf(d.msg()))) {
                for (int j = 0; j < n; j++) {
                    if (cores.get(j) != null && j != d.to() && j != sender) {
                        enqueue(new Delivery(j, d.msg()));
                    }
                }
            }
            handle(d.to(), out);
            trackRounds();
            return true;
        }

        void trackRounds() {
            for (int i = 0; i < n; i++) {
                if (cores.get(i) != null && !byz.contains(i)) {
                    maxRound = Math.max(maxRound, cores.get(i).round());
                }
            }
        }

        long minCorrectHeight() {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (cores.get(i) != null && !byz.contains(i)) {
                    min = Math.min(min, cores.get(i).height());
                }
            }
            return min;
        }

        long maxCorrectHeight() {
            long max = 0;
            for (int i = 0; i < n; i++) {
                if (cores.get(i) != null && !byz.contains(i)) {
                    max = Math.max(max, cores.get(i).height());
                }
            }
            return max;
        }

        boolean agree() {
            Map<Long, String> agreed = new HashMap<>();
            for (int i = 0; i < n; i++) {
                if (cores.get(i) == null || byz.contains(i)) {
                    continue;
                }
                for (Map.Entry<Long, String> e : cores.get(i).decisions().entrySet()) {
                    String prev = agreed.putIfAbsent(e.getKey(), e.getValue());
                    if (prev != null && !prev.equals(e.getValue())) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    static Result run(Cell cell, long seed) {
        Rede net = new Rede(cell, seed);
        net.start();
        net.trackRounds();
        int steps = 0;
        while (steps < MAX_PASSOS && net.minCorrectHeight() < H_MIN_ALVO) {
            if (!net.step()) {
                break;
            }
            steps++;
        }
        Map<Integer, Integer> decisions = new TreeMap<>();
        Map<TendermintDiscardReason, Long> discards = new LinkedHashMap<>();
        long equivocations = 0;
        for (int i = 0; i < net.n; i++) {
            TendermintCore<String> c = net.cores.get(i);
            if (c == null || net.byz.contains(i)) {
                continue;
            }
            decisions.put(i, c.decisions().size());
            equivocations += c.equivocationsObserved();
            for (TendermintDiscardReason r : TendermintDiscardReason.values()) {
                discards.merge(r, c.discardCount(r), Long::sum);
            }
        }
        long min = net.minCorrectHeight();
        boolean byzFirst = net.byz.contains(TendermintCore.roundRobin(net.n).proposer(0, 0));
        return new Result(cell, seed, steps, min, net.maxCorrectHeight(), net.maxRound, decisions, min < H_MIN_ALVO,
                net.agree(), discards, equivocations, byzFirst);
    }

    // ---- plano de celulas ----

    static List<Cell> plan() {
        List<Cell> cells = new ArrayList<>();
        Gossip[] ctrl = {Gossip.OFF, Gossip.FULL};
        for (Gossip g : ctrl) {
            cells.add(new Cell(Arm.C1_SEM_BYZ, 4, Set.of(), g));
            cells.add(new Cell(Arm.C1_SEM_BYZ, 7, Set.of(), g));
        }
        for (int b = 0; b < 4; b++) {
            for (Gossip g : ctrl) {
                cells.add(new Cell(Arm.C2_SILENT, 4, Set.of(b), g));
            }
        }
        for (int b = 0; b < 4; b++) {
            for (Gossip g : Gossip.values()) {
                cells.add(new Cell(Arm.T_EQUIVOCA, 4, Set.of(b), g));
            }
        }
        for (int a = 0; a < 7; a++) {
            for (int b = a + 1; b < 7; b++) {
                for (Gossip g : Gossip.values()) {
                    cells.add(new Cell(Arm.T_EQUIVOCA, 7, new TreeSet<>(Set.of(a, b)), g));
                }
            }
        }
        return cells;
    }

    static int seedsFor(Cell cell) {
        return cell.n() == 4 ? SEEDS_N4 : SEEDS_N7;
    }

    static int expectedRows() {
        return plan().stream().mapToInt(TendermintLivenessN4Experiment::seedsFor).sum();
    }

    // ---- teste ----

    @Test
    void instrumentoDeLivenessN4() throws IOException {
        long t0 = System.nanoTime();
        List<Result> results = new ArrayList<>();
        for (Cell cell : plan()) {
            for (long seed = 0; seed < seedsFor(cell); seed++) {
                results.add(run(cell, seed));
            }
        }
        Path dir = Paths.get(System.getProperty("exp.out", "target/exp-liveness-n4"));
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        lines.add(csvHeader());
        results.forEach(r -> lines.add(r.csvRow()));
        Files.write(dir.resolve("raw.csv"), lines, StandardCharsets.UTF_8);

        String summary = summarize(results);
        Files.writeString(dir.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println("tempo (s): " + (System.nanoTime() - t0) / 1_000_000_000L);

        // invariantes do instrumento (nao do resultado de liveness dos bracos T/C2/C3)
        assertEquals(expectedRows(), results.size(), "numero de linhas do CSV");
        assertEquals(results.size() + 1, Files.readAllLines(dir.resolve("raw.csv")).size());
        for (Result r : results) {
            int f = r.cell().byz().size();
            if (f <= (r.cell().n() - 1) / 3) {
                assertTrue(r.agree(), "SAFETY violada: " + r.csvRow());
            }
            if (r.cell().arm() == Arm.C1_SEM_BYZ) {
                assertTrue(!r.stalled() && r.minHeight() >= H_MIN_ALVO, "C1 deve decidir: " + r.csvRow());
            }
        }
    }

    @Test
    void mesmaSementeMesmaSaida() {
        List<Cell> cells = plan();
        for (int i = 0; i < cells.size(); i += 7) {
            Cell cell = cells.get(i);
            for (long seed : new long[]{0, 3}) {
                assertEquals(run(cell, seed).csvRow(), run(cell, seed).csvRow(), cell + " seed=" + seed);
            }
        }
    }

    // ---- resumo (so contagens) ----

    static String summarize(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("H_MIN_ALVO=").append(H_MIN_ALVO).append(" MAX_PASSOS=").append(MAX_PASSOS)
                .append(" SEEDS_N4=").append(SEEDS_N4).append(" SEEDS_N7=").append(SEEDS_N7)
                .append(" linhas=").append(results.size()).append('\n');
        Map<String, List<Result>> groups = new LinkedHashMap<>();
        for (Result r : results) {
            groups.computeIfAbsent(r.cell().arm() + " n=" + r.cell().n() + " gossip=" + r.cell().gossip(),
                    k -> new ArrayList<>()).add(r);
        }
        sb.append(String.format("%-40s %6s %9s %8s%n", "braco/n/gossip", "exec", "%stalled", "%agree"));
        for (Map.Entry<String, List<Result>> e : groups.entrySet()) {
            List<Result> g = e.getValue();
            sb.append(String.format("%-40s %6d %8.1f%% %7.1f%%%n", e.getKey(), g.size(),
                    100.0 * g.stream().filter(Result::stalled).count() / g.size(),
                    100.0 * g.stream().filter(Result::agree).count() / g.size()));
        }
        sb.append("\nPor byz (n=4, f=1): exec | %stalled | %sem nenhuma decisao | %maxRound>=1 | alturaMin mediana\n");
        Map<String, List<Result>> byB = new TreeMap<>();
        for (Result r : results) {
            if (r.cell().n() == 4 && r.cell().arm() != Arm.C1_SEM_BYZ) {
                byB.computeIfAbsent(r.cell().arm() + " byz=" + r.cell().byz() + " gossip=" + r.cell().gossip(),
                        k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<String, List<Result>> e : byB.entrySet()) {
            List<Result> g = e.getValue();
            List<Long> hs = g.stream().map(Result::minHeight).sorted().collect(Collectors.toList());
            sb.append(String.format("%-40s %4d | %6.1f%% | %6.1f%% | %6.1f%% | %d%n", e.getKey(), g.size(),
                    100.0 * g.stream().filter(Result::stalled).count() / g.size(),
                    100.0 * g.stream().filter(r -> r.maxDecisions() == 0).count() / g.size(),
                    100.0 * g.stream().filter(r -> r.maxRound() >= 1).count() / g.size(),
                    hs.get(hs.size() / 2)));
        }
        return sb.toString();
    }
}
