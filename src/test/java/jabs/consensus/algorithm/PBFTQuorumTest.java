package jabs.consensus.algorithm;

import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTCommitVote;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.ledgerdata.pbft.PBFTPrepareVote;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 2: quorum teorico 2f+1 (f = floor((n-1)/3)) com {@code >=}, nas fases PREPARE e COMMIT.
 * Duas camadas: (1) injecao direta de votos distintos em um unico no, para provar o limiar exato;
 * (2) simulacao ponta a ponta com nos em crash (ate f nao impedem; f+1 impedem) e determinismo por semente.
 */
class PBFTQuorumTest {

    // ---------- formula ----------

    @Test
    void formulaDoQuorum() {
        assertEquals(1, PBFT.maxFaultyNodes(4));
        assertEquals(3, PBFT.quorumSize(4));
        assertEquals(2, PBFT.maxFaultyNodes(7));
        assertEquals(5, PBFT.quorumSize(7));
        assertEquals(3, PBFT.maxFaultyNodes(10));
        assertEquals(7, PBFT.quorumSize(10));
        // n = 3f+1 e n = 3f+2, 3f+3 (f nao muda ate o proximo multiplo)
        assertEquals(3, PBFT.quorumSize(5));
        assertEquals(3, PBFT.quorumSize(6));
        assertEquals(7, PBFT.quorumSize(11));
        assertEquals(7, PBFT.quorumSize(12));
        assertEquals(9, PBFT.quorumSize(13));
    }

    @Test
    void construtorAplicaAFormula() {
        for (int n : new int[]{4, 7, 10, 13}) {
            PBFT<PBFTBlock, ?> pbft = novoNo(n).pbft;
            assertEquals(PBFT.quorumSize(n), pbft.getRequiredVotes(), "n=" + n);
            assertEquals(PBFT.maxFaultyNodes(n), pbft.getMaxFaulty(), "n=" + n);
        }
    }

    // ---------- limiar exato por injecao de votos (um no receptor) ----------

    private record Alvo(PBFTNode receptor, PBFT<PBFTBlock, ?> pbft, List<PBFTNode> todos, Simulator sim,
                        RandomnessEngine rng) {}

    private static Alvo novoNo(int n) {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig());
        PBFTNode receptor = net.getAllNodes().get(0);
        @SuppressWarnings("unchecked")
        PBFT<PBFTBlock, ?> pbft = (PBFT<PBFTBlock, ?>) receptor.getConsensusAlgorithm();
        return new Alvo(receptor, pbft, new ArrayList<>(net.getAllNodes()), sim, rng);
    }

    private static PBFTBlock novoBloco(Alvo a) {
        return BlockFactory.samplePBFTBlockWithTx(a.sim(), a.rng(), a.todos().get(1), PBFT_GENESIS_BLOCK, 5);
    }

    private static void prepare(Alvo a, PBFTBlock b, int voter) {
        PBFTNode v = a.todos().get(voter);
        a.receptor().processIncomingPacket(new Packet(v, a.receptor(), new VoteMessage(new PBFTPrepareVote<>(v, b))));
    }

    private static void commit(Alvo a, PBFTBlock b, int voter) {
        PBFTNode v = a.todos().get(voter);
        a.receptor().processIncomingPacket(new Packet(v, a.receptor(), new VoteMessage(new PBFTCommitVote<>(v, b))));
    }

    /** O receptor recebe e aceita o PRE_PREPARE do bloco (enviado pelo no 1, o criador do bloco). */
    private static void prePrepare(Alvo a, PBFTBlock b) {
        PBFTNode v = a.todos().get(1);
        a.receptor().processIncomingPacket(new Packet(v, a.receptor(), new VoteMessage(new PBFTPrePrepareVote<>(v, b))));
    }

    private static void limiarExato(int n, int quorum) {
        Alvo a = novoNo(n);
        PBFTBlock b = novoBloco(a);
        // Caminho feliz: o PRE_PREPARE vem primeiro; so depois os PREPAREs contam.
        prePrepare(a, b);
        assertTrue(a.pbft().hasAcceptedPrePrepare(b));
        // PREPARE: quorum-1 votos distintos nao bastam; o quorum-esimo basta.
        for (int i = 0; i < quorum - 1; i++) prepare(a, b, i);
        assertFalse(a.pbft().isPrepared(b), "n=" + n + ": " + (quorum - 1) + " PREPAREs nao bastam");
        prepare(a, b, quorum - 1);
        assertTrue(a.pbft().isPrepared(b), "n=" + n + ": " + quorum + " PREPAREs bastam");

        // COMMIT (no ja prepared): idem.
        for (int i = 0; i < quorum - 1; i++) commit(a, b, i);
        assertFalse(a.pbft().isCommitted(b), "n=" + n + ": " + (quorum - 1) + " COMMITs nao bastam");
        commit(a, b, quorum - 1);
        assertTrue(a.pbft().isCommitted(b), "n=" + n + ": " + quorum + " COMMITs finalizam");
    }

    @Test
    void n4FinalizaCom3VotosENaoCom2() {
        limiarExato(4, 3);
    }

    @Test
    void n7Exige5() {
        limiarExato(7, 5);
    }

    @Test
    void n10Exige7() {
        limiarExato(10, 7);
    }

    @Test
    void votoDuplicadoDoMesmoNoNaoContaDuasVezes() {
        Alvo a = novoNo(4);
        PBFTBlock b = novoBloco(a);
        prePrepare(a, b);
        prepare(a, b, 0);
        prepare(a, b, 0);
        prepare(a, b, 1);
        prepare(a, b, 1);
        assertFalse(a.pbft().isPrepared(b), "2 nos distintos (4 mensagens) nao formam quorum 3");
        prepare(a, b, 2);
        assertTrue(a.pbft().isPrepared(b));
    }

    @Test
    void votosDeBlocosDistintosNaoSeSomam() {
        Alvo a = novoNo(4);
        PBFTBlock b1 = novoBloco(a);
        PBFTBlock b2 = novoBloco(a);
        prePrepare(a, b1);
        prePrepare(a, b2);
        prepare(a, b1, 0);
        prepare(a, b1, 1);
        prepare(a, b2, 2);
        assertFalse(a.pbft().isPrepared(b1));
        assertFalse(a.pbft().isPrepared(b2));
    }

    // ---------- pre-condicoes de fase (rodada 2) ----------

    @Test
    void preparesSemPrePrepareNaoPreparam() {
        for (int n : new int[]{4, 7, 10}) {
            Alvo a = novoNo(n);
            PBFTBlock b = novoBloco(a);
            // Todos os n nos votam PREPARE (bem acima do quorum), mas o PRE_PREPARE nunca chegou.
            for (int i = 0; i < n; i++) prepare(a, b, i);
            assertFalse(a.pbft().hasAcceptedPrePrepare(b), "n=" + n);
            assertFalse(a.pbft().isPrepared(b), "n=" + n + ": PREPAREs sem PRE_PREPARE nao preparam");
            assertFalse(a.pbft().isCommitted(b), "n=" + n);
            assertEquals(n, a.pbft().getPendingVoteCount(b), "n=" + n + ": os votos ficam retidos, nao descartados");
        }
    }

    @Test
    void commitsSemPreparedNaoFinalizam() {
        for (int n : new int[]{4, 7, 10}) {
            // (a) sem PRE_PREPARE nem PREPARE.
            Alvo a = novoNo(n);
            PBFTBlock b = novoBloco(a);
            for (int i = 0; i < n; i++) commit(a, b, i);
            assertFalse(a.pbft().isPrepared(b), "n=" + n);
            assertFalse(a.pbft().isCommitted(b), "n=" + n + ": COMMITs sem prepared nao finalizam");

            // (b) com PRE_PREPARE aceito e PREPAREs abaixo do quorum: ainda nao esta prepared.
            Alvo c = novoNo(n);
            PBFTBlock b2 = novoBloco(c);
            int q = PBFT.quorumSize(n);
            prePrepare(c, b2);
            for (int i = 0; i < q - 1; i++) prepare(c, b2, i);
            for (int i = 0; i < n; i++) commit(c, b2, i);
            assertFalse(c.pbft().isPrepared(b2), "n=" + n);
            assertFalse(c.pbft().isCommitted(b2), "n=" + n + ": prepared ausente, COMMITs nao finalizam");
        }
    }

    @Test
    void commitAdiantadoEhAproveitadoQuandoOnoFicaPrepared() {
        for (int n : new int[]{4, 7, 10}) {
            Alvo a = novoNo(n);
            PBFTBlock b = novoBloco(a);
            int q = PBFT.quorumSize(n);
            prePrepare(a, b);
            for (int i = 0; i < q; i++) commit(a, b, i); // quorum de COMMITs antes de prepared
            assertFalse(a.pbft().isCommitted(b), "n=" + n);
            for (int i = 0; i < q - 1; i++) prepare(a, b, i);
            assertFalse(a.pbft().isCommitted(b), "n=" + n);
            prepare(a, b, q - 1);
            assertTrue(a.pbft().isPrepared(b), "n=" + n);
            assertTrue(a.pbft().isCommitted(b), "n=" + n + ": COMMITs retidos reavaliados ao ficar prepared");
            assertEquals(0, a.pbft().getPendingVoteCount(b), "n=" + n + ": sem retencao residual");
        }
    }

    @Test
    void prepareAdiantadoEhAproveitadoDepoisDoPrePrepare() {
        for (int n : new int[]{4, 7, 10}) {
            int q = PBFT.quorumSize(n);
            // Todos os PREPAREs do quorum chegam antes do PRE_PREPARE: nada muda ate ele chegar.
            Alvo a = novoNo(n);
            PBFTBlock b = novoBloco(a);
            for (int i = 0; i < q; i++) prepare(a, b, i);
            assertFalse(a.pbft().isPrepared(b), "n=" + n);
            prePrepare(a, b);
            assertTrue(a.pbft().isPrepared(b), "n=" + n + ": quorum retido e reavaliado no PRE_PREPARE");
            assertEquals(0, a.pbft().getPendingVoteCount(b), "n=" + n);

            // Parte antes, parte depois: nenhum voto se perde (q-1 antes + 1 depois = q distintos).
            Alvo c = novoNo(n);
            PBFTBlock b2 = novoBloco(c);
            for (int i = 0; i < q - 1; i++) prepare(c, b2, i);
            prePrepare(c, b2);
            assertFalse(c.pbft().isPrepared(b2), "n=" + n + ": q-1 votos ainda nao bastam");
            prepare(c, b2, q - 1);
            assertTrue(c.pbft().isPrepared(b2), "n=" + n);
        }
    }

    @Test
    void prepareAdiantadoDuplicadoNaoContaDuasVezes() {
        Alvo a = novoNo(4);
        PBFTBlock b = novoBloco(a);
        prepare(a, b, 0);
        prepare(a, b, 0);
        prepare(a, b, 1);
        prePrepare(a, b);
        assertFalse(a.pbft().isPrepared(b), "2 nos distintos nao formam quorum 3");
        prepare(a, b, 2);
        assertTrue(a.pbft().isPrepared(b));
    }

    // ---------- ponta a ponta: nos em crash ----------

    /** Nos que finalizaram pelo menos um bloco, com n nos e os indices em crash. */
    private static List<Integer> finalizadores(long seed, int n, double stop, int... crash) {
        RandomnessEngine rng = new RandomnessEngine(seed);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig());
        for (int c : crash) net.getAllNodes().get(c).crash();
        net.startConsensusRound(sim, stop);

        List<Integer> out = new ArrayList<>();
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= stop) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                out.add(fe.getNode().nodeID);
            }
        }
        return out;
    }

    // O primario inicial e o no min(10, n-1); os crashes abaixo usam indices menores.

    @Test
    void n4_ateFCrashNaoImpede_FMaisUmImpede() {
        assertFalse(finalizadores(1, 4, 30, 0).isEmpty(), "f=1 em crash: 3 vivos = quorum 3");
        assertTrue(finalizadores(1, 4, 30, 0, 1).isEmpty(), "f+1=2 em crash: 2 vivos < quorum 3");
    }

    @Test
    void n7_ateFCrashNaoImpede_FMaisUmImpede() {
        assertFalse(finalizadores(1, 7, 30, 0, 1).isEmpty(), "f=2 em crash: 5 vivos = quorum 5");
        assertTrue(finalizadores(1, 7, 30, 0, 1, 2).isEmpty(), "f+1=3 em crash: 4 vivos < quorum 5");
    }

    @Test
    void n10_ateFCrashNaoImpede_FMaisUmImpede() {
        assertFalse(finalizadores(1, 10, 30, 0, 1, 2).isEmpty(), "f=3 em crash: 7 vivos = quorum 7");
        assertTrue(finalizadores(1, 10, 30, 0, 1, 2, 3).isEmpty(), "f+1=4 em crash: 6 vivos < quorum 7");
    }

    @Test
    void crashNaoFazONoCrashadoFinalizar() {
        for (int id : finalizadores(1, 7, 30, 0, 1)) {
            assertTrue(id != 0 && id != 1, "no em crash nao finaliza: " + id);
        }
    }

    // ---------- determinismo ----------

    @Test
    void mesmaSementeMesmaSaida() {
        assertEquals(finalizadores(42, 7, 30), finalizadores(42, 7, 30));
        assertEquals(finalizadores(42, 7, 30, 0, 1), finalizadores(42, 7, 30, 0, 1));
    }
}
