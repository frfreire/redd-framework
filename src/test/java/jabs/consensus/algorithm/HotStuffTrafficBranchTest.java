package jabs.consensus.algorithm;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffBlockWithTx;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.DataMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.hotstuff.HotStuffLocalLANNetwork;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static jabs.network.node.nodes.hotstuff.HotStuffNode.HOTSTUFF_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-1: ramo de trafego do HotStuff em PeerBlockchainNode#processIncomingPacket. Granularidade: por no que
 * recebe, por bloco; C_B_total e C_B_consenso. So pacote que passou pela rede simulada conta; autoentrega local
 * nao. (O algoritmo e D3a-2a-2: aqui nenhum voto e processado.)
 */
class HotStuffTrafficBranchTest {
    private static final int TX_BYTES = 512; // .experimento/bateria1.yaml: tamanho_tx_bytes
    private static final int BLOCK_KB = 1024; // .experimento/bateria1.yaml: tamanho_bloco_kb

    private static void drenar(Simulator sim) {
        while (sim.isThereMoreEvents()) {
            sim.executeNextEvent();
        }
    }

    private static HotStuff<HotStuffBlock, HotStuffTx> hs(HotStuffNode no) {
        @SuppressWarnings("unchecked")
        HotStuff<HotStuffBlock, HotStuffTx> r = (HotStuff<HotStuffBlock, HotStuffTx>) no.getConsensusAlgorithm();
        return r;
    }

    // ---------- tamanho do lote e do bloco (BlockFactory) ----------

    @Test
    void blocoTemTamanhoDerivadoDoConfigESemAleatoriedade() {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        assertEquals(HotStuffConsensusConfig.batchSizeFor(BLOCK_KB, TX_BYTES), cfg.getBatchSize());
        assertEquals(2048, cfg.getBatchSize());
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, cfg);
        HotStuffNode criador = net.getAllNodes().get(0);

        HotStuffBlockWithTx b = BlockFactory.sampleHotStuffBlockWithTx(sim, criador, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES);
        assertEquals(2048, b.getTransactionCount());
        assertEquals(BLOCK_KB * 1024, b.getSize(), "2048 x 512 B = 1024 KB");
        assertEquals(1, b.getHeight());
        long soma = 0;
        for (HotStuffTx t : b.getTxs()) {
            assertEquals(TX_BYTES, t.getSize());
            soma += t.getSize();
        }
        assertEquals(b.getSize(), soma, "tamanho do bloco = soma das transacoes");

        HotStuffBlock vazio = BlockFactory.sampleHotStuffBlock(sim, criador, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES);
        assertEquals(BLOCK_KB * 1024, vazio.getSize());

        HotStuffBlockWithTx parcial = BlockFactory.sampleHotStuffBlockWithTx(sim, criador, b, cfg, TX_BYTES, 10);
        assertEquals(10, parcial.getTransactionCount());
        assertEquals(10 * TX_BYTES, parcial.getSize());
        assertEquals(2, parcial.getHeight());

        // nao consome aleatoriedade
        assertEquals(new RandomnessEngine(5).nextLong(), rng.nextLong());
    }

    @Test
    void loteForaDoIntervaloEArgumentosInvalidosFalham() {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(1));
        net.populateNetwork(sim, 4, cfg);
        HotStuffNode c = net.getAllNodes().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> BlockFactory.sampleHotStuffBlockWithTx(sim, c, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockFactory.sampleHotStuffBlockWithTx(sim, c, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 2049));
        assertThrows(IllegalArgumentException.class,
                () -> BlockFactory.sampleHotStuffBlockWithTx(sim, c, HOTSTUFF_GENESIS_BLOCK, cfg, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockFactory.sampleHotStuffBlock(sim, c, null, cfg, TX_BYTES));
    }

    // ---------- hook: bytes = packet.getSize(), so rede, nao autoentrega ----------

    @Test
    void pacoteVindoDaRedeSimuladaSomaOTamanhoDoPacote() {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        RandomnessEngine rng = new RandomnessEngine(7);
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, cfg);
        HotStuffNode emissor = net.getAllNodes().get(0);
        HotStuffNode receptor = net.getAllNodes().get(1);
        HotStuffBlockWithTx bloco = BlockFactory.sampleHotStuffBlockWithTx(sim, emissor, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 20);
        HotStuffVote voto = new HotStuffVote(emissor, HotStuffVote.Phase.PREPARE, 0, bloco, cfg);

        Packet pb = new Packet(emissor, receptor, new DataMessage(bloco));
        Packet pv = new Packet(emissor, receptor, new VoteMessage(voto));
        // entrega PELA rede simulada: fila de envio -> latencia -> recepcao -> processIncomingPacket
        emissor.getNodeNetworkInterface().addToUpLinkQueue(pb);
        emissor.getNodeNetworkInterface().addToUpLinkQueue(pv);
        drenar(sim);

        assertEquals(20 * TX_BYTES, bloco.getSize());
        assertEquals(pb.getSize() + pv.getSize(), hs(receptor).getBlockTraffic(bloco), "C_B_total = bloco + voto");
        assertEquals(pv.getSize(), hs(receptor).getConsensusMessageTraffic(bloco),
                "C_B_consenso = so o voto (a carga do bloco nao e consenso)");
        assertEquals(HotStuffVote.computeSize(bloco, cfg), pv.getSize());
        assertEquals(0, hs(emissor).getBlockTraffic(bloco), "quem so envia nao recebeu nada");
        assertEquals(0, hs(net.getAllNodes().get(2)).getBlockTraffic(bloco), "terceiro nao recebeu");
    }

    @Test
    void autoentregaLocalNaoEContadaMasSegueParaOProcessamento() {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        RandomnessEngine rng = new RandomnessEngine(7);
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(rng);
        net.populateNetwork(sim, 4, cfg);
        HotStuffNode no = net.getAllNodes().get(2);
        HotStuffBlockWithTx bloco = BlockFactory.sampleHotStuffBlockWithTx(sim, no, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 20);
        HotStuffVote voto = new HotStuffVote(no, HotStuffVote.Phase.PREPARE, 0, bloco, cfg);

        no.processIncomingPacket(new Packet(no, no, new DataMessage(bloco)));
        no.processIncomingPacket(new Packet(no, no, new VoteMessage(voto)));

        assertEquals(0, hs(no).getBlockTraffic(bloco), "autoentrega nao e trafego");
        assertEquals(0, hs(no).getConsensusMessageTraffic(bloco));
        assertTrue(no.getConsensusAlgorithm().getLocalBlockTree().contains(bloco),
                "o bloco autoentregue segue para o processamento normal (so a contagem e excluida)");
    }

    @Test
    void duplicataEntregueTambemConsomeBanda() {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, 4, cfg);
        HotStuffNode emissor = net.getAllNodes().get(0);
        HotStuffNode receptor = net.getAllNodes().get(1);
        HotStuffBlockWithTx bloco = BlockFactory.sampleHotStuffBlockWithTx(sim, emissor, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        HotStuffVote voto = new HotStuffVote(emissor, HotStuffVote.Phase.PREPARE, 0, bloco, cfg);
        Packet p = new Packet(emissor, receptor, new VoteMessage(voto));
        receptor.processIncomingPacket(p);
        receptor.processIncomingPacket(p);
        assertEquals(2L * p.getSize(), hs(receptor).getBlockTraffic(bloco));
        assertEquals(2L * p.getSize(), hs(receptor).getConsensusMessageTraffic(bloco));
    }

    @Test
    void broadcastAlcancaOsOutrosNMenos1NosENaoOProprio() {
        for (int n : new int[]{4, 7, 10}) {
            HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
            Simulator sim = new Simulator();
            HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(3));
            net.populateNetwork(sim, n, cfg);
            HotStuffNode lider = net.getAllNodes().get(0);
            HotStuffBlockWithTx bloco = BlockFactory.sampleHotStuffBlockWithTx(sim, lider, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 50);
            DataMessage msg = new DataMessage(bloco);
            lider.broadcastMessage(msg);
            drenar(sim);

            List<Long> recebido = new ArrayList<>();
            for (HotStuffNode no : net.getAllNodes()) {
                recebido.add(hs(no).getBlockTraffic(bloco));
            }
            assertEquals(0L, recebido.get(0), "o lider nao se envia o proprio bloco pela rede (n=" + n + ")");
            for (int i = 1; i < n; i++) {
                assertEquals(msg.getSize(), recebido.get(i), "no " + i + " recebeu o pacote uma vez (n=" + n + ")");
            }
            assertEquals((long) (n - 1) * msg.getSize(), recebido.stream().mapToLong(Long::longValue).sum());
        }
    }

    @Test
    void contabilidadeRejeitaEntradaInvalida() {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(1));
        net.populateNetwork(sim, 4, cfg);
        HotStuff<HotStuffBlock, HotStuffTx> h = hs(net.getAllNodes().get(0));
        assertThrows(IllegalArgumentException.class, () -> h.addBlockTraffic(null, 1));
        assertThrows(IllegalArgumentException.class, () -> h.addBlockTraffic(HOTSTUFF_GENESIS_BLOCK, -1));
        assertThrows(IllegalArgumentException.class, () -> h.addConsensusMessageTraffic(HOTSTUFF_GENESIS_BLOCK, -1));
    }

    // ---------- determinismo por semente ----------

    private static List<Long> trafegoComSemente(long seed) {
        HotStuffConsensusConfig cfg = HotStuffTestConfig.config();
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(sim, 7, cfg);
        HotStuffNode lider = net.getAllNodes().get(0);
        HotStuffBlockWithTx bloco = BlockFactory.sampleHotStuffBlockWithTx(sim, lider, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 30);
        lider.broadcastMessage(new DataMessage(bloco));
        for (HotStuffNode no : net.getAllNodes()) {
            if (no != lider) {
                no.broadcastMessage(new VoteMessage(new HotStuffVote(no, HotStuffVote.Phase.PREPARE, 0, bloco, cfg)));
            }
        }
        drenar(sim);
        List<Long> r = new ArrayList<>();
        for (HotStuffNode no : net.getAllNodes()) {
            r.add(hs(no).getBlockTraffic(bloco));
            r.add(hs(no).getConsensusMessageTraffic(bloco));
        }
        r.add((long) (sim.getSimulationTime() * 1e9)); // o tempo final tambem e reproduzivel
        return r;
    }

    @Test
    void mesmaSementeMesmoTrafegoEMesmoTempo() {
        assertEquals(trafegoComSemente(1), trafegoComSemente(1));
        assertEquals(trafegoComSemente(2), trafegoComSemente(2));
    }
}
