package jabs.simulator.event;

import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.LinkLoadCounters;
import jabs.network.node.nodes.Node;
import jabs.simulator.randengine.RandomnessEngine;
import jabs.simulator.Simulator;

public class PacketReceivingProcess extends AbstractPacketProcessor {
    public PacketReceivingProcess(Simulator simulator, Network network, RandomnessEngine randomnessEngine, Node node) {
        super(simulator, network, randomnessEngine, node);
    }

    @Override
    protected void packetLeftQueue(Packet packet, boolean queueEmptied) {
        // RM-01 / VT-02: RecvBytes counted where the receiving process delivers the packet to the node
        LinkLoadCounters counters = node.getNodeNetworkInterface().getLoadCounters();
        if (counters != null) {
            counters.packetDelivered(packet.getSize());
        }
    }

    protected void sendPacketToNextProcess(Packet packet) {
        this.node.processIncomingPacket(packet);
    }

    public double processingTime(Packet packet) {
        return ((packet.getSize()*8) / (((double) node.getNodeNetworkInterface().downloadBandwidth)));
    }
}

