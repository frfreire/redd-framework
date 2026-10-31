package jabs.network.p2p;

import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Conexoes P2P de rede blockchain: cada no abre ate {@code numOutboundConnections} conexoes de saida
 * para nos sorteados da rede e aceita entradas ate a sua capacidade.
 *
 * <p><b>Rede pequena (ADR-0011, opcoes A+D).</b> Cada aresta e um par nao ordenado distinto (o par
 * inverso e barrado por {@code inbound}), entao uma rede com {@code n} nos so comporta
 * {@code n * d <= C(n,2)}, isto e, {@code n >= 2d + 1}. Antes da correcao, quando os candidatos
 * elegiveis acabavam o laco de {@link #connectToNetwork(Network)} girava para sempre. Agora:
 * <ul>
 *   <li>O alvo efetivo e {@code min(d, n - 1, candidatos elegiveis)}. Quando nao resta candidato
 *       elegivel (nem por ja estar ligado, nem por falta de capacidade de entrada do vizinho) o no
 *       <b>termina com menos de d saidas</b>. Para {@code n <= d + 1} e capacidade folgada o
 *       resultado e a malha completa, como no {@code PBFTP2P}.</li>
 *   <li>A deteccao de "sem candidato" e uma varredura em ordem de {@code getAllNodes()}, sem estado
 *       mutavel nem uso do {@code RandomnessEngine}. A sequencia de chamadas a {@code sampleFromList}
 *       e identica a do laco original enquanto houver candidato; so deixa de haver chamada onde o
 *       laco original nunca terminaria.</li>
 *   <li>Nenhuma estrutura de hash e usada para decidir ordem: a lista de vizinhos depende so da
 *       semente e da ordem de {@code getAllNodes()}.</li>
 * </ul>
 *
 * <p><b>Diferenca de topologia para n pequeno:</b> para n pequeno a rede deixa de ser "aleatoria de
 * grau d" e passa a ter grau {@code <= d} por saida (grau total ate d + entradas), e os nos
 * processados por ultimo tendem a ficar com menos saidas. A rede <b>pode ficar desconexa</b>: nada
 * na construcao garante conexidade (a baseline tambem nao garantia). Politica (ADR-0011, pergunta
 * 4): aceitar e registrar; a conexidade medida (busca em largura nao direcionada) entra no
 * relatorio, nao e imposta aqui.
 *
 * <p><b>Falha alta (opcao D):</b> {@link IllegalStateException} com n, d, candidatos elegiveis e no
 * apenas em estado inconsistente (no fora da rede ou vizinho que, apos ser apontado como elegivel,
 * recusa a conexao), que de outro modo viraria laco infinito.
 *
 * @author habib yajam
 */
public abstract class AbstractBlockchainP2PConnections extends AbstractP2PConnections {
    protected final int numOutboundConnections;
    private final int maxConnections;

    private final List<Node> outbound = new ArrayList<>();
    private final List<Node> inbound = new ArrayList<>();

    protected Network network;


    public AbstractBlockchainP2PConnections(int numOutboundConnections, int maxConnections) {
        this.numOutboundConnections = numOutboundConnections;
        this.maxConnections = maxConnections;
    }

    public void connectToNetwork(Network network){
        this.network = network;
        node.getNodeNetworkInterface().connectNetwork(network, network.getRandom());
        while (this.outbound.size() < this.numOutboundConnections) {
            List<Node> nodes = network.getAllNodes();
            // Sem candidato elegivel o laco original nunca terminaria; aqui encerra (opcao A).
            // A varredura nao consome o RandomnessEngine.
            if (!hasEligibleCandidate(nodes)) {
                break;
            }
            Node remoteNode = network.getRandom().sampleFromList(nodes);
            if (isEligible(remoteNode)) {
                if (remoteNode.getP2pConnections().requestConnection(this.getNode())) {
                    this.addOutbound(remoteNode);
                } else {
                    // isEligible() espelha requestConnection(); divergencia = estado inconsistente.
                    throw new IllegalStateException(describe(nodes,
                            "vizinho elegivel recusou a conexao " + remoteNode));
                }
            }
        }
    }

    public boolean requestConnection(Node remoteNode) {
        if (hasInboundCapacity()) {
            this.inbound.add(remoteNode);
            this.peerNeighbors.add(remoteNode);
            return true;
        } else {
            return false;
        }
    }

    public void addOutbound(Node remoteNode) {
        this.outbound.add(remoteNode);
        this.peerNeighbors.add(remoteNode);
    }

    /** Numero de conexoes de saida estabelecidas (pode ficar abaixo de d em rede pequena). */
    public int getOutboundCount() {
        return this.outbound.size();
    }

    /** Numero de conexoes de entrada aceitas. */
    public int getInboundCount() {
        return this.inbound.size();
    }

    /**
     * Espelha exatamente a regra de {@link #requestConnection(Node)} (inclui o "+1" historico:
     * aceita {@code max - d + 1} entradas). Sem efeito colateral.
     */
    boolean hasInboundCapacity() {
        return this.inbound.size() <= (this.maxConnections - this.numOutboundConnections);
    }

    /**
     * {@code remote} seria aceito agora por este no: nao e o proprio no, nao esta em outbound/inbound
     * e tem capacidade de entrada. Vizinhos que nao herdam desta classe (PBFTP2P, SnowP2P) recusam
     * sempre em {@code requestConnection}, portanto nao sao elegiveis. Sem RNG, sem efeito colateral.
     */
    private boolean isEligible(Node remote) {
        if (remote == this.getNode()
                || this.outbound.contains(remote)
                || this.inbound.contains(remote)) {
            return false;
        }
        return remote.getP2pConnections() instanceof AbstractBlockchainP2PConnections other
                && other.hasInboundCapacity();
    }

    /** Varredura em ordem de {@code getAllNodes()} (lista, sem hash), sai no primeiro elegivel. */
    private boolean hasEligibleCandidate(List<Node> nodes) {
        for (Node candidate : nodes) {
            if (isEligible(candidate)) {
                return true;
            }
        }
        return false;
    }

    private int countEligible(List<Node> nodes) {
        int count = 0;
        for (Node candidate : nodes) {
            if (isEligible(candidate)) {
                count++;
            }
        }
        return count;
    }

    private String describe(List<Node> nodes, String reason) {
        return "Setup P2P inconsistente: " + reason
                + " (n=" + nodes.size()
                + ", d=" + this.numOutboundConnections
                + ", saidas=" + this.outbound.size()
                + ", entradas=" + this.inbound.size()
                + ", elegiveis=" + countEligible(nodes)
                + ", no=" + (this.getNode() == null ? "null" : String.valueOf(this.getNode().nodeID))
                + ")";
    }
}
