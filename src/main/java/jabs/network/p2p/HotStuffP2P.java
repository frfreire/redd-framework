package jabs.network.p2p;

import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;

/**
 * P2P do HotStuff: MALHA COMPLETA. Cada no tem como vizinhos todos os OUTROS nos da rede (n-1), em ordem de
 * {@code getAllNodes()}.
 *
 * <p><b>Escolha registrada (D3a-2a-1):</b> o HotStuff basico (Yin et al., PODC 2019) usa canais
 * todos-para-lider (replica -> lider nos votos; lider -> replicas na proposta e nos QCs), nao
 * todos-para-todos. A rede, porem, e totalmente conectada: qualquer no alcanca qualquer outro, e o lider muda a
 * cada visao (politica ROTATING) sem reconfigurar a topologia. A TOPOLOGIA DE MENSAGENS vem do algoritmo
 * (D3a-2a-2), que envia pacote dirigido ao lider ou aos seguidores.
 *
 * <p><b>Diferenca deliberada em relacao ao {@link PBFTP2P}:</b> o PBFTP2P inclui o proprio no entre os
 * vizinhos, de modo que no PBFT o broadcast percorre a rede tambem ate o remetente e {@code from == this} pode
 * ser trafego de rede. Aqui o no NAO e vizinho de si (como no EthereumGethP2P/DAGsper). Razao: a convencao de
 * trafego deste projeto (ADR-0006: so conta pacote que passou pela rede simulada; autoentrega local nao conta)
 * exige distinguir os dois casos sem ambiguidade. Com este P2P, {@code from == this} so ocorre em entrega local
 * direta (chamada a {@code processIncomingPacket} sem passar pela fila de envio), e o hook em
 * PeerBlockchainNode a exclui. Consequencia para o algoritmo: o voto do proprio lider e a propria proposta sao
 * entregues localmente (nao custam banda nem latencia e nao entram em C_B); {@code broadcastMessage} alcanca
 * os n-1 outros.
 *
 * <p>Nao ha sorteio de vizinhos: a construcao nao consome aleatoriedade e o setup termina para qualquer n, sem
 * o problema de laco do ADR-0011 (que afeta os P2P de grau fixo).
 */
public class HotStuffP2P extends AbstractP2PConnections {
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
