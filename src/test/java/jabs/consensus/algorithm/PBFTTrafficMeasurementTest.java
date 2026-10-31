package jabs.consensus.algorithm;

import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTBlockVote;
import jabs.ledgerdata.pbft.PBFTCommitVote;
import jabs.ledgerdata.pbft.PBFTPrepareVote;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 1: PBFT mede trafego (recebido por no) em vez de estimar por formula.
 * Cobre: medicao no hook de PeerBlockchainNode, decomposicao C_B_total/C_B_consenso,
 * determinismo por semente (RandomnessEngine), no silencioso e ausencia da formula/Random(42).
 * Quorum NAO e testado aqui (tarefa 2 do ADR-0006).
 */
class PBFTTrafficMeasurementTest {

    /** Uma finalizacao observada: (no, altura, C_B_total, C_B_consenso, tempo). */
    private record Fin(int nodeId, int height, long total, long consensus, double time) {}

    private static List<Fin> simular(long seed, int n, double stop, Integer noSilencioso) {
        RandomnessEngine rng = new RandomnessEngine(seed);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig());
        if (noSilencioso != null) {
            net.getAllNodes().get(noSilencioso).crash();
        }
        net.startConsensusRound(sim, stop);

        List<Fin> out = new ArrayList<>();
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= stop) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                out.add(new Fin(fe.getNode().nodeID, fe.getBlock().getHeight(),
                        fe.getTrafficUntilFinalization(), fe.getConsensusMessageTraffic(),
                        sim.getSimulationTime()));
            }
        }
        return out;
    }

    // ---------- hook de PeerBlockchainNode: bytes = packet.getSize(), por no ----------

    @Test
    void hookContaBytesDoPacoteNoNoQueRecebeu() {
        RandomnessEngine rng = new RandomnessEngine(7);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, new jabs.consensus.config.PBFTConsensusConfig());
        PBFTNode receptor = net.getAllNodes().get(1);
        PBFTNode emissor = net.getAllNodes().get(0);
        PBFT<PBFTBlock, ?> pbft = (PBFT<PBFTBlock, ?>) receptor.getConsensusAlgorithm();

        PBFTBlock bloco = BlockFactory.samplePBFTBlockWithTx(sim, rng, emissor, PBFT_GENESIS_BLOCK, 5);

        Packet pre = new Packet(emissor, receptor, new VoteMessage(new PBFTPrePrepareVote<>(emissor, bloco)));
        receptor.processIncomingPacket(pre);
        assertEquals(pre.getSize(), pbft.getBlockTraffic(bloco),
                "C_B_total do PRE_PREPARE = pacote inteiro (carrega o bloco)");
        assertEquals(PBFTBlockVote.PBFT_VOTE_SIZE_OVERHEAD, pbft.getConsensusMessageTraffic(bloco),
                "C_B_consenso do PRE_PREPARE = so o overhead fixo do anuncio");
        assertTrue(pre.getSize() >= bloco.getSize(), "o pacote do PRE_PREPARE carrega o bloco inteiro");

        Packet prep = new Packet(emissor, receptor, new VoteMessage(new PBFTPrepareVote<>(emissor, bloco)));
        receptor.processIncomingPacket(prep);
        long total = pre.getSize() + prep.getSize();
        long cons = PBFTBlockVote.PBFT_VOTE_SIZE_OVERHEAD + prep.getSize();
        assertEquals(total, pbft.getBlockTraffic(bloco));
        assertEquals(cons, pbft.getConsensusMessageTraffic(bloco), "PREPARE conta inteiro no consenso");
        assertEquals(42, prep.getSize(), "PREPARE leva so o hash: 32 + 10");

        Packet com = new Packet(emissor, receptor, new VoteMessage(new PBFTCommitVote<>(emissor, bloco)));
        receptor.processIncomingPacket(com);
        assertEquals(total + com.getSize(), pbft.getBlockTraffic(bloco));
        assertEquals(cons + com.getSize(), pbft.getConsensusMessageTraffic(bloco));
    }

    @Test
    void duplicataTambemConsomeBanda() {
        RandomnessEngine rng = new RandomnessEngine(7);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, new jabs.consensus.config.PBFTConsensusConfig());
        PBFTNode receptor = net.getAllNodes().get(1);
        PBFTNode emissor = net.getAllNodes().get(0);
        PBFT<PBFTBlock, ?> pbft = (PBFT<PBFTBlock, ?>) receptor.getConsensusAlgorithm();
        PBFTBlock bloco = BlockFactory.samplePBFTBlockWithTx(sim, rng, emissor, PBFT_GENESIS_BLOCK, 5);

        PBFTPrePrepareVote<PBFTBlock> voto = new PBFTPrePrepareVote<>(emissor, bloco);
        Packet p = new Packet(emissor, receptor, new VoteMessage(voto));
        receptor.processIncomingPacket(p);
        receptor.processIncomingPacket(p);
        assertEquals(2L * p.getSize(), pbft.getBlockTraffic(bloco));
    }

    // ---------- simulacao ponta a ponta ----------

    @Test
    void trafegoMedidoNaoEhAFormulaAntiga() {
        int n = 7;
        List<Fin> fins = simular(1, n, 30, null);
        assertFalse(fins.isEmpty(), "deve haver finalizacoes");
        for (Fin f : fins) {
            assertTrue(f.total() > 100_000,
                    "C_B_total medido inclui o bloco (~centenas de KB), nao n*2*1024=" + (n * 2 * 1024));
            assertTrue(f.consensus() >= 0, "C_B_consenso medido (>= 0, nao sentinela)");
            assertTrue(f.consensus() < f.total(), "consenso < total (o bloco nao conta no consenso)");
            assertNotEquals((long) n * 2 * 1024, f.total(), "nao pode ser a formula fechada");
        }
    }

    @Test
    void consensoCrescePorNoComNeAoNaoComOBloco() {
        // O consenso por no recebido cresce ~linear em n (todos-para-todos: n votos por fase chegam a cada no).
        long c7 = mediaConsenso(simular(1, 7, 30, null));
        long c13 = mediaConsenso(simular(1, 13, 30, null));
        assertTrue(c13 > c7, "C_B_consenso por no em n=13 > n=7 (" + c13 + " vs " + c7 + ")");
    }

    private static long mediaConsenso(List<Fin> fins) {
        assertFalse(fins.isEmpty());
        long s = 0;
        for (Fin f : fins) s += f.consensus();
        return s / fins.size();
    }

    @Test
    void mesmaSementeMesmaSaida() {
        assertEquals(simular(42, 7, 30, null), simular(42, 7, 30, null));
    }

    @Test
    void sementesDistintasTrafegoDistinto() {
        List<Long> a = new ArrayList<>(), b = new ArrayList<>();
        simular(1, 7, 30, null).forEach(f -> a.add(f.total()));
        simular(2, 7, 30, null).forEach(f -> b.add(f.total()));
        assertNotEquals(a, b, "com Random(42) fixo + formula isto seria igual por construcao");
    }

    @Test
    void noSilenciosoNaoRecebeNemFinaliza() {
        // no 0 fora do ar desde o inicio (proposta parte do no min(10, n-1) = 6)
        List<Fin> fins = simular(3, 7, 30, 0);
        assertFalse(fins.isEmpty(), "os demais ainda finalizam");
        for (Fin f : fins) {
            assertNotEquals(0, f.nodeId(), "no silencioso nao recebe pacote, logo nao finaliza nem acumula bytes");
        }
    }

    // ---------- higiene do codigo ----------

    @Test
    void pbftNaoTemFormulaNemRandomFixo() throws Exception {
        Path fonte = Paths.get("src/main/java/jabs/consensus/algorithm/PBFT.java");
        String s = Files.readString(fonte);
        assertFalse(s.contains("numAllParticipants * 2 * 1024"));
        assertFalse(s.contains("Random(42)"));
        assertFalse(s.contains("nextGaussian"));
    }

    @Test
    void sentinelaNaoMedidoPermaneceParaProtocolosSemDecomposicao() {
        BlockFinalizationEvent e = new BlockFinalizationEvent(0.0, null, null, 123L);
        assertEquals(BlockFinalizationEvent.NOT_MEASURED, e.getConsensusMessageTraffic());
        assertEquals(-1L, e.getConsensusMessageTraffic());
        assertEquals(123L, e.getTrafficUntilFinalization());
    }
}
