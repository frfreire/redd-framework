package jabs.consensus.algorithm;

import jabs.consensus.config.TangleIOTAConsensusConfig;
import jabs.ledgerdata.tangle.TangleBlock;
import jabs.network.networks.iota.TangleGlobalNetwork;
import jabs.network.node.nodes.iota.IOTANode;
import jabs.network.p2p.BitcoinCoreP2P;
import jabs.network.stats.NodeGlobalNetworkStats;
import jabs.network.stats.sixglobalregions.SixRegions;
import jabs.network.stats.sixglobalregions.iota.IOTANodeDistribution6Region;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 5 (debito 7), parte do TangleIOTA: o genesis de IOTANode tinha {@code parents == null} e a
 * construcao dava NullPointerException na baseline. Cobre SO isso. O Tangle continua sem cenario executavel
 * (declarado no relatorio da tarefa): IOTANode nao gera blocos, ninguem chama confirmBlock, e
 * TangleGlobalNetwork#createNewIOTANode usa nodeTypes.get(regiao) (a chave do mapa e o no, nao a regiao).
 */
class IOTANodeGenesisTest {

    static final class Stats extends IOTANodeDistribution6Region implements NodeGlobalNetworkStats<SixRegions> {
        Stats(RandomnessEngine r) {
            super(r);
        }
    }

    @Test
    void genesisTemListaDePaisVaziaENaoNula() {
        TangleBlock g = IOTANode.TANGLE_GENESIS_BLOCK;
        assertNotNull(g.getParents(), "genesis sem pai: lista vazia, nunca null");
        assertTrue(g.getParents().isEmpty());
        assertEquals(0, g.getHeight());
    }

    @Test
    void iotaNodeConstroiSemNullPointerException() {
        RandomnessEngine rng = new RandomnessEngine(1);
        TangleGlobalNetwork<SixRegions> net = new TangleGlobalNetwork<>(rng, new Stats(rng));
        IOTANode no = assertDoesNotThrow(() -> new IOTANode(new Simulator(), net, 0, 10_000_000L, 10_000_000L,
                new BitcoinCoreP2P(), new TangleIOTAConsensusConfig(1.0)));
        assertTrue(no.getConsensusAlgorithm() instanceof TangleIOTA);
        assertEquals(1, no.getConsensusAlgorithm().getLocalBlockDAG().size(),
                "o DAG local nasce com o genesis");
    }
}
