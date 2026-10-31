package jabs.consensus.algorithm;

import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.simulator.Simulator;
import jabs.simulator.event.PacketSendingProcess;
import jabs.simulator.randengine.RandomnessEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Processo de envio de teste: grava todo pacote que o no enfileira na saida (uplink) e lanca
 * {@link FalhaInjetada} quando {@link #falharQuando} aceita o pacote. Substitui {@code messageSendingProcess} do no
 * (campo publico), de modo que os testes observam o que o hook bizantino ENFILEIRA sem rodar a simulacao.
 */
public final class EnvioDeTeste extends PacketSendingProcess {
    public final List<Packet> enviados = new ArrayList<>();
    public Predicate<Packet> falharQuando = p -> false;

    @SuppressWarnings("rawtypes")
    public EnvioDeTeste(Simulator simulator, Network network, RandomnessEngine rng, Node node) {
        super(simulator, network, rng, node);
    }

    @Override
    public void addToQueue(Packet packet) {
        if (falharQuando.test(packet)) {
            throw new FalhaInjetada("envio de pacote de " + getNode().nodeID);
        }
        enviados.add(packet);
        super.addToQueue(packet);
    }

    /** Pacotes enfileirados cuja mensagem e do tipo dado. */
    public <M> List<Packet> doTipo(Class<M> tipo) {
        List<Packet> out = new ArrayList<>();
        for (Packet p : enviados) {
            if (tipo.isInstance(p.getMessage())) out.add(p);
        }
        return out;
    }

    /** Instala um {@link EnvioDeTeste} como processo de envio do no e o devolve. */
    @SuppressWarnings("rawtypes")
    public static EnvioDeTeste instala(Node no, Simulator simulator, Network network, RandomnessEngine rng) {
        EnvioDeTeste envio = new EnvioDeTeste(simulator, network, rng, no);
        no.getNodeNetworkInterface().messageSendingProcess = envio;
        return envio;
    }
}
