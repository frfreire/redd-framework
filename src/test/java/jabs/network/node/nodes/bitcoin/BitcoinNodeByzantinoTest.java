package jabs.network.node.nodes.bitcoin;

import jabs.config.ByzantineConfig;
import jabs.consensus.algorithm.EnvioDeTeste;
import jabs.consensus.algorithm.FalhaInjetada;
import jabs.consensus.algorithm.GuardaCatch;
import jabs.consensus.config.NakamotoConsensusConfig;
import jabs.ledgerdata.TransactionFactory;
import jabs.ledgerdata.bitcoin.BitcoinBlockWithoutTx;
import jabs.ledgerdata.bitcoin.BitcoinTx;
import jabs.network.message.DataMessage;
import jabs.network.message.InvMessage;
import jabs.network.message.Packet;
import jabs.network.networks.bitcoin.BitcoinGlobalProofOfWorkNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.stats.eightysixcountries.bitcoin.BitcoinProofOfWorkGlobalNetworkStats86Countries;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static jabs.network.stats.eightysixcountries.bitcoin.BitcoinProofOfWorkGlobalNetworkStats86Countries.BITCOIN_DIFFICULTY_2022;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Criterio 4 (modelo de falha explicito) nos hooks do Nakamoto em {@link BitcoinNode}: WITHHOLD/EQUIVOCATION
 * (retem o bloco minerado), propagacao de transacao e registro no mempool. Os tres {@code catch (Exception ignored)}
 * saíram; falha de envio ou de registro PROPAGA. Fixa tambem a semantica que nao mudou: EQUIVOCATION == WITHHOLD no
 * Nakamoto (decisao do humano), SILENT/DOUBLE_SIGN recusados.
 */
class BitcoinNodeByzantinoTest {
    private static final int MINERADORES = 24; // BitcoinCoreP2P exige n*8 < n(n-1)/2 para o setup terminar
    private static final int NAO_MINERADORES = 2;

    private static final class Cena {
        final Simulator sim = new Simulator();
        final RandomnessEngine rng = new RandomnessEngine(3);
        final BitcoinGlobalProofOfWorkNetwork<?> net;
        final BitcoinMinerNode miner;
        final BitcoinNode comum;
        final EnvioDeTeste envioMiner;
        final EnvioDeTeste envioComum;
        final BitcoinBlockWithoutTx genesis;

        Cena() {
            net = new BitcoinGlobalProofOfWorkNetwork<>(rng, new BitcoinProofOfWorkGlobalNetworkStats86Countries(rng));
            genesis = BitcoinBlockWithoutTx.generateGenesisBlock(BITCOIN_DIFFICULTY_2022);
            net.populateNetwork(sim, MINERADORES, NAO_MINERADORES,
                    new NakamotoConsensusConfig(genesis, 600.0, 14));
            miner = (BitcoinMinerNode) net.getAllNodes().get(5);
            comum = (BitcoinNode) net.getAllNodes().get(MINERADORES);
            assertFalse(comum instanceof BitcoinMinerNode, "controle: o no comum nao e minerador");
            envioMiner = EnvioDeTeste.instala(miner, sim, net, rng);
            envioComum = EnvioDeTeste.instala(comum, sim, net, rng);
        }

        void bizantino(String ataque) {
            miner.getConsensusAlgorithm().setByzantineConfig(
                    new ByzantineConfig(MINERADORES + NAO_MINERADORES, Set.of(miner.nodeID), ataque, 3));
        }

        BitcoinBlockWithoutTx blocoDoMiner() {
            return new BitcoinBlockWithoutTx(1000, 1, 1.0, miner, genesis, BITCOIN_DIFFICULTY_2022, 1.0);
        }

        void recebeBloco(BitcoinNode no, BitcoinBlockWithoutTx b) {
            no.processIncomingPacket(new Packet(no, no, new DataMessage(b)));
        }

        int vizinhosDe(Node no) {
            return no.getP2pConnections().getNeighbors().size();
        }
    }

    private static boolean ehInv(Packet p) {
        return p.getMessage() instanceof InvMessage;
    }

    // ---------- hook de bloco: WITHHOLD / EQUIVOCATION (semantica preservada) ----------

    @Test
    void minerHonestoAnunciaOBlocoAosVizinhos() {
        Cena c = new Cena();
        c.recebeBloco(c.miner, c.blocoDoMiner());
        assertEquals(c.vizinhosDe(c.miner), c.envioMiner.doTipo(InvMessage.class).size());
    }

    @Test
    void withholdEEquivocationRetemOBlocoMinerado() {
        for (String ataque : new String[]{"WITHHOLD", "EQUIVOCATION"}) {
            Cena c = new Cena();
            c.bizantino(ataque);
            c.envioMiner.falharQuando = BitcoinNodeByzantinoTest::ehInv; // se tentasse anunciar, a falha apareceria
            c.recebeBloco(c.miner, c.blocoDoMiner());
            assertEquals(0, c.envioMiner.doTipo(InvMessage.class).size(), ataque + ": bloco retido");
        }
    }

    @Test
    void bizantinoNaoRetemBlocoDeOutroMinerador() {
        // O hook so retem o bloco que o PROPRIO no minerou: o bloco de outro e anunciado normalmente.
        Cena c = new Cena();
        c.bizantino("WITHHOLD");
        BitcoinBlockWithoutTx deOutro = new BitcoinBlockWithoutTx(1000, 1, 1.0, c.net.getAllNodes().get(7), c.genesis,
                BITCOIN_DIFFICULTY_2022, 1.0);
        c.recebeBloco(c.miner, deOutro);
        assertEquals(c.vizinhosDe(c.miner), c.envioMiner.doTipo(InvMessage.class).size());
    }

    @Test
    void silentEDoubleSignSaoRecusadosEmVezDeVirarHonestos() {
        for (String ataque : new String[]{"SILENT", "DOUBLE_SIGN"}) {
            Cena c = new Cena();
            assertThrows(UnsupportedOperationException.class, () -> c.bizantino(ataque), ataque);
        }
    }

    // ---------- (criterio 4) falha de envio atravessa o hook ----------

    @Test
    void falhaAoAnunciarBlocoHonestoPropaga() {
        Cena c = new Cena();
        c.envioMiner.falharQuando = BitcoinNodeByzantinoTest::ehInv;
        assertThrows(FalhaInjetada.class, () -> c.recebeBloco(c.miner, c.blocoDoMiner()));
    }

    @Test
    void falhaAoPropagarTransacaoRecebidaPropaga() {
        Cena c = new Cena();
        c.envioMiner.falharQuando = BitcoinNodeByzantinoTest::ehInv;
        BitcoinTx tx = TransactionFactory.sampleBitcoinTransaction(c.rng);
        assertThrows(FalhaInjetada.class,
                () -> c.miner.processIncomingPacket(new Packet(c.comum, c.miner, new DataMessage(tx))));
    }

    @Test
    void falhaAoGerarTransacaoNovaPropaga() {
        Cena c = new Cena();
        c.envioMiner.falharQuando = BitcoinNodeByzantinoTest::ehInv;
        assertThrows(FalhaInjetada.class, c.miner::generateNewTransaction);
        c.envioComum.falharQuando = BitcoinNodeByzantinoTest::ehInv;
        assertThrows(FalhaInjetada.class, c.comum::generateNewTransaction);
    }

    // ---------- mempool: condicao legitima do antigo catch e agora checagem explicita ----------

    @Test
    void minerGuardaATransacaoNoMempoolEONaoMineradorNao() {
        Cena c = new Cena();
        BitcoinTx recebida = TransactionFactory.sampleBitcoinTransaction(c.rng);
        c.miner.processIncomingPacket(new Packet(c.comum, c.miner, new DataMessage(recebida)));
        assertTrue(c.miner.memPool.contains(recebida), "minerador guarda a tx recebida no mempool");
        assertEquals(c.vizinhosDe(c.miner), c.envioMiner.doTipo(InvMessage.class).size(), "e a repassa a cada vizinho");

        int antes = c.miner.memPool.size();
        c.miner.generateNewTransaction();
        assertEquals(antes + 1, c.miner.memPool.size(), "tx gerada pelo proprio minerador tambem vai ao mempool");

        // No comum (nao minerador) nao ha mempool: so repassa, sem lancar.
        assertDoesNotThrow(c.comum::generateNewTransaction);
        assertDoesNotThrow(() -> c.comum.processIncomingPacket(
                new Packet(c.miner, c.comum, new DataMessage(TransactionFactory.sampleBitcoinTransaction(c.rng)))));
        assertEquals(c.vizinhosDe(c.comum) * 2, c.envioComum.doTipo(InvMessage.class).size());
    }

    // ---------- guarda estatica ----------

    @Test
    void bitcoinNodeNaoTemCatchDeExceptionVazioNemIgnored() throws IOException {
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/network/node/nodes/bitcoin/BitcoinNode.java"));
    }
}
