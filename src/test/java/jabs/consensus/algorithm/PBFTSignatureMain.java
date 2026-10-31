package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;

import java.util.Set;

/**
 * Utilitario de nao-regressao (nao e teste JUnit; nome sem sufixo Test): imprime uma assinatura (hash) dos
 * BlockFinalizationEvent do PBFT por semente, n e ataque. Rodar antes e depois de mexer em PBFT.java e
 * comparar as linhas. Uso: java -cp target/test-classes:target/classes:$(cat target/cp.txt)
 * jabs.consensus.algorithm.PBFTSignatureMain
 */
public class PBFTSignatureMain {
    static String run(long seed, int n, String attack, Set<Integer> byz) {
        RandomnessEngine rng = new RandomnessEngine(seed);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig());
        if (attack != null) {
            ByzantineConfig cfg = new ByzantineConfig(n, byz, attack, seed);
            for (int id : byz) {
                net.getAllNodes().get(id).getConsensusAlgorithm().setByzantineConfig(cfg);
            }
        }
        net.startConsensusRound(sim, 60.0);
        long h = 7;
        int events = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 60.0) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                events++;
                h = h * 31 + fe.getNode().nodeID;
                h = h * 31 + Double.doubleToLongBits(sim.getSimulationTime());
                h = h * 31 + fe.getTrafficUntilFinalization();
                h = h * 31 + fe.getConsensusMessageTraffic();
            }
        }
        return "events=" + events + " hash=" + h;
    }

    public static void main(String[] args) {
        for (long seed : new long[]{1, 42}) {
            for (int n : new int[]{4, 7, 10, 13}) {
                System.out.println("none seed=" + seed + " n=" + n + " " + run(seed, n, null, Set.of()));
                Set<Integer> byz = n >= 10 ? Set.of(1, 4, 7) : Set.of(1, 2);
                for (String at : new String[]{"EQUIVOCATION", "DOUBLE_SIGN", "WITHHOLD", "SILENT"}) {
                    String out;
                    try {
                        out = run(seed, n, at, byz);
                    } catch (RuntimeException t) {
                        out = "THREW " + t;
                    }
                    System.out.println(at + " seed=" + seed + " n=" + n + " " + out);
                }
            }
        }
    }
}
