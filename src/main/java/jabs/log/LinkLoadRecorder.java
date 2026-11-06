package jabs.log;

import jabs.network.node.LinkLoadCounters;
import jabs.network.node.nodes.Node;
import jabs.simulator.Simulator;
import jabs.simulator.event.Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Per-node link-load record (ADR-0012, task M1a-1): bytes sent and received, send-queue busy time and utilization
 * U_i over an explicit window {@code W = [t0, t1]} (RM-01, RM-03, RM-06, RM-08, RM-09). Writes two NEW files,
 * {@code <prefix>-nos.csv} (one row per node) and {@code <prefix>-corrida.csv} (one row per run). Does not touch any
 * existing output.
 *
 * <p><b>Off by default.</b> Nothing happens unless a scenario explicitly calls {@code AddNewLogger} with an
 * instance of this class. The counters live in {@link LinkLoadCounters} (primitive arrays indexed by
 * {@code getNodeID()}): no {@code hashCode}, no {@code identityHashCode}, no hash collections keyed by
 * Node/Packet/Block/Message, no {@code RandomnessEngine}, no new simulator event (RM-08). This class only READS the
 * event queue (snapshot at t0, residual scan) and never schedules anything.
 *
 * <p><b>Window.</b> {@code t0} comes from the caller (no embedded value, no embedded transient). The counters are
 * snapshotted before the first event whose time is {@code >= t0} (so everything executed strictly before t0 is
 * excluded and events at exactly t0 are inside) and subtracted at the end. {@code t1} is the simulation time of the
 * last executed event. An open busy interval is cut at t0 and at t1. If the run ends at or before t0 the record
 * FAILS LOUD ({@link IllegalStateException}): no empty or implicit window.
 *
 * <p><b>Conservation (VT-02).</b> Over the window,
 * {@code SentTotal = RecvTotal + DroppedAtDest + ResidualBytes - InFlightAtT0}, where {@code ResidualBytes} /
 * {@code InFlightAtT0} are bytes still on the wire (scheduled {@code PacketDeliveryEvent}) or waiting in a receive
 * queue, measured by scanning the queues, NOT derived from the counters. With {@code t0 = 0} the last term is 0.
 * A violation is a bug in the instrumentation and aborts the record.
 *
 * <p><b>Sentinel.</b> {@code -1} = not measured, different from 0. In this task ConfRate, Theta, U_block_max and
 * UnattributedSent_B are not measured ({@code -1}, {@code ThetaStatus=NOT_MEASURED}); M1a-2/M1b fill them.
 */
public class LinkLoadRecorder extends AbstractLogger {
    /**
     * Version label of the simulator that produced the record, written in both files (ADR-0012 Verification 6).
     * The {@code pom.xml} version stays 0.2.0; the suffix marks the output schema introduced by task M1a-1.
     */
    public static final String SIMULATOR_VERSION = "0.2.0+m1a1";

    /** Sentinel for "not measured" (ADR-0012 RM-09). */
    public static final long NOT_MEASURED = -1L;

    /** Command-line flag that turns the record on ({@code true}) or off ({@code false}, default). */
    public static final String FLAG_RECORD = "load-record";

    /** Command-line flag with the window start t0 in simulated seconds. Mandatory when the record is on. */
    public static final String FLAG_T0 = "load-record-t0";

    private static final double U_TOLERANCE = 1e-9;

    private final String prefix;
    private final String protocol;
    private final long seed;
    private final double t0;

    private Node[] nodesById;
    private LinkLoadCounters[] counters;
    private boolean snapshotTaken;
    private long[] sent0;
    private long[] recv0;
    private long[] dropDest0;
    private long[] dropSender0;
    private double[] busy0;
    private long inFlight0;

    private Result result;

    /**
     * @param prefix   path prefix of the output files ({@code <prefix>-nos.csv}, {@code <prefix>-corrida.csv})
     * @param protocol protocol label written in the run file
     * @param seed     run seed written in the files
     * @param t0       window start in simulated seconds, explicit; must be finite and {@code >= 0}
     */
    public LinkLoadRecorder(String prefix, String protocol, long seed, double t0) {
        if (Double.isNaN(t0) || Double.isInfinite(t0) || t0 < 0) {
            throw new IllegalArgumentException("window start t0 must be a finite number >= 0 (got " + t0 + ")");
        }
        this.prefix = prefix;
        this.protocol = protocol;
        this.seed = seed;
        this.t0 = t0;
    }

    /**
     * Interprets {@code --load-record} / {@code --load-record-t0}. Returns {@code null} when the record is off
     * (default); the window start when it is on. FAILS LOUD when the record is on without t0, when t0 is given
     * without the record being on, or when a value is invalid.
     *
     * @param recordFlag value of {@code --load-record}, or {@code null} if absent
     * @param t0Flag     value of {@code --load-record-t0}, or {@code null} if absent
     * @return window start in seconds, or {@code null} if the record is off
     */
    public static Double parseWindowStart(String recordFlag, String t0Flag) {
        boolean on;
        if (recordFlag == null || recordFlag.equals("false")) {
            on = false;
        } else if (recordFlag.equals("true")) {
            on = true;
        } else {
            throw new IllegalArgumentException("--" + FLAG_RECORD + "='" + recordFlag + "' invalid. Valid: true, false"
                    + " (absent = false)");
        }
        if (!on) {
            if (t0Flag != null) {
                throw new IllegalArgumentException("--" + FLAG_T0 + " given but --" + FLAG_RECORD
                        + " is not true: refusing to ignore it silently");
            }
            return null;
        }
        if (t0Flag == null) {
            throw new IllegalArgumentException("--" + FLAG_RECORD + "=true requires an explicit --" + FLAG_T0
                    + "=<seconds>: there is no implicit window and no embedded transient (ADR-0012 RM-06)");
        }
        double t0;
        try {
            t0 = Double.parseDouble(t0Flag.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + FLAG_T0 + "='" + t0Flag + "' is not a number", e);
        }
        if (Double.isNaN(t0) || Double.isInfinite(t0) || t0 < 0) {
            throw new IllegalArgumentException("--" + FLAG_T0 + "='" + t0Flag + "' must be finite and >= 0");
        }
        return t0;
    }

    /** Result of the record, available after {@code finalLog}; {@code null} before. */
    public Result getResult() {
        return result;
    }

    @Override
    public void initialLog() {
        List<?> nodes = scenario.getNetwork().getAllNodes();
        int n = nodes.size();
        if (n == 0) {
            throw new IllegalStateException("link-load record: the network has no nodes");
        }
        nodesById = new Node[n];
        counters = new LinkLoadCounters[n];
        for (Object o : nodes) {
            Node node = (Node) o;
            int id = node.getNodeID();
            if (id < 0 || id >= n || counters[id] != null) {
                throw new IllegalStateException("link-load record needs unique node IDs in 0..n-1 (n=" + n
                        + "); node with ID " + id + " is out of range or duplicated");
            }
            nodesById[id] = node;
            counters[id] = node.getNodeNetworkInterface().enableLoadRecording();
        }
    }

    @Override
    public void logBeforeEachEvent(Event event) {
        if (!snapshotTaken && scenario.getSimulator().peekEventTime() >= t0) {
            takeSnapshot();
        }
    }

    @Override
    public void logAfterEachEvent(Event event) {
    }

    private void takeSnapshot() {
        int n = counters.length;
        sent0 = new long[n];
        recv0 = new long[n];
        dropDest0 = new long[n];
        dropSender0 = new long[n];
        busy0 = new double[n];
        for (int i = 0; i < n; i++) {
            LinkLoadCounters c = counters[i];
            sent0[i] = c.getSentBytes();
            recv0[i] = c.getRecvBytes();
            dropDest0[i] = c.getDroppedAtDestBytes();
            dropSender0[i] = c.getDroppedSenderDownBytes();
            busy0[i] = c.busyTimeAt(t0);
        }
        inFlight0 = residualNow();
        snapshotTaken = true;
    }

    /** Bytes on the wire (scheduled deliveries) plus bytes waiting in receive queues, by scanning the queues. */
    private long residualNow() {
        Simulator sim = scenario.getSimulator();
        long total = sim.pendingPacketDeliveryBytes();
        for (Node node : nodesById) {
            total += node.getNodeNetworkInterface().receiveQueueBytes();
        }
        return total;
    }

    @Override
    public void finalLog() throws IOException {
        double t1 = scenario.getSimulator().getSimulationTime();
        if (!snapshotTaken || t1 <= t0) {
            throw new IllegalStateException("link-load record: window start t0=" + t0 + " s is not inside the run"
                    + " (last executed event at " + t1 + " s): empty window, nothing measured");
        }
        int n = counters.length;
        double window = t1 - t0;
        long[] sent = new long[n];
        long[] recv = new long[n];
        double[] busy = new double[n];
        double[] u = new double[n];
        long sentTotal = 0;
        long recvTotal = 0;
        long droppedAtDest = 0;
        long droppedSenderDown = 0;
        double uMax = -1;
        int uArgmax = -1;
        for (int i = 0; i < n; i++) {
            LinkLoadCounters c = counters[i];
            sent[i] = c.getSentBytes() - sent0[i];
            recv[i] = c.getRecvBytes() - recv0[i];
            busy[i] = c.busyTimeAt(t1) - busy0[i];
            double ui = busy[i] / window;
            if (ui < -U_TOLERANCE || ui > 1.0 + U_TOLERANCE) {
                throw new IllegalStateException("link-load record: U of node " + i + " = " + ui
                        + " outside [0,1]; inconsistent busy-time integration");
            }
            u[i] = Math.min(Math.max(ui, 0.0), 1.0);
            sentTotal += sent[i];
            recvTotal += recv[i];
            droppedAtDest += c.getDroppedAtDestBytes() - dropDest0[i];
            droppedSenderDown += c.getDroppedSenderDownBytes() - dropSender0[i];
            if (u[i] > uMax) { // strict: ties resolve to the smallest NodeID
                uMax = u[i];
                uArgmax = i;
            }
        }
        long residual = residualNow();
        if (sentTotal != recvTotal + droppedAtDest + residual - inFlight0) {
            throw new IllegalStateException("link-load record: byte conservation violated: Sent=" + sentTotal
                    + " Recv=" + recvTotal + " DroppedAtDest=" + droppedAtDest + " Residual=" + residual
                    + " InFlightAtT0=" + inFlight0 + " (instrumentation bug, record discarded)");
        }
        result = new Result(protocol, seed, n, t0, t1, sent, recv, busy, u, uMax, uArgmax, sentTotal, recvTotal,
                droppedAtDest, droppedSenderDown, residual, inFlight0);
        write(result);
    }

    private void write(Result r) throws IOException {
        Path nos = Paths.get(prefix + "-nos.csv");
        Path corrida = Paths.get(prefix + "-corrida.csv");
        if (nos.getParent() != null) {
            Files.createDirectories(nos.getParent());
        }
        String common = new StringBuilder("# Seed=").append(r.seed).append("; SimulatorVersion=")
                .append(SIMULATOR_VERSION).append("; Protocol=").append(r.protocol).append("; n=").append(r.n)
                .append(" (nodes in the built network); window W=[WindowStart_s, WindowEnd_s]=[")
                .append(fmt(r.t0)).append(", ").append(fmt(r.t1)).append("] s (t0 explicit on the command line;"
                        + " t1 = time of the last executed event; counters snapshotted at t0 and subtracted)\n")
                .toString();
        String granularity = "# Granularity: PER NODE over W. SentBytes = sum of packet.getSize() of packets whose"
                + " transmission on the node uplink ended (left the send queue; packets to self that pass the queue"
                + " count; direct deliveries do not). RecvBytes = bytes the receiving process delivered to the node."
                + " BusyTime_s = time the send queue was non-empty, integrated per event, cut at t0 and t1."
                + " U_i = BusyTime_s / (WindowEnd_s - WindowStart_s), in [0,1]. -1 = not measured (differs from 0)."
                + " Busy time of a burst follows the simulator link model: sum(T_j) + (T_1 - T_n).\n";
        StringBuilder sb = new StringBuilder(common).append(granularity)
                .append("NodeID,SentBytes,RecvBytes,BusyTime_s,U_i\n");
        for (int i = 0; i < r.n; i++) {
            sb.append(i).append(',').append(r.sent[i]).append(',').append(r.recv[i]).append(',')
                    .append(fmt(r.busy[i])).append(',').append(fmt(r.u[i])).append('\n');
        }
        Files.writeString(nos, sb.toString(), StandardCharsets.UTF_8);

        String corridaGranularity = "# Granularity: run totals over W; U_run_max = max over nodes of U_i (run-level,"
                + " not per block); ResidualBytes_B = bytes on the wire or in receive queues at WindowEnd_s;"
                + " InFlightAtT0_B = same at WindowStart_s; Sent = Recv + DroppedAtDest + Residual - InFlightAtT0."
                + " DroppedSenderDown_B is informational and never part of SentTotal_B. ConfRate_tx_per_s,"
                + " Theta_tx_per_s, U_block_max and UnattributedSent_B are -1 (not measured in this version);"
                + " ThetaStatus=NOT_MEASURED.\n";
        StringBuilder sc = new StringBuilder(common).append(corridaGranularity)
                .append("Seed,SimulatorVersion,Protocol,n,WindowStart_s,WindowEnd_s,ConfRate_tx_per_s,"
                        + "Theta_tx_per_s,ThetaStatus,U_run_max,U_run_argmaxNodeID,U_block_max,SentTotal_B,"
                        + "RecvTotal_B,DroppedAtDest_B,DroppedSenderDown_B,ResidualBytes_B,UnattributedSent_B,"
                        + "InFlightAtT0_B\n")
                .append(r.seed).append(',').append(SIMULATOR_VERSION).append(',').append(r.protocol).append(',')
                .append(r.n).append(',').append(fmt(r.t0)).append(',').append(fmt(r.t1)).append(',')
                .append(NOT_MEASURED).append(',').append(NOT_MEASURED).append(",NOT_MEASURED,")
                .append(fmt(r.uRunMax)).append(',').append(r.uRunArgmaxNodeId).append(',').append(NOT_MEASURED)
                .append(',').append(r.sentTotal).append(',').append(r.recvTotal).append(',')
                .append(r.droppedAtDest).append(',').append(r.droppedSenderDown).append(',').append(r.residual)
                .append(',').append(NOT_MEASURED).append(',').append(r.inFlightAtT0).append('\n');
        Files.writeString(corrida, sc.toString(), StandardCharsets.UTF_8);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.9f", v);
    }

    /** Measured values of one run. Arrays are indexed by {@code NodeID}. */
    public static final class Result {
        public final String protocol;
        public final long seed;
        public final int n;
        public final double t0;
        public final double t1;
        public final long[] sent;
        public final long[] recv;
        public final double[] busy;
        public final double[] u;
        public final double uRunMax;
        public final int uRunArgmaxNodeId;
        public final long sentTotal;
        public final long recvTotal;
        public final long droppedAtDest;
        public final long droppedSenderDown;
        public final long residual;
        public final long inFlightAtT0;

        Result(String protocol, long seed, int n, double t0, double t1, long[] sent, long[] recv, double[] busy,
               double[] u, double uRunMax, int uRunArgmaxNodeId, long sentTotal, long recvTotal, long droppedAtDest,
               long droppedSenderDown, long residual, long inFlightAtT0) {
            this.protocol = protocol;
            this.seed = seed;
            this.n = n;
            this.t0 = t0;
            this.t1 = t1;
            this.sent = sent;
            this.recv = recv;
            this.busy = busy;
            this.u = u;
            this.uRunMax = uRunMax;
            this.uRunArgmaxNodeId = uRunArgmaxNodeId;
            this.sentTotal = sentTotal;
            this.recvTotal = recvTotal;
            this.droppedAtDest = droppedAtDest;
            this.droppedSenderDown = droppedSenderDown;
            this.residual = residual;
            this.inFlightAtT0 = inFlightAtT0;
        }
    }
}
