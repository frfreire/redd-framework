package jabs.network.networks.tendermint;

import jabs.consensus.algorithm.Tendermint;
import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.ledgerdata.tendermint.TendermintBlockWithTx;
import jabs.ledgerdata.tendermint.TendermintTx;
import jabs.network.message.Packet;
import jabs.network.message.TendermintProposalMessage;
import jabs.network.message.TendermintVoteMessage;
import jabs.network.networks.tendermint.TendermintLocalLANNetwork;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static jabs.network.node.nodes.tendermint.TendermintNode.TENDERMINT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hook de PeerBlockchainNode: bytes medidos por no que recebe, por bloco, com corte na finalizacao local. */
class TendermintTrafficTest {
    private static final int N = 4;

    private Simulator sim;
    private TendermintLocalLANNetwork net;
    private TendermintNode receptor;
    private TendermintNode emissor;
    private Tendermint<TendermintBlock, TendermintTx> alg;
    private TendermintBlockWithTx bloco;

    @BeforeEach
    void montar() {
        sim = new Simulator();
        net = new TendermintLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, N, TendermintTestConfig.config());
        receptor = net.getNode(1);
        emissor = net.getNode(0);
        @SuppressWarnings("unchecked")
        Tendermint<TendermintBlock, TendermintTx> t =
                (Tendermint<TendermintBlock, TendermintTx>) receptor.getConsensusAlgorithm();
        alg = t;
        bloco = BlockFactory.sampleTendermintBlockWithTx(sim, emissor, TENDERMINT_GENESIS_BLOCK,
                TendermintTestConfig.config());
    }

    @Test
    void propostaContaTotalInteiroEConsensoSemCargaDoBloco() {
        TendermintProposalMessage m = new TendermintProposalMessage(emissor, 0, 0, bloco, -1,
                TendermintTestConfig.config());
        Packet p = new Packet(emissor, receptor, m);
        receptor.processIncomingPacket(p);

        assertEquals(p.getSize(), alg.getBlockTraffic(bloco), "C_B_total = pacote inteiro");
        assertEquals(p.getSize() - bloco.getSize(), alg.getConsensusMessageTraffic(bloco),
                "C_B_consenso = pacote menos a carga do bloco");
        assertTrue(p.getSize() > bloco.getSize());
        assertTrue(alg.getLocalBlockTree().contains(bloco), "a proposta admite o bloco na arvore");
    }

    @Test
    void propostaMedidaNaRedeRefleteAssinaturaDoProponenteNosContadoresDeTotalEConsenso() {
        TendermintProposalMessage m = new TendermintProposalMessage(emissor, 0, 0, bloco, -1,
                TendermintTestConfig.config());
        int consenso = TendermintBlock.TENDERMINT_BLOCK_HASH_SIZE
                + TendermintTestConfig.config().getMessageOverheadBytes()
                + TendermintTestConfig.TEST_FIXTURE_PROPOSER_SIGNATURE_SIZE_BYTES;
        long total = consenso + bloco.getSize();
        // detecta reuso da assinatura do voto na proposta
        assertTrue(TendermintTestConfig.TEST_FIXTURE_PROPOSER_SIGNATURE_SIZE_BYTES
                != TendermintTestConfig.TEST_FIXTURE_SIGNATURE_SIZE_BYTES);
        assertEquals(total, m.getSize());
        Packet p = new Packet(emissor, receptor, m);
        assertEquals(total, p.getSize());
        receptor.processIncomingPacket(p);
        assertEquals(total, alg.getBlockTraffic(bloco), "C_B_total = hash + overhead + assinatura do proponente + payload");
        assertEquals(consenso, alg.getConsensusMessageTraffic(bloco),
                "C_B_consenso = hash + overhead + assinatura do proponente (sem payload do bloco)");
    }

    @Test
    void duplicataTambemConsomeBanda() {
        TendermintProposalMessage m = new TendermintProposalMessage(emissor, 0, 0, bloco, -1,
                TendermintTestConfig.config());
        Packet p = new Packet(emissor, receptor, m);
        receptor.processIncomingPacket(p);
        receptor.processIncomingPacket(p);
        assertEquals(2L * p.getSize(), alg.getBlockTraffic(bloco));
    }

    @Test
    void votoContaInteiroNosDoisContadores() {
        TendermintVoteMessage m = new TendermintVoteMessage(emissor, TendermintVote.Type.PREVOTE, 0, 0,
                bloco, TendermintTestConfig.config());
        Packet p = new Packet(emissor, receptor, m);
        receptor.processIncomingPacket(p);
        assertEquals(p.getSize(), alg.getBlockTraffic(bloco));
        assertEquals(p.getSize(), alg.getConsensusMessageTraffic(bloco));
    }

    @Test
    void votoMedidoNaRedeRefleteTamanhoComAssinatura() {
        TendermintVoteMessage m = new TendermintVoteMessage(emissor, TendermintVote.Type.PREVOTE, 0, 0,
                bloco, TendermintTestConfig.config());
        int esperado = TendermintBlock.TENDERMINT_BLOCK_HASH_SIZE
                + TendermintTestConfig.config().getMessageOverheadBytes()
                + TendermintTestConfig.TEST_FIXTURE_SIGNATURE_SIZE_BYTES;
        assertEquals(esperado, m.getSize());
        Packet p = new Packet(emissor, receptor, m);
        assertEquals(esperado, p.getSize());
        receptor.processIncomingPacket(p);
        assertEquals(esperado, alg.getBlockTraffic(bloco), "trafego medido = hash + overhead + assinatura");
        assertEquals(esperado, alg.getConsensusMessageTraffic(bloco));
    }

    @Test
    void votoNilVaiParaContadorAParte() {
        TendermintVoteMessage m = new TendermintVoteMessage(emissor, TendermintVote.Type.PREVOTE, 0, 0,
                null, TendermintTestConfig.config());
        Packet p = new Packet(emissor, receptor, m);
        receptor.processIncomingPacket(p);
        assertEquals(p.getSize(), alg.getUnattributedTraffic());
        assertEquals(0, alg.getBlockTraffic(bloco));
        assertEquals(p.getSize(), alg.takeUnattributedTraffic());
        assertEquals(0, alg.getUnattributedTraffic());
    }

    @Test
    void corteNaFinalizacaoLocalIgnoraPacoteTardio() {
        alg.markLocallyFinalized(bloco);
        TendermintVoteMessage m = new TendermintVoteMessage(emissor, TendermintVote.Type.PRECOMMIT, 0, 0,
                bloco, TendermintTestConfig.config());
        receptor.processIncomingPacket(new Packet(emissor, receptor, m));
        assertEquals(0, alg.getBlockTraffic(bloco));
        assertEquals(0, alg.getConsensusMessageTraffic(bloco));
    }
}
