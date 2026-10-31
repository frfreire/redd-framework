package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.consensus.config.ChainBasedConsensusConfig;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.ledgerdata.ethereum.EthereumTx;
import jabs.network.networks.bsc.ParliaGlobalBlockchainNetwork;
import jabs.network.node.nodes.MinerNode;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.ethereum.EthereumMinerNode;
import jabs.network.node.nodes.ethereum.EthereumNode;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.event.Event;
import jabs.simulator.event.VoteDeliveryEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterio 4 (modelo de falha explicito) no Parlia: {@code scheduleVote} e {@code createEquivocationBlock} nao
 * engolem excecao (antes: {@code catch (Exception ignored)}). Prova a propagacao com falha tipada injetada e fixa a
 * semantica que NAO mudou, incluindo a LACUNA (alt == null: EQUIVOCATION vira voto honesto, DOUBLE_SIGN repete o voto).
 */
class ParliaByzantinoTest {
    private static final int MINERS = 4;
    private static final int STAKEHOLDERS = 6;

    private static final class Cena {
        final Duples.SimuladorQueFalha sim = new Duples.SimuladorQueFalha();
        final Duples.RngQueFalha rng = new Duples.RngQueFalha(11);
        final ParliaGlobalBlockchainNetwork<?> net;
        final List<EthereumMinerNode> validadores = new ArrayList<>();
        final EthereumMinerNode v0; // proponente da altura 1 (turnLength 3)
        final Parlia<EthereumBlock, EthereumTx> parlia;
        final EthereumBlock genesis;

        @SuppressWarnings("unchecked")
        Cena() {
            net = new ParliaGlobalBlockchainNetwork<>(rng, new EthereumProofOfWorkGlobalNetworkStats6Regions(rng), 3, 21);
            genesis = EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022);
            net.populateNetwork(sim, MINERS, STAKEHOLDERS, new ChainBasedConsensusConfig(genesis, 3.0));
            for (MinerNode m : net.getAllMiners()) validadores.add((EthereumMinerNode) m);
            for (EthereumMinerNode a : validadores) {
                Parlia<EthereumBlock, EthereumTx> pa = (Parlia<EthereumBlock, EthereumTx>) a.getConsensusAlgorithm();
                for (EthereumMinerNode b : validadores) pa.addValidator(b);
            }
            v0 = validadores.get(0);
            parlia = (Parlia<EthereumBlock, EthereumTx>) validadores.get(2).getConsensusAlgorithm();
        }

        void bizantinos(String ataque, int... ids) {
            Set<Integer> s = new HashSet<>();
            for (int i : ids) s.add(i);
            parlia.setByzantineConfig(new ByzantineConfig(MINERS + STAKEHOLDERS, s, ataque, 11));
        }

        EthereumBlock blocoAltura1() {
            return new EthereumBlock(100, 1, 1.0, v0, genesis, new HashSet<>(), 1.0, 1.0);
        }

        /** Votos agendados na fila (eventos VoteDeliveryEvent), sem executa-los. */
        int votosAgendados() throws ReflectiveOperationException {
            int n = 0;
            for (Event e : Duples.pendentes(sim)) if (e instanceof VoteDeliveryEvent) n++;
            return n;
        }
    }

    // ---------- semantica preservada ----------

    @Test
    void semAtaqueUmVotoPorValidador() throws Exception {
        Cena c = new Cena();
        c.parlia.newIncomingBlock(c.blocoAltura1());
        assertEquals(MINERS, c.votosAgendados());
    }

    @Test
    void equivocationComAlternativaAgendaUmVotoExtraNoBlocoAlternativo() throws Exception {
        Cena c = new Cena();
        c.bizantinos("EQUIVOCATION", c.validadores.get(1).nodeID);
        c.parlia.newIncomingBlock(c.blocoAltura1());
        assertEquals(MINERS + 1, c.votosAgendados(), "1 voto honesto por validador + 1 voto do bizantino no bloco alternativo");
    }

    @Test
    void silentEWithholdNaoAgendamVotoDoBizantino() throws Exception {
        for (String ataque : new String[]{"SILENT", "WITHHOLD"}) {
            Cena c = new Cena();
            c.bizantinos(ataque, c.validadores.get(1).nodeID);
            c.parlia.newIncomingBlock(c.blocoAltura1());
            assertEquals(MINERS - 1, c.votosAgendados(), ataque);
        }
    }

    // ---------- LACUNA registrada: alt == null nao muda a semantica (nao e falha) ----------

    @Test
    void semAlternativaEquivocationVotaHonestoEDoubleSignRepeteOVoto() throws Exception {
        // Validador que nao e EthereumMinerNode: createEquivocationBlock devolve null (sem fabrica de bloco alternativo).
        for (String ataque : new String[]{"EQUIVOCATION", "DOUBLE_SIGN"}) {
            Cena c = new Cena();
            EthereumNode naoMinerador = (EthereumNode) c.net.getAllNodes().get(MINERS); // primeiro nao minerador
            assertFalse(naoMinerador instanceof EthereumMinerNode);
            c.parlia.addValidator(naoMinerador);
            c.bizantinos(ataque, naoMinerador.nodeID);
            c.parlia.newIncomingBlock(c.blocoAltura1());
            int esperado = (MINERS + 1) + (ataque.equals("DOUBLE_SIGN") ? 1 : 0);
            assertEquals(esperado, c.votosAgendados(), ataque + " sem alternativa: honesto (+1 se DOUBLE_SIGN)");
        }
    }

    @Test
    void createEquivocationBlockDevolveNullSoNasCondicoesExplicitas() {
        Cena c = new Cena();
        EthereumBlock b = c.blocoAltura1();
        EthereumNode naoMinerador = (EthereumNode) c.net.getAllNodes().get(MINERS);
        assertNotNull(c.parlia.createEquivocationBlock(b, c.v0), "caso feliz: bloco com pai e validador minerador");
        assertNotSame(b, c.parlia.createEquivocationBlock(b, c.v0), "o alternativo e um bloco novo");
        assertNull(c.parlia.createEquivocationBlock(c.genesis, c.v0), "genesis: sem pai");
        assertNull(c.parlia.createEquivocationBlock(b, naoMinerador), "validador que nao minera");
        assertNull(c.parlia.createEquivocationBlock(b, null), "validador nulo");
    }

    // ---------- (criterio 4) falha atravessa o hook ----------

    @Test
    void falhaAoAgendarVotoPropaga() {
        Cena c = new Cena();
        c.sim.falhar = true; // scheduleVote -> putEvent falha. Antes: catch (Exception ignored) engolia e o voto sumia.
        assertThrows(FalhaInjetada.class, () -> c.parlia.newIncomingBlock(c.blocoAltura1()));
    }

    @Test
    void falhaNaCriacaoDoBlocoAlternativoPropaga() {
        Cena c = new Cena();
        c.bizantinos("EQUIVOCATION", c.validadores.get(1).nodeID);
        c.rng.falhar = true; // sampleExponentialDistribution (peso do bloco alternativo) falha
        assertThrows(FalhaInjetada.class, () -> c.parlia.newIncomingBlock(c.blocoAltura1()));
    }

    @Test
    void falhaDeCriacaoDoAlternativoPropagaTambemEmDoubleSign() {
        Cena c = new Cena();
        c.bizantinos("DOUBLE_SIGN", c.validadores.get(1).nodeID);
        c.rng.falhar = true;
        assertThrows(FalhaInjetada.class, () -> c.parlia.newIncomingBlock(c.blocoAltura1()));
    }

    @Test
    void controleSemFalhaInjetadaNaoLancaEAgendaOsVotos() throws Exception {
        Cena c = new Cena();
        c.bizantinos("EQUIVOCATION", c.validadores.get(1).nodeID);
        c.parlia.newIncomingBlock(c.blocoAltura1()); // mesma chamada dos testes de falha, so que sem falha injetada
        assertEquals(MINERS + 1, c.votosAgendados());
    }

    // ---------- guarda estatica ----------

    @Test
    void parliaNaoTemCatchDeExceptionVazioNemIgnored() throws IOException {
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/consensus/algorithm/Parlia.java"));
    }
}
