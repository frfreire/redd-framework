package jabs.network.networks.tendermint;

import jabs.consensus.algorithm.Tendermint;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.Block;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.ledgerdata.tendermint.TendermintTx;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Caminho honesto do adaptador Tendermint com rede: todos decidem a mesma cadeia e medem trafego. */
class TendermintLocalLANNetworkTest {
    private static final int N = 4;

    private record Fin(int nodeId, int height, long total, long consensus, Block block, double time) { }

    private static List<Fin> rodar(long seed, int n, double stop) {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(sim, n, TendermintTestConfig.config());
        net.startConsensusRound(sim, stop);

        List<Fin> fins = new ArrayList<>();
        while (sim.isThereMoreEvents() && sim.peekEventTime() <= stop) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                fins.add(new Fin(fe.getNode().nodeID, fe.getBlock().getHeight(),
                        fe.getTrafficUntilFinalization(), fe.getConsensusMessageTraffic(),
                        fe.getBlock(), sim.getSimulationTime()));
            }
        }
        return fins;
    }

    private static Tendermint<TendermintBlock, TendermintTx> alg(TendermintNode node) {
        @SuppressWarnings("unchecked")
        Tendermint<TendermintBlock, TendermintTx> t =
                (Tendermint<TendermintBlock, TendermintTx>) node.getConsensusAlgorithm();
        return t;
    }

    @Test
    void malhaCompletaSemAutoVizinhanca() {
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(1));
        net.populateNetwork(new Simulator(), N, TendermintTestConfig.config());
        for (TendermintNode node : net.getAllNodes()) {
            assertEquals(N - 1, node.getP2pConnections().getNeighbors().size());
            assertFalse(node.getP2pConnections().getNeighbors().contains(node));
        }
    }

    @Test
    void nMenorQue4FalhaAlto() {
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(1));
        assertThrows(IllegalArgumentException.class,
                () -> net.populateNetwork(new Simulator(), 3, TendermintTestConfig.config()));
        assertTrue(net.getAllNodes().isEmpty());
    }

    @Test
    void todosDecidemAMesmaCadeiaECompatilhamOsBlocos() {
        List<Fin> fins = rodar(7, N, 20.0);
        assertFalse(fins.isEmpty(), "deve haver finalizacoes");

        Map<Integer, List<Fin>> porAltura = new HashMap<>();
        for (Fin f : fins) {
            porAltura.computeIfAbsent(f.height(), k -> new ArrayList<>()).add(f);
        }
        // A altura 0 do nucleo propoe o primeiro bloco (altura 1 no JABS, filho do genesis).
        assertTrue(porAltura.containsKey(1));
        assertTrue(porAltura.containsKey(2));
        assertTrue(porAltura.containsKey(3));

        for (Map.Entry<Integer, List<Fin>> e : porAltura.entrySet()) {
            List<Fin> noAltura = e.getValue();
            assertEquals(N, noAltura.size(), "todos os nos finalizam a altura " + e.getKey());
            Block primeiro = noAltura.get(0).block();
            for (Fin f : noAltura) {
                assertSame(primeiro, f.block(), "mesmo bloco decidido na altura " + e.getKey());
            }
        }
    }

    @Test
    void trafegoMedidoPorNoEDecomposto() {
        List<Fin> fins = rodar(7, N, 20.0);
        assertFalse(fins.isEmpty());
        boolean algumComCargaDeBloco = false;
        for (Fin f : fins) {
            assertTrue(f.total() > 0, "C_B_total medido");
            assertTrue(f.consensus() >= 0, "C_B_consenso medido (>= 0, nao sentinela)");
            assertTrue(f.consensus() <= f.total(), "consenso nunca excede o total");
            if (f.consensus() < f.total()) {
                algumComCargaDeBloco = true;
            }
        }
        // Quem propoe so recebe votos (consenso == total); quem recebe a proposta paga a carga do bloco no total.
        assertTrue(algumComCargaDeBloco, "ao menos um no recebeu proposta e teve carga de bloco em C_B_total");
    }

    @Test
    void headsCanonicosConvergemAoFinal() {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, N, TendermintTestConfig.config());
        net.startConsensusRound(sim, 20.0);
        while (sim.isThereMoreEvents() && sim.peekEventTime() <= 20.0) {
            sim.executeNextEvent();
        }
        TendermintBlock head = net.getNode(0).getConsensusAlgorithm().getCanonicalChainHead();
        for (TendermintNode node : net.getAllNodes()) {
            assertSame(head, node.getConsensusAlgorithm().getCanonicalChainHead());
            // Bloco de altura k e decidido na altura k-1 do nucleo; apos decidir k alturas, height() == k.
            assertEquals(head.getHeight(), alg(node).getHeight());
        }
    }

    @Test
    void configObrigatoria() {
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(1));
        TendermintConsensusConfig config = TendermintTestConfig.config();
        assertThrows(UnsupportedOperationException.class, () -> net.populateNetwork(new Simulator(), config));
        assertThrows(IllegalArgumentException.class, () -> net.populateNetwork(new Simulator(), N, null));
        assertThrows(IllegalArgumentException.class, () -> net.populateNetwork(new Simulator(), N,
                new jabs.consensus.config.PBFTConsensusConfig()));
    }
}
