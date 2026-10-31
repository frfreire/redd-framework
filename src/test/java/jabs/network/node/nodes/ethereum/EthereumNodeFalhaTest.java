package jabs.network.node.nodes.ethereum;

import jabs.consensus.algorithm.Duples;
import jabs.consensus.algorithm.EnvioDeTeste;
import jabs.consensus.algorithm.FalhaInjetada;
import jabs.consensus.algorithm.GuardaCatch;
import jabs.consensus.config.GhostProtocolConfig;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.message.InvMessage;
import jabs.network.networks.ethereum.EthereumGlobalProofOfWorkNetwork;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.event.BlockProposalEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterio 4 nos dois pontos do Ethereum que o Casper/Parlia usam e que tinham {@code catch (Exception ignored)}:
 * o evento de proposta em {@link EthereumMinerNode#generateNewBlock()} e o registro de submissao em
 * {@link EthereumNode#generateNewTransaction()}. Falha do simulador PROPAGA; o fluxo normal segue igual.
 */
class EthereumNodeFalhaTest {
    private static final class Cena {
        final Duples.SimuladorQueFalha sim = new Duples.SimuladorQueFalha();
        final RandomnessEngine rng = new RandomnessEngine(9);
        final EthereumMinerNode miner;
        final EnvioDeTeste envio;

        Cena() {
            EthereumGlobalProofOfWorkNetwork<?> net = new EthereumGlobalProofOfWorkNetwork<>(rng,
                    new EthereumProofOfWorkGlobalNetworkStats6Regions(rng));
            net.populateNetwork(sim, 4, 8, new GhostProtocolConfig<>(
                    EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022), 15.0));
            miner = (EthereumMinerNode) net.getAllNodes().get(1);
            envio = EnvioDeTeste.instala(miner, sim, net, rng);
        }
    }

    @Test
    void falhaAoAgendarOEventoDeProposaPropaga() {
        Cena c = new Cena();
        c.sim.falhar = true; // putEvent(BlockProposalEvent) falha. Antes: catch (Exception ignored) engolia.
        FalhaInjetada f = assertThrows(FalhaInjetada.class, c.miner::generateNewBlock);
        assertTrue(f.getMessage().contains("BlockProposalEvent"), f.getMessage());
    }

    @Test
    void controleSemFalhaGeraOBlocoEOEventoDeProposta() throws Exception {
        Cena c = new Cena();
        c.miner.generateNewBlock();
        long propostas = Duples.pendentes(c.sim).stream().filter(e -> e instanceof BlockProposalEvent).count();
        assertEquals(1, propostas);
        assertEquals(2, c.miner.getConsensusAlgorithm().getLocalBlockTree().size(),
                "o bloco gerado entrou na arvore local (genesis + 1)");
    }

    @Test
    void falhaAoPropagarATransacaoGeradaPropaga() {
        Cena c = new Cena();
        c.envio.falharQuando = p -> p.getMessage() instanceof jabs.network.message.DataMessage
                || p.getMessage() instanceof InvMessage;
        assertThrows(FalhaInjetada.class, c.miner::generateNewTransaction);
    }

    @Test
    void ethereumNodeEMinerNaoTemCatchDeExceptionVazioNemIgnored() throws IOException {
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/network/node/nodes/ethereum/EthereumNode.java"));
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/network/node/nodes/ethereum/EthereumMinerNode.java"));
    }

    @Test
    void abstractConsensusAlgorithmNaoTemCatchDeExceptionVazioNemIgnored() throws IOException {
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/consensus/algorithm/AbstractConsensusAlgorithm.java"));
    }
}
