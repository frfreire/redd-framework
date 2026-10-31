package jabs.network.networks.hotstuff;

import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D3a-2a-1: o no e a rede do HotStuff constroem; malha completa; determinismo da construcao por semente. */
class HotStuffLocalLANNetworkTest {

    /** Valores de teste, nao de pesquisa (mesmos do HotStuffConsensusConfigTest). */
    static HotStuffConsensusConfig config() {
        return HotStuffTestConfig.config();
    }

    static HotStuffLocalLANNetwork rede(long seed, int n) {
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(new Simulator(), n, config());
        return net;
    }

    @Test
    void noEReDeConstroem() {
        HotStuffLocalLANNetwork net = rede(1, 4);
        assertEquals(4, net.getAllNodes().size());
        for (int i = 0; i < 4; i++) {
            HotStuffNode no = net.getAllNodes().get(i);
            assertEquals(i, no.getNodeID());
            assertEquals(4, no.getNumAllParticipants());
            HotStuff<?, ?> consenso = (HotStuff<?, ?>) no.getConsensusAlgorithm();
            assertEquals(3, consenso.getRequiredVotes());
            assertSame(no.getConfig(), consenso.getConfig());
            assertSame(HotStuffNode.HOTSTUFF_GENESIS_BLOCK, no.getConsensusAlgorithm().getCanonicalChainHead());
        }
    }

    @Test
    void tresNosNaoSaoAceitosPeloProtocolo() {
        // n=4 pode ser 3 nos (decisao do humano) no sentido de f=1 com um no bizantino silencioso; o protocolo
        // em si continua exigindo n >= 4 (3f+1): a construcao com 3 nos falha alto.
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(1));
        assertThrows(IllegalArgumentException.class, () -> net.populateNetwork(new Simulator(), 3, config()));
        assertTrue(net.getAllNodes().isEmpty(), "nenhum no pode ter sido criado antes da validacao");
    }

    @Test
    void configInvalidaFalhaAlto() {
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(1));
        assertThrows(IllegalArgumentException.class, () -> net.populateNetwork(new Simulator(), 4, null));
        assertThrows(IllegalArgumentException.class, () -> net.populateNetwork(new Simulator(), 4,
                new jabs.consensus.config.PBFTConsensusConfig()));
        assertThrows(UnsupportedOperationException.class, () -> net.populateNetwork(new Simulator(), config()));
    }

    @Test
    void agendadorDePropostasFalhaAltoAteD3a2a2() {
        HotStuffLocalLANNetwork net = rede(1, 4);
        assertThrows(UnsupportedOperationException.class, () -> net.startConsensusRound(new Simulator(), 10.0));
    }

    @Test
    void malhaCompletaParaN4N7N10N16() {
        for (int n : new int[]{4, 7, 10, 16}) {
            HotStuffLocalLANNetwork net = rede(1, n);
            assertEquals(n, net.getAllNodes().size());
            for (HotStuffNode no : net.getAllNodes()) {
                List<Node> viz = no.getP2pConnections().getNeighbors();
                assertEquals(n - 1, viz.size(), "grau n-1 em n=" + n);
                assertFalse(viz.contains(no), "o no nao e vizinho de si (autoentrega nao e rede)");
                for (HotStuffNode outro : net.getAllNodes()) {
                    if (outro != no) {
                        assertEquals(1, viz.stream().filter(v -> v == outro).count(),
                                "no " + no.getNodeID() + " liga a " + outro.getNodeID() + " exatamente uma vez (n=" + n + ")");
                    }
                }
            }
            // simetria: a-b <=> b-a
            for (HotStuffNode a : net.getAllNodes()) {
                for (Node b : a.getP2pConnections().getNeighbors()) {
                    assertTrue(b.getP2pConnections().getNeighbors().contains(a), "simetria em n=" + n);
                }
            }
            // total de arestas dirigidas = n(n-1)
            int total = net.getAllNodes().stream().mapToInt(x -> x.getP2pConnections().getNeighbors().size()).sum();
            assertEquals(n * (n - 1), total);
        }
    }

    @Test
    void vizinhosEmOrdemDeId() {
        HotStuffLocalLANNetwork net = rede(1, 7);
        for (HotStuffNode no : net.getAllNodes()) {
            List<Integer> ids = new ArrayList<>();
            for (Node v : no.getP2pConnections().getNeighbors()) {
                ids.add(v.getNodeID());
            }
            List<Integer> esperado = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                if (i != no.getNodeID()) {
                    esperado.add(i);
                }
            }
            assertEquals(esperado, ids);
        }
    }

    @Test
    void construcaoDeterministicaPorSemente() {
        for (long seed : new long[]{1, 2, 42}) {
            RandomnessEngine a = new RandomnessEngine(seed);
            RandomnessEngine b = new RandomnessEngine(seed);
            HotStuffLocalLANNetwork na = new HotStuffLocalLANNetwork(a);
            HotStuffLocalLANNetwork nb = new HotStuffLocalLANNetwork(b);
            na.populateNetwork(new Simulator(), 10, config());
            nb.populateNetwork(new Simulator(), 10, config());
            for (int i = 0; i < 10; i++) {
                List<Integer> ia = ids(na.getAllNodes().get(i));
                List<Integer> ib = ids(nb.getAllNodes().get(i));
                assertEquals(ia, ib, "vizinhos do no " + i + " (semente " + seed + ")");
                assertNotSame(na.getAllNodes().get(i), nb.getAllNodes().get(i));
            }
            // A construcao nao consome aleatoriedade: o proximo sorteio e o mesmo de um motor virgem da semente.
            assertEquals(new RandomnessEngine(seed).nextLong(), a.nextLong());
            assertEquals(new RandomnessEngine(seed).nextLong(), b.nextLong());
        }
    }

    private static List<Integer> ids(HotStuffNode no) {
        List<Integer> r = new ArrayList<>();
        for (Node v : no.getP2pConnections().getNeighbors()) {
            r.add(v.getNodeID());
        }
        return r;
    }
}
