package jabs.log;

import jabs.network.message.Message;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.p2p.AbstractP2PConnections;
import jabs.network.stats.lan.SingleNodeType;
import jabs.scenario.AbstractScenario;
import jabs.simulator.Simulator;
import jabs.consensus.config.ConsensusAlgorithmConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-built mini network for the link-load tests (ADR-0012 / M1a-1): n stub nodes, constant latency, explicit
 * bandwidths, traffic injected by scheduled events. No consensus, no randomness consumed by the network model
 * (latency is a constant), so every expected value in the tests is computed by hand from the link model:
 * transmission time of a packet = 8 * size / bandwidth.
 */
final class EnlaceRig {
    private EnlaceRig() { }

    /** Message with an explicit size. */
    static final class Msg extends Message {
        Msg(int size) { super(size); }
    }

    /** Stub node: records every packet delivered to {@code processIncomingPacket}, with the simulation time. */
    static final class No extends Node {
        final List<Packet> recebidos = new ArrayList<>();
        final List<Double> tempos = new ArrayList<>();

        @SuppressWarnings("rawtypes")
        No(Simulator sim, Network net, int id, long down, long up) {
            super(sim, net, id, down, up, new AbstractP2PConnections() {
                @Override public void connectToNetwork(Network network) { }
                @Override public boolean requestConnection(Node node) { return false; }
            });
        }

        @Override public void processIncomingPacket(Packet packet) {
            recebidos.add(packet);
            tempos.add(simulator.getSimulationTime());
        }
        @Override public void generateNewTransaction() { }
    }

    /** Network with a constant latency; node types are not used. */
    static final class Rede extends Network<No, SingleNodeType> {
        final double latencia;

        Rede(jabs.simulator.randengine.RandomnessEngine rng, double latencia) {
            super(rng, null);
            this.latencia = latencia;
        }

        @Override public double getLatency(No from, No to) { return latencia; }
        @Override public void populateNetwork(Simulator simulator, ConsensusAlgorithmConfig c) { throw new UnsupportedOperationException(); }
        @Override public void populateNetwork(Simulator simulator, int numNodes, ConsensusAlgorithmConfig c) { throw new UnsupportedOperationException(); }
        @Override public void addNode(No node) { nodes.add(node); }
    }

    /**
     * Burst of packets sent by one node: {@code tamanhos.length} packets, in that order. {@code passo} is the time
     * between consecutive packets of the burst: with {@code passo > 0} the queue order is FIFO (distinct entry
     * instants); with {@code passo == 0} all packets tie on entry time and the {@code PriorityQueue} of
     * {@code AbstractPacketProcessor} serves them in HEAP order, not FIFO (inherited behavior, documented by a test).
     */
    record Rajada(double tempo, int de, int para, double passo, int... tamanhos) { }

    /** Spacing used by default: distinct enough to give FIFO order, negligible against transmission times (>= 0.5 s). */
    static final double PASSO_FIFO = 1e-6;

    /** Scenario: runs until the event queue is empty, or until the time of the last executed event passes {@code parar}. */
    static final class Cena extends AbstractScenario {
        final int n;
        final long[] up;
        final long down;
        final double latencia;
        final double parar;
        final List<Rajada> rajadas = new ArrayList<>();
        final boolean[] caidoNoInicio;
        /** (tempo, no) to crash during the run. */
        final List<double[]> quedas = new ArrayList<>();
        /** Direct deliveries (bypass the queues): (tempo, no, tamanho). */
        final List<double[]> diretas = new ArrayList<>();
        No[] nos;

        Cena(int n, long up, long down, double latencia, double parar) {
            this(n, filled(n, up), down, latencia, parar);
        }

        Cena(int n, long[] up, long down, double latencia, double parar) {
            super("EnlaceRig", 1L);
            this.n = n;
            this.up = up;
            this.down = down;
            this.latencia = latencia;
            this.parar = parar;
            this.caidoNoInicio = new boolean[n];
        }

        private static long[] filled(int n, long v) {
            long[] a = new long[n];
            java.util.Arrays.fill(a, v);
            return a;
        }

        Cena rajada(double tempo, int de, int para, int... tamanhos) {
            rajadas.add(new Rajada(tempo, de, para, PASSO_FIFO, tamanhos));
            return this;
        }

        /** Burst with all packets entering the queue at the same instant (heap order, not FIFO). */
        Cena rajadaNoMesmoInstante(double tempo, int de, int para, int... tamanhos) {
            rajadas.add(new Rajada(tempo, de, para, 0.0, tamanhos));
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        protected void createNetwork() {
            Rede rede = new Rede(randomnessEngine, latencia);
            nos = new No[n];
            for (int i = 0; i < n; i++) {
                nos[i] = new No(simulator, rede, i, down, up[i]);
                rede.addNode(nos[i]);
                nos[i].getNodeNetworkInterface().connectNetwork(rede, randomnessEngine);
                if (caidoNoInicio[i]) {
                    nos[i].crash();
                }
            }
            this.network = rede;
        }

        @Override
        protected void insertInitialEvents() {
            for (Rajada r : rajadas) {
                for (int k = 0; k < r.tamanhos().length; k++) {
                    final int tam = r.tamanhos()[k];
                    simulator.putEvent(() -> nos[r.de()].getNodeNetworkInterface()
                            .addToUpLinkQueue(new Packet(nos[r.de()], nos[r.para()], new Msg(tam))),
                            r.tempo() + k * r.passo());
                }
            }
            for (double[] q : quedas) {
                simulator.putEvent(() -> nos[(int) q[1]].crash(), q[0]);
            }
            for (double[] d : diretas) {
                simulator.putEvent(() -> nos[(int) d[1]].processIncomingPacket(
                        new Packet(nos[(int) d[1]], nos[(int) d[1]], new Msg((int) d[2]))), d[0]);
            }
        }

        @Override
        protected boolean simulationStopCondition() {
            return simulator.getSimulationTime() > parar;
        }
    }
}
