package jabs.simulator.event;

import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;

import java.util.PriorityQueue;

public abstract class AbstractPacketProcessor implements Event {
    protected final Simulator simulator;
    protected final Network network;
    protected final RandomnessEngine randomnessEngine;
    protected final PriorityQueue<TimedPacket> packetsQueue = new PriorityQueue<>();

    private record TimedPacket(Packet packet, double time) implements Comparable<TimedPacket> {
        public int compareTo(TimedPacket o) {
                return Double.compare(this.time, o.time);
            }
    }

    protected final Node node;

    public AbstractPacketProcessor(Simulator simulator, Network network, RandomnessEngine randomnessEngine, Node node) {
        this.simulator = simulator;
        this.network = network;
        this.randomnessEngine = randomnessEngine;
        this.node = node;
    }

    public boolean isQueueEmpty() {
        return (packetsQueue.isEmpty());
    }

    public void addToQueue(Packet packet) {
        TimedPacket timedPacket = new TimedPacket(packet, simulator.getSimulationTime());
        packetsQueue.add(timedPacket);
    }

    public Packet peek() {
        TimedPacket timedPacket = this.packetsQueue.peek();
        return timedPacket.packet;
    }

    public void execute() {
        TimedPacket timedPacket = this.packetsQueue.poll();
        if (timedPacket != null) {
            if (!this.packetsQueue.isEmpty()) {
                simulator.putEvent(this, processingTime(timedPacket.packet));
            }
            // ADR-0012 RM-01/RM-03: single instrumentation point; no-op unless link-load recording is enabled
            this.packetLeftQueue(timedPacket.packet, this.packetsQueue.isEmpty());
            this.sendPacketToNextProcess(timedPacket.packet);
        }
    }

    /**
     * Hook called when a packet leaves this processor's queue (before it is handed to the next stage). Default:
     * nothing. Overridden by the sending and receiving processes to feed the link-load counters (ADR-0012). Must
     * not schedule events, draw random numbers or call hashCode/identityHashCode (RM-08).
     *
     * @param packet       the packet that left the queue
     * @param queueEmptied true if the queue is empty after removing the packet
     */
    protected void packetLeftQueue(Packet packet, boolean queueEmptied) {
    }

    /**
     * Total size, in bytes, of the packets currently waiting in this processor's queue (read-only; used only by the
     * link-load recorder to measure the residual of the run, ADR-0012 VT-02).
     *
     * @return sum of {@code packet.getSize()} over the queue
     */
    public long queuedBytes() {
        long total = 0;
        for (TimedPacket tp : packetsQueue) {
            total += tp.packet().getSize();
        }
        return total;
    }

    public abstract double processingTime(Packet packet);
    protected abstract void sendPacketToNextProcess(Packet packet);

    public Node getNode() {
        return node;
    }
}
