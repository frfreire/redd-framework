package jabs.consensus.algorithm;

import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.metrics.NetworkHeightLedger;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.scenario.PBFTLANScenario;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6 / ADR-0007 (opcao 2): view = altura decidida, primario = {@code (altura decidida) % n} (sem view change), PRE_PREPARE so do
 * primario da altura do bloco, UMA decisao por altura e sem a proposta periodica do genesis. Antes: ~5 a 10 blocos decididos por
 * altura (cadeias concorrentes), 7 a 10 vezes o trafego do PBFT de livro por altura.
 */
class PBFTPrimarioPorAlturaTest {
    @SuppressWarnings("unchecked")
    private static PBFT<PBFTBlock, ?> pbft(PBFTNode n) {
        return (PBFT<PBFTBlock, ?>) n.getConsensusAlgorithm();
    }

    @Test
    void primarioPorAlturaERotacionaDe0() {
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(new RandomnessEngine(5));
        net.populateNetwork(new Simulator(), 4, new PBFTConsensusConfig(1000L, jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy.ROTATING));
        PBFT<PBFTBlock, ?> p = pbft(net.getAllNodes().get(0));
        assertEquals(0, p.primaryForHeight(1), "altura 1: no 0");
        assertEquals(1, p.primaryForHeight(2));
        assertEquals(3, p.primaryForHeight(4));
        assertEquals(0, p.primaryForHeight(5), "volta ao no 0");
        assertEquals(0, p.getCurrentPrimaryNumber(), "cabeca = genesis (altura 0): proximo bloco e o da altura 1");
    }

    @Test
    void prePrepareDeQuemNaoEPrimarioDaAlturaEDescartadoEContado() {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, new PBFTConsensusConfig(1000L, jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy.ROTATING));
        PBFTNode receptor = net.getAllNodes().get(2);
        PBFT<PBFTBlock, ?> p = pbft(receptor);
        PBFTBlock b = BlockFactory.samplePBFTBlockWithTx(sim, rng, net.getAllNodes().get(1), PBFT_GENESIS_BLOCK, 5);

        PBFTNode impostor = net.getAllNodes().get(1); // primario da altura 1 e o no 0
        receptor.processIncomingPacket(new Packet(impostor, receptor, new VoteMessage(new PBFTPrePrepareVote<>(impostor, b))));
        assertFalse(p.hasAcceptedPrePrepare(b), "PRE_PREPARE de quem nao e o primario da altura nao e aceito");
        assertEquals(1L, p.getRejectedPrePrepareNotPrimary());

        PBFTNode primario = net.getAllNodes().get(0);
        receptor.processIncomingPacket(new Packet(primario, receptor, new VoteMessage(new PBFTPrePrepareVote<>(primario, b))));
        assertTrue(p.hasAcceptedPrePrepare(b), "o do primario da altura e aceito");
        assertEquals(1L, p.getRejectedPrePrepareNotPrimary(), "o contador nao sobe para o primario");
    }

    @Test
    void corridaHonestaDecideUmBlocoPorAlturaETrafegoIgualAoDeLivro() throws Exception {
        Field committed = PBFT.class.getDeclaredField("committedBlocks");
        committed.setAccessible(true);
        for (int n : new int[] {4, 7}) {
            PBFTLANScenario sc = new PBFTLANScenario("t6", 1L, n, 40.0);
            sc.setMaxFutureHorizon(1000L); sc.setLeaderPolicy(jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy.ROTATING);
            sc.run();
            TreeMap<Long, long[]> porAltura = new TreeMap<>();
            for (PBFTNode nd : sc.getNetwork().getAllNodes()) {
                PBFT<PBFTBlock, ?> p = pbft(nd);
                assertEquals(0L, p.getRejectedPrePrepareNotPrimary(), "honesto: ninguem propoe fora do turno");
                Set<?> commits = (Set<?>) committed.get(p);
                Map<Integer, Integer> contagem = new TreeMap<>();
                for (Object o : commits) {
                    contagem.merge(((PBFTBlock) o).getHeight(), 1, Integer::sum);
                }
                assertEquals(1, Collections.max(contagem.values()), "n=" + n + " no " + nd.nodeID + ": uma decisao por altura");
                assertEquals(commits.size(), contagem.size());
                for (Map.Entry<Long, NetworkHeightLedger.Cell> e : p.getHeightLedger().heights().entrySet()) {
                    porAltura.computeIfAbsent(e.getKey(), k -> new long[1])[0] += e.getValue().getPackets();
                }
            }
            long max = porAltura.lastKey();
            assertTrue(max >= 8, "n=" + n + ": rodou alturas suficientes: " + max);
            // contagem de livro por altura, SEM autoentrega: 1 PRE_PREPARE (n-1) + n PREPARE (n-1 cada) + n COMMIT (n-1 cada)
            long livro = (long) (n - 1) * (2L * n + 1L);
            for (long h = 2; h <= max - 2; h++) {
                assertEquals(livro, porAltura.get(h)[0], "n=" + n + " altura " + h + ": pacotes da rede = contagem de livro");
            }
        }
    }

    @Test
    void semPropostaPeriodicaDoGenesis() throws Exception {
        // Antes: a cada 5 s o agendador propunha outro filho do genesis (cadeia nova). Agora so ha UMA proposta inicial.
        PBFTLANScenario sc = new PBFTLANScenario("t6b", 1L, 4, 30.0);
        sc.setMaxFutureHorizon(1000L); sc.setLeaderPolicy(jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy.ROTATING);
        sc.run();
        List<Integer> alturasComMaisDeUmPrePrepare = new ArrayList<>();
        for (PBFTNode nd : sc.getNetwork().getAllNodes()) {
            for (Map.Entry<Long, NetworkHeightLedger.Cell> e : pbft(nd).getHeightLedger().heights().entrySet()) {
                if (e.getKey() == 1L && e.getValue().getPackets() > 3 * 4 - 1 + 3) {
                    alturasComMaisDeUmPrePrepare.add(nd.nodeID);
                }
            }
        }
        assertTrue(alturasComMaisDeUmPrePrepare.isEmpty(), "altura 1 recebe so os pacotes de uma instancia: " + alturasComMaisDeUmPrePrepare);
    }
}
