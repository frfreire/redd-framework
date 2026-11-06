package jabs.network.node;

/**
 * Cumulative per-node link counters for the Theta/U/C_node record (ADR-0012, RM-01, RM-03, RM-08).
 *
 * <p>One instance per {@link NetworkInterface}, created only when recording is enabled
 * ({@link NetworkInterface#enableLoadRecording()}); when it is {@code null} the simulator does exactly what it did
 * before. Plain primitive fields: no collections, no {@code hashCode}/{@code identityHashCode}, no randomness, no
 * events (RM-08).
 *
 * <p>What is counted, and where:
 * <ul>
 *   <li>{@code sentBytes}: when a packet LEAVES the send queue ({@code AbstractPacketProcessor.execute} of the
 *       sending process), i.e. when its transmission on the uplink ends (RM-01).</li>
 *   <li>{@code recvBytes}: when the receiving process hands the packet to the node (RM-01 / VT-02).</li>
 *   <li>{@code arrivedBytes}: every packet that reaches this node's downlink entry point
 *       ({@code addToDownLinkQueue}), whether or not the interface is down.</li>
 *   <li>{@code droppedAtDestBytes}: the part of {@code arrivedBytes} discarded because the interface was down.</li>
 *   <li>{@code droppedSenderDownBytes}: packets the node tried to send while its interface was down (never part of
 *       {@code sentBytes}; informational).</li>
 *   <li>busy time of the SEND queue: an interval opens when a packet enters an empty send queue and closes when the
 *       queue empties in {@code execute}; an interval still open is cut by the caller at the window end (RM-03).</li>
 * </ul>
 * Direct deliveries that bypass the queues ({@code processIncomingPacket} called by the protocol on itself) never
 * reach these methods and therefore are not counted.
 */
public final class LinkLoadCounters {
    private long sentBytes;
    private long recvBytes;
    private long arrivedBytes;
    private long droppedAtDestBytes;
    private long droppedSenderDownBytes;

    private double busyClosed;
    private boolean busyOpen;
    private double busyOpenSince;

    public LinkLoadCounters() { }

    /** The send queue went from empty to non-empty at {@code now}. */
    public void upLinkBecameBusy(double now) {
        if (busyOpen) {
            throw new IllegalStateException("busy interval already open (queue was empty): inconsistent link record");
        }
        busyOpen = true;
        busyOpenSince = now;
    }

    /** A packet left the send queue; {@code queueEmptied} tells whether the queue is empty afterwards. */
    public void packetSent(int size, boolean queueEmptied, double now) {
        sentBytes += size;
        if (queueEmptied) {
            if (!busyOpen) {
                throw new IllegalStateException("send queue emptied without an open busy interval: inconsistent link record");
            }
            busyClosed += now - busyOpenSince;
            busyOpen = false;
        }
    }

    /** The receiving process delivered a packet to the node. */
    public void packetDelivered(int size) {
        recvBytes += size;
    }

    /** A packet reached the downlink entry point of the node. */
    public void packetArrived(int size, boolean interfaceDown) {
        arrivedBytes += size;
        if (interfaceDown) {
            droppedAtDestBytes += size;
        }
    }

    /** The node tried to send while its interface was down. */
    public void senderDownDropped(int size) {
        droppedSenderDownBytes += size;
    }

    public long getSentBytes() { return sentBytes; }
    public long getRecvBytes() { return recvBytes; }
    public long getArrivedBytes() { return arrivedBytes; }
    public long getDroppedAtDestBytes() { return droppedAtDestBytes; }
    public long getDroppedSenderDownBytes() { return droppedSenderDownBytes; }

    /** True if the send queue is currently non-empty (an interval is open). */
    public boolean isBusyOpen() { return busyOpen; }

    /**
     * Busy time of the send queue accumulated up to {@code t}, cutting an open interval at {@code t}.
     * {@code t} must not precede the start of the open interval.
     */
    public double busyTimeAt(double t) {
        if (!busyOpen) {
            return busyClosed;
        }
        if (t < busyOpenSince) {
            throw new IllegalArgumentException("t=" + t + " precedes the start of the open busy interval ("
                    + busyOpenSince + ")");
        }
        return busyClosed + (t - busyOpenSince);
    }
}
