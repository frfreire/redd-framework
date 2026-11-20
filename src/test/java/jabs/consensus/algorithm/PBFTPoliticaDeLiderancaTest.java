package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.consensus.config.PBFTConsensusConfig;
import jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.scenario.PBFTLANScenario;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Politica de lideranca do PBFT (PI-14, ADR-0010): {@code STABLE} (modo do estudo: o no 0 propoe toda altura) e
 * {@code ROTATING} (ADR-0007 opcao 2, T6). Parametro obrigatorio e sem padrao.
 */
class PBFTPoliticaDeLiderancaTest {
    @SuppressWarnings("unchecked")
    private static PBFT<PBFTBlock, ?> pbft(PBFTNode n) {
        return (PBFT<PBFTBlock, ?>) n.getConsensusAlgorithm();
    }

    @Test
    void politicaEObrigatoriaESemPadrao() {
        assertThrows(IllegalArgumentException.class, () -> new PBFTConsensusConfig(1000L, null));
        assertThrows(IllegalArgumentException.class, () -> LeaderPolicy.fromFlag(null));
        assertThrows(IllegalArgumentException.class, () -> LeaderPolicy.fromFlag("stable"), "sem maiusculas/minusculas tolerantes");
        assertThrows(IllegalArgumentException.class, () -> LeaderPolicy.fromFlag(""));
        assertEquals(LeaderPolicy.STABLE, LeaderPolicy.fromFlag("STABLE"));
        assertEquals(LeaderPolicy.ROTATING, LeaderPolicy.fromFlag("ROTATING"));
        PBFTLANScenario sc = new PBFTLANScenario("sem-politica", 1L, 4, 5.0);
        sc.setMaxFutureHorizon(1000L);
        IllegalStateException e = assertThrows(IllegalStateException.class, sc::run);
        assertTrue(e.getMessage().contains("leaderPolicy"), e.getMessage());
    }

    @Test
    void estavelTemSempreOMesmoPrimarioERotativoRotaciona() {
        for (LeaderPolicy lp : LeaderPolicy.values()) {
            PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(new RandomnessEngine(5));
            net.populateNetwork(new Simulator(), 4, new PBFTConsensusConfig(1000L, lp));
            PBFT<PBFTBlock, ?> p = pbft(net.getAllNodes().get(2));
            assertEquals(lp, p.getLeaderPolicy());
            for (long h = 1; h <= 9; h++) {
                int esperado = lp == LeaderPolicy.STABLE ? PBFTConsensusConfig.STABLE_LEADER_ID : (int) ((h - 1) % 4);
                assertEquals(esperado, p.primaryForHeight(h), lp + " altura " + h);
            }
        }
    }

    @Test
    void estavelTodoBlocoVemDoLiderEUmaDecisaoPorAltura() throws Exception {
        Field committed = PBFT.class.getDeclaredField("committedBlocks");
        committed.setAccessible(true);
        PBFTLANScenario sc = new PBFTLANScenario("estavel", 1L, 7, 30.0);
        sc.setMaxFutureHorizon(1000L);
        sc.setLeaderPolicy(LeaderPolicy.STABLE);
        sc.run();
        for (PBFTNode nd : sc.getNetwork().getAllNodes()) {
            PBFT<PBFTBlock, ?> p = pbft(nd);
            assertEquals(0L, p.getRejectedPrePrepareNotPrimary(), "ninguem propoe fora do turno");
            Set<?> commits = (Set<?>) committed.get(p);
            assertTrue(commits.size() >= 10, "no " + nd.nodeID + ": rodou alturas: " + commits.size());
            java.util.Set<Integer> alturas = new java.util.HashSet<>();
            for (Object o : commits) {
                PBFTBlock b = (PBFTBlock) o;
                assertEquals(PBFTConsensusConfig.STABLE_LEADER_ID, b.getCreator().nodeID, "criador = o lider estavel");
                assertTrue(alturas.add(b.getHeight()), "uma decisao por altura");
            }
        }
    }

    /** O caso do E2: lider estavel HONESTO e validadores que equivocam: o PBFT segue vivo (f <= (n-1)/3). */
    @Test
    void estavelComLiderHonestoSegueVivoSobEquivocationDosDemais() {
        int n = 7;
        Set<Integer> bizantinos = Set.of(3, 5); // f = 2 = (n-1)/3; o lider (no 0) e honesto
        RandomnessEngine rng = new RandomnessEngine(1);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new PBFTConsensusConfig(1000L, LeaderPolicy.STABLE));
        ByzantineConfig cfg = new ByzantineConfig(n, bizantinos, "EQUIVOCATION", 1);
        for (int id : bizantinos) net.getAllNodes().get(id).getConsensusAlgorithm().setByzantineConfig(cfg);
        net.startConsensusRound(sim, 30.0);
        int finalizacoes = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 30.0) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent) finalizacoes++;
        }
        assertTrue(finalizacoes > 50, "lider honesto + f=2: segue vivo (T6 rotativo travava): " + finalizacoes);
    }

    /** O custo declarado do modo estavel sem view change: lider que equivoca trava a cadeia. */
    @Test
    void estavelComLiderQueEquivocaTravaACadeia() {
        int n = 7;
        Set<Integer> bizantinos = Set.of(0, 5); // o lider (no 0) e bizantino
        RandomnessEngine rng = new RandomnessEngine(1);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new PBFTConsensusConfig(1000L, LeaderPolicy.STABLE));
        ByzantineConfig cfg = new ByzantineConfig(n, bizantinos, "EQUIVOCATION", 1);
        for (int id : bizantinos) net.getAllNodes().get(id).getConsensusAlgorithm().setByzantineConfig(cfg);
        net.startConsensusRound(sim, 30.0);
        int finalizacoes = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 30.0) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent) finalizacoes++;
        }
        assertTrue(finalizacoes <= 1, "sem view change, o lider bizantino que equivoca nao deixa decidir: " + finalizacoes);
    }
}
