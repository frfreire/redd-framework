package jabs.network.p2p;

import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;

/**
 * P2P do Tendermint: MALHA COMPLETA. Cada no tem como vizinhos todos os OUTROS nos da rede (n-1).
 *
 * <p>O Algoritmo 1 supõe que toda mensagem recebida por um no correto acaba recebida por todos os corretos; a
 * difusao direta do simulador (sem perda, exceto interface derrubada) satisfaz essa suposicao enquanto todos os
 * remetentes sao corretos. O no NAO e vizinho de si: o nucleo ja registra a propria proposta/voto no log, e a
 * autoentrega nao e trafego de rede (convencao ADR-0006).
 */
public class TendermintP2P extends AbstractP2PConnections {
    @Override
    public void connectToNetwork(Network network) {
        for (Object o : network.getAllNodes()) {
            Node other = (Node) o;
            if (other != node) {
                this.peerNeighbors.add(other);
            }
        }
        node.getNodeNetworkInterface().connectNetwork(network, network.getRandom());
    }

    @Override
    public boolean requestConnection(Node node) {
        return false;
    }
}
