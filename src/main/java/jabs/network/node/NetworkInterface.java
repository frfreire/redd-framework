package jabs.network.node;

import jabs.simulator.event.AbstractPacketProcessor;
import jabs.simulator.event.PacketReceivingProcess;
import jabs.simulator.event.PacketSendingProcess;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;

public class NetworkInterface {
    /**
     * Node's total download bandwidth
     */
    public final long downloadBandwidth;

    /**
     * Node's total upload bandwidth
     */
    public final long uploadBandwidth;

    /**
     * The node that have this network interface
     */
    public final Node node;

    /**
     * The simulator which handles events for this network interface
     */
    public final Simulator simulator;

    /**
     * The process (which is a type of ongoing event) that handles receiving packets and their delays due to bandwidth
     */
    public PacketReceivingProcess messageReceivingProcess;

    /**
     * The process (which is a type of ongoing event) that handles sending packets and their delays due to bandwidth
     */
    public PacketSendingProcess messageSendingProcess;

    /**
     * State of network interface (false->Up / true->Down)
     */
    public boolean networkInterfaceDown;

    /**
     * Link-load counters (ADR-0012). {@code null} = recording off (default): no behavior change.
     */
    private LinkLoadCounters loadCounters;

    /**
     * Stops the node from sending or receiving packets
     */
    public void takeDown() {
        this.networkInterfaceDown = true;
    }

    /**
     * Restores the node network interface to a working state (node can send and receive packets again)
     */
    public void bringUp() {
        this.networkInterfaceDown = false;
    }

    /**
     * return the state of network interface
     * @return true if the network interface is down and not working
     */
    public boolean isNetworkInterfaceDown() {
        return networkInterfaceDown;
    }

    /**
     * Creates a new network interface
     * @param simulator The simulator which simulates the events of this network interface
     * @param node The node that possesses this network interface
     * @param downloadBandwidth The total down-link bandwidth of this node
     * @param uploadBandwidth The total up-link bandwidth of this node
     */
    public NetworkInterface(Simulator simulator, Node node, long downloadBandwidth, long uploadBandwidth) {
        this.simulator = simulator;
        this.downloadBandwidth = downloadBandwidth;
        this.uploadBandwidth = uploadBandwidth;
        this.node = node;
        this.networkInterfaceDown = false;
    }

    /**
     * Creates two processes (ongoing events) that handle receiving and sending packets and their respective latencies
     * @param network Network in which this network interface operates
     * @param randomnessEngine Randomness source for simulating randomness in delays
     */
    public void connectNetwork(Network network, RandomnessEngine randomnessEngine) {
        this.messageReceivingProcess = new PacketReceivingProcess(simulator, network, randomnessEngine, node);
        this.messageSendingProcess = new PacketSendingProcess(simulator, network, randomnessEngine, node);
    }

    /**
     * Adds the packet to node's receiving process if the network interface is not down
     * @param packet the packet to be received
     */
    public void addToDownLinkQueue(Packet packet) {
        if (this.loadCounters != null) {
            // ADR-0012 RM-01/VT-02: every packet reaching the downlink entry point, up or down interface
            this.loadCounters.packetArrived(packet.getSize(), this.networkInterfaceDown);
        }
        if (!this.networkInterfaceDown) {
            this.addToLinkQueue(packet, this.messageReceivingProcess);
        }
    }

    /**
     * Adds the packet to node's sending process if the network interface is not down
     * @param packet the packet to be sent
     */
    public void addToUpLinkQueue(Packet packet) {
        if (!this.networkInterfaceDown) {
            if (this.loadCounters != null && this.messageSendingProcess.isQueueEmpty()) {
                // ADR-0012 RM-03: a busy interval opens when a packet enters an EMPTY send queue
                this.loadCounters.upLinkBecameBusy(this.simulator.getSimulationTime());
            }
            this.addToLinkQueue(packet, this.messageSendingProcess);
        } else if (this.loadCounters != null) {
            this.loadCounters.senderDownDropped(packet.getSize());
        }
    }

    /**
     * Turns on the per-node link-load counters of ADR-0012 (bytes sent/received, send-queue busy time). Off by
     * default; with it off the interface behaves exactly as before. Call before the first packet is exchanged
     * (a packet already waiting in the send queue opens a busy interval at the current simulation time).
     *
     * @return the counters of this interface (the same object if already enabled)
     */
    public LinkLoadCounters enableLoadRecording() {
        if (this.loadCounters == null) {
            this.loadCounters = new LinkLoadCounters();
            if (this.messageSendingProcess != null && !this.messageSendingProcess.isQueueEmpty()) {
                this.loadCounters.upLinkBecameBusy(this.simulator.getSimulationTime());
            }
        }
        return this.loadCounters;
    }

    /**
     * @return the link-load counters, or {@code null} when recording is off (default)
     */
    public LinkLoadCounters getLoadCounters() {
        return this.loadCounters;
    }

    /**
     * Bytes waiting in the receiving queue (read-only; used by the link-load recorder for the end-of-run residual).
     * @return sum of packet sizes queued for reception, 0 if there is no receiving process
     */
    public long receiveQueueBytes() {
        return (this.messageReceivingProcess == null) ? 0L : this.messageReceivingProcess.queuedBytes();
    }

    /**
     * Adds the packet to the queue of that link (up-link / down-link)
     * @param packet the packet
     * @param processor the up-link/down-link process
     */
    private void addToLinkQueue(Packet packet, AbstractPacketProcessor processor) {
        if (processor.isQueueEmpty()) {
            simulator.putEvent(processor, processor.processingTime(packet));
        }
        processor.addToQueue(packet);
    }
}
