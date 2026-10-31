package jabs.network.p2p;

import jabs.consensus.config.ConsensusAlgorithmConfig;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.NodeType;
import jabs.network.node.nodes.Node;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Rede minima so para exercitar a camada P2P (ADR-0011): nos stub com o P2P real, sem consenso, sem
 * eventos. Nao e usada fora de teste. A ordem de conexao e a de {@code getAllNodes()}, como em
 * {@code GlobalProofOfWorkNetwork.populateNetwork}.
 */
final class RedeP2PDeTeste extends Network<Node, NodeType> {

    static final class NoStub extends Node {
        NoStub(int id, AbstractP2PConnections p2p) { super(new Simulator(), null, id, 1, 1, p2p); }
        @Override public void processIncomingPacket(Packet packet) { }
        @Override public void generateNewTransaction() { }
    }

    /** Fabrica de P2P por familia (grau e capacidade reais das classes de producao). */
    static final Supplier<AbstractP2PConnections> GETH = EthereumGethP2P::new;
    static final Supplier<AbstractP2PConnections> BITCOIN = BitcoinCoreP2P::new;

    private RedeP2PDeTeste(RandomnessEngine rng) { super(rng, null); }

    /** Cria {@code n} nos e conecta um a um (pode lancar ou nao terminar: quem chama controla). */
    static RedeP2PDeTeste criar(Supplier<AbstractP2PConnections> familia, int n, long semente) {
        RedeP2PDeTeste rede = new RedeP2PDeTeste(new RandomnessEngine(semente));
        for (int i = 0; i < n; i++) {
            rede.addNode(new NoStub(i, familia.get()));
        }
        return rede;
    }

    void conectarTodos() {
        for (Node node : getAllNodes()) {
            node.getP2pConnections().connectToNetwork(this);
        }
    }

    /** Ids dos vizinhos de cada no, na ordem de {@code getNeighbors()}. */
    List<List<Integer>> vizinhos() {
        List<List<Integer>> out = new ArrayList<>();
        for (Node node : getAllNodes()) {
            List<Integer> ids = new ArrayList<>();
            for (Node v : node.getP2pConnections().getNeighbors()) ids.add(v.nodeID);
            out.add(ids);
        }
        return out;
    }

    /** Numero de componentes conexas (BFS, grafo nao direcionado). 1 = rede conexa. */
    int componentes() {
        int n = getAllNodes().size();
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        List<List<Integer>> viz = vizinhos();
        for (int i = 0; i < n; i++) {
            for (int j : viz.get(i)) {
                adj.get(i).add(j);
                adj.get(j).add(i);
            }
        }
        boolean[] visto = new boolean[n];
        int comps = 0;
        for (int s = 0; s < n; s++) {
            if (visto[s]) continue;
            comps++;
            ArrayDeque<Integer> fila = new ArrayDeque<>();
            fila.add(s);
            visto[s] = true;
            while (!fila.isEmpty()) {
                int u = fila.poll();
                for (int v : adj.get(u)) {
                    if (!visto[v]) { visto[v] = true; fila.add(v); }
                }
            }
        }
        return comps;
    }

    /** Grau de saida de cada no (so classes de producao). */
    int saidas(int i) {
        return ((AbstractBlockchainP2PConnections) getNode(i).getP2pConnections()).getOutboundCount();
    }

    // Network: nada disto e usado pelos testes.
    @Override public void populateNetwork(Simulator simulator, ConsensusAlgorithmConfig c) { throw new UnsupportedOperationException(); }
    @Override public void populateNetwork(Simulator simulator, int numNodes, ConsensusAlgorithmConfig c) { throw new UnsupportedOperationException(); }
    @Override public void addNode(Node node) { addNode(node, NodeType.VALIDATOR); }
}
