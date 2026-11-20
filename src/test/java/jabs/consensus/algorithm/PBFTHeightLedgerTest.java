package jabs.consensus.algorithm;

import jabs.consensus.config.PBFTConsensusConfig;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.metrics.NetworkHeightLedger;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.scenario.PBFTLANScenario;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PBFT puro no MVPComparison (PBFT-puro-ledger): o acumulador por altura (soma da rede) fecha com os livros do
 * PBFT: cada byte que atravessou a rede simulada e contado uma vez (nunca autoentrega), e trafego alem do teto de
 * retencao NAO cria celula por altura (memoria limitada).
 */
class PBFTHeightLedgerTest {
    private static final long TETO = 1000L;

    @SuppressWarnings("unchecked")
    private static PBFT<PBFTBlock, ?> pbft(PBFTNode n) {
        return (PBFT<PBFTBlock, ?>) n.getConsensusAlgorithm();
    }

    @Test
    void ledgerFechaComOsLivrosDoPbftNumaCorridaHonesta() throws Exception {
        PBFTLANScenario sc = new PBFTLANScenario("ledger", 1L, 4, 30.0);
        sc.setMaxFutureHorizon(TETO);
        sc.run();
        long total = 0, consenso = 0, ledgerTotal = 0, ledgerConsenso = 0, semAltura = 0;
        int finalizadas = 0;
        for (PBFTNode n : sc.getNetwork().getAllNodes()) {
            PBFT<PBFTBlock, ?> p = pbft(n);
            total += p.getAccountedVoteBytes();
            consenso += p.getAccountedConsensusBytes();
            NetworkHeightLedger l = p.getHeightLedger();
            semAltura += l.getUnattributedBytes();
            for (NetworkHeightLedger.Cell c : l.heights().values()) {
                ledgerTotal += c.getTotalBytes();
                ledgerConsenso += c.getConsensusBytes();
                if (c.isFinalized()) {
                    finalizadas++;
                }
            }
        }
        assertTrue(total > 0 && finalizadas > 0, "a corrida mediu trafego e finalizou alturas");
        assertEquals(total, ledgerTotal + semAltura, "C_B_total: o ledger por altura + sem altura fecha com o livro do PBFT");
        assertEquals(consenso, ledgerConsenso, "C_B_consenso: o ledger fecha com o livro do PBFT (nada alem do teto numa corrida honesta)");
        assertEquals(0L, semAltura, "corrida honesta: nada alem do teto");
    }

    @Test
    void trafegoAlemDoTetoNaoCriaCelulaPorAltura() {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        long teto = 10L;
        net.populateNetwork(sim, 4, new PBFTConsensusConfig(teto));
        PBFTNode no = net.getAllNodes().get(0);
        PBFT<PBFTBlock, ?> p = pbft(no);
        PBFTBlock longe = new PBFTBlock(100, (int) teto + 1, 0.0, net.getAllNodes().get(1), PBFT_GENESIS_BLOCK);
        PBFTBlock perto = new PBFTBlock(100, 1, 0.0, net.getAllNodes().get(1), PBFT_GENESIS_BLOCK);
        p.addBlockTraffic(longe, 77);
        p.addConsensusMessageTraffic(longe, 55);
        assertEquals(0, p.getHeightLedger().heights().size(), "alem do teto: nenhuma celula por altura");
        assertEquals(77L, p.getHeightLedger().getUnattributedBytes(), "o byte vai ao contador sem altura (nao some)");
        p.addBlockTraffic(perto, 10);
        p.addConsensusMessageTraffic(perto, 4);
        NetworkHeightLedger.Cell c = p.getHeightLedger().heights().get(1L);
        assertNotNull(c);
        assertEquals(10L, c.getTotalBytes());
        assertEquals(4L, c.getConsensusBytes());
    }
}
