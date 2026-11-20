package jabs.consensus.algorithm;

import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTCommitVote;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.ledgerdata.pbft.PBFTPrepareVote;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.scenario.PBFTLANScenario;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PARIDADE-teto-retencao-pbft-tendermint: voto de bloco a mais de {@code maxFutureHorizon} alturas a frente da cabeca da
 * cadeia e descartado (contado, nao retido) e seus bytes vao a um agregado proprio, sem sumir do livro; dentro do teto o
 * comportamento e o de antes (voto adiantado fica retido ate o PRE_PREPARE). O parametro e obrigatorio e sem padrao.
 */
class PBFTTetoRetencaoTest {
    private static final long TETO = 10L;

    private record Alvo(PBFTNode receptor, PBFT<PBFTBlock, ?> pbft, List<PBFTNode> todos) {}

    private static Alvo novoNo() {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, new PBFTConsensusConfig(TETO));
        PBFTNode receptor = net.getAllNodes().get(0);
        @SuppressWarnings("unchecked")
        PBFT<PBFTBlock, ?> pbft = (PBFT<PBFTBlock, ?>) receptor.getConsensusAlgorithm();
        return new Alvo(receptor, pbft, new ArrayList<>(net.getAllNodes()));
    }

    private static PBFTBlock bloco(Alvo a, int altura) {
        return new PBFTBlock(100, altura, 0.0, a.todos().get(1), PBFT_GENESIS_BLOCK);
    }

    private static void envia(Alvo a, int voter, jabs.ledgerdata.Vote v) {
        PBFTNode from = a.todos().get(voter);
        a.receptor().processIncomingPacket(new Packet(from, a.receptor(), new VoteMessage(v)));
    }

    @Test
    void votoNoLimiteDoTetoEhRetido() {
        Alvo a = novoNo();
        PBFTBlock b = bloco(a, (int) TETO); // cabeca = genesis (altura 0): distancia == teto, nao e "alem"
        envia(a, 2, new PBFTPrepareVote<>(a.todos().get(2), b));
        assertEquals(0L, a.pbft().getDiscardedBeyondHorizon());
        assertEquals(1, a.pbft().getPendingVoteCount(b), "voto adiantado dentro do teto fica retido ate o PRE_PREPARE");
    }

    @Test
    void votoUmAlemDoTetoEhDescartadoEContado() {
        Alvo a = novoNo();
        PBFTBlock b = bloco(a, (int) TETO + 1);
        envia(a, 2, new PBFTPrepareVote<>(a.todos().get(2), b));
        envia(a, 3, new PBFTCommitVote<>(a.todos().get(3), b));
        assertEquals(2L, a.pbft().getDiscardedBeyondHorizon());
        assertEquals(0, a.pbft().getPendingVoteCount(b), "nada retido alem do teto");
    }

    @Test
    void prePrepareAlemDoTetoNaoEntraNaArvoreNemEhAceito() {
        Alvo a = novoNo();
        PBFTBlock b = bloco(a, (int) TETO + 5);
        envia(a, 1, new PBFTPrePrepareVote<>(a.todos().get(1), b));
        assertEquals(1L, a.pbft().getDiscardedBeyondHorizon());
        assertFalse(a.pbft().hasAcceptedPrePrepare(b));
    }

    @Test
    void bytesAlemDoTetoNaoSomemDoLivro() {
        Alvo a = novoNo();
        PBFTBlock longe = bloco(a, (int) TETO + 1);
        long antes = a.pbft().getAccountedVoteBytes();
        a.pbft().addBlockTraffic(longe, 77);
        a.pbft().addConsensusMessageTraffic(longe, 55);
        assertEquals(antes + 77, a.pbft().getAccountedVoteBytes(), "o byte fecha no livro, no agregado alem-do-teto");
        assertEquals(55, a.pbft().getAccountedConsensusBytes());
        assertEquals(0, a.pbft().getOpenTrafficEntryCount(), "nenhuma entrada por bloco aberta alem do teto");
        PBFTBlock perto = bloco(a, 1);
        a.pbft().addBlockTraffic(perto, 10);
        assertEquals(10, a.pbft().getBlockTraffic(perto), "dentro do teto o byte e atribuido ao bloco como antes");
    }

    @Test
    void tetoInvalidoOuAusenteFalhaAlto() {
        assertThrows(IllegalArgumentException.class, () -> new PBFTConsensusConfig(0L));
        assertThrows(IllegalArgumentException.class, () -> new PBFTConsensusConfig(-1L));
        assertThrows(IllegalArgumentException.class, () -> new PBFT<PBFTBlock, jabs.ledgerdata.pbft.PBFTTx>(
                new jabs.consensus.blockchain.LocalBlockTree<>(PBFT_GENESIS_BLOCK), 4, 0L));
        PBFTLANScenario sc = new PBFTLANScenario("sem-teto", 1L, 4, 5.0);
        IllegalStateException e = assertThrows(IllegalStateException.class, sc::run);
        assertTrue(e.getMessage().contains("maxFutureHorizon"), e.getMessage());
    }
}
