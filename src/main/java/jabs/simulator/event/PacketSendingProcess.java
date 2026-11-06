package jabs.simulator.event;

import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.LinkLoadCounters;
import jabs.network.node.nodes.Node;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;

public class PacketSendingProcess extends AbstractPacketProcessor {
    public PacketSendingProcess(Simulator simulator, Network network, RandomnessEngine randomnessEngine, Node node) {
        super(simulator, network, randomnessEngine, node);
    }

    @Override
    protected void packetLeftQueue(Packet packet, boolean queueEmptied) {
        // RM-01: S_i counts the packet when its transmission on the uplink ends (it leaves the send queue)
        LinkLoadCounters counters = node.getNodeNetworkInterface().getLoadCounters();
        if (counters != null) {
            counters.packetSent(packet.getSize(), queueEmptied, simulator.getSimulationTime());
        }
    }

    protected void sendPacketToNextProcess(Packet packet) {
        PacketDeliveryEvent transfer = new PacketDeliveryEvent(packet);
        double latency = network.getLatency(packet.getFrom(), packet.getTo());
        simulator.putEvent(transfer, latency);
    }

    public double processingTime(Packet packet) {
        return ((packet.getSize()*8) / ((double) node.getNodeNetworkInterface().uploadBandwidth));
    }
}
