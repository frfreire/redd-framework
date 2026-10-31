package jabs.consensus.algorithm;

import jabs.consensus.config.CasperFFGConfig;
import jabs.ledgerdata.casper.CasperFFGLink;
import jabs.ledgerdata.casper.CasperFFGVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.ledgerdata.ethereum.EthereumTx;
import jabs.network.networks.ethereum.CasperFFGGlobalBlockchainNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.ethereum.EthereumNode;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tarefa C1-casper-quorum-correcao (loop 2, criterio 3): o limiar de um supermajority link do Casper FFG e
 * {@code votos >= ceil(2S/3)} (peso unitario), nao {@code votos > ((S/3)*2)+1}.
 * Duas camadas: (1) a formula pura {@link CasperFFG#supermajorityThreshold(int)} contra uma referencia
 * independente (aritmetica exata em {@code long}) e contra os valores antigos; (2) fronteira ponta a ponta:
 * votos distintos injetados em {@code newIncomingVote}, {@code limiar-1} nao justifica nem finaliza e
 * exatamente {@code limiar} faz as duas coisas.
 */
class CasperFFGQuorumTest {
    private static final int CHECKPOINT = 2;
    private static final int MINERS = 3;

    /**
     * Limiar antigo, mantido so para documentar a diferenca: {@code votos > T} com {@code T = (S/3)*2 + 1}, isto e,
     * {@code T + 1 = (S/3)*2 + 2} votos. Frente a {@code ceil(2S/3)}: S=3k, dois a mais; S=3k+1, um a mais;
     * S=3k+2, igual.
     */
    private static int limiarAntigo(int s) {
        return (((s / 3) * 2) + 1) + 1;
    }

    // ---------- formula ----------

    @Test
    void formulaNosValoresDaTarefa() {
        // S -> (teorico ceil(2S/3), exigido pelo codigo antigo)
        int[][] casos = {{4, 3, 4}, {7, 5, 6}, {10, 7, 8}, {13, 9, 10}, {16, 11, 12}, {64, 43, 44}, {128, 86, 86}};
        for (int[] c : casos) {
            assertEquals(c[1], CasperFFG.supermajorityThreshold(c[0]), "S=" + c[0]);
            assertEquals(c[2], limiarAntigo(c[0]), "documenta o limiar antigo, S=" + c[0]);
        }
    }

    @Test
    void formulaEIgualAoTetoDeDoisTercosParaTodoS() {
        for (int s = 1; s <= 2000; s++) {
            // referencia independente: menor k inteiro com 3k >= 2s
            int k = 0;
            while (3L * k < 2L * s) k++;
            assertEquals(k, CasperFFG.supermajorityThreshold(s), "S=" + s);
            // supermajority link: k votos somam >= 2/3 do peso; k-1 nao
            assertTrue(3L * k >= 2L * s, "S=" + s);
            assertTrue(3L * (k - 1) < 2L * s, "S=" + s);
        }
    }

    @Test
    void formulaNaoEstouraEmSGrande() {
        int s = Integer.MAX_VALUE;
        int k = CasperFFG.supermajorityThreshold(s);
        assertTrue(3L * k >= 2L * s);
        assertTrue(3L * (k - 1) < 2L * s);
    }

    @Test
    void formulaRejeitaSMenorQueUm() {
        assertThrows(IllegalArgumentException.class, () -> CasperFFG.supermajorityThreshold(0));
        assertThrows(IllegalArgumentException.class, () -> CasperFFG.supermajorityThreshold(-5));
    }

    // ---------- fronteira ponta a ponta ----------

    /** Rede Casper com MINERS mineradores + s stakeholders, no observado e cadeia genesis->b1->b2. */
    private static final class Cena {
        final Simulator sim = new Simulator();
        final RandomnessEngine rng = new RandomnessEngine(11);
        final CasperFFGGlobalBlockchainNetwork<?> net;
        final EthereumNode r;
        final CasperFFG<EthereumBlock, EthereumTx> casper;
        final EthereumBlock g, b1, b2;
        final List<? extends Node> nos;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Cena(int s) {
            net = new CasperFFGGlobalBlockchainNetwork<>(rng, CHECKPOINT,
                    new EthereumProofOfWorkGlobalNetworkStats6Regions(rng));
            net.populateNetwork(sim, MINERS, s, new CasperFFGConfig(
                    EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022), 15.0, CHECKPOINT, s));
            nos = net.getAllNodes();
            r = net.getAllNodes().get(MINERS);
            casper = (CasperFFG<EthereumBlock, EthereumTx>) r.getConsensusAlgorithm();
            g = casper.getLocalBlockTree().getGenesisBlock();
            b1 = new EthereumBlock(100, 1, 1, null, g, new HashSet<>(), 1.0, 1.0);
            b2 = new EthereumBlock(100, 2, 2, null, b1, new HashSet<>(), 1.0, 1.0);
            // sem newIncomingBlock: nao queremos que o no vote por conta propria (updateChain)
            casper.getLocalBlockTree().add(b1);
            casper.getLocalBlockTree().add(b2);
        }

        /** Link b1 (a finalizar) -> b2 (a justificar): distinto do genesis, que ja nasce finalizado. */
        CasperFFGLink<EthereumBlock> link() {
            return new CasperFFGLink<>(b1, b2);
        }

        /** Voto do votante {@code i} (todos distintos entre si). */
        void vota(int i) {
            casper.newIncomingVote(new CasperFFGVote<>(nos.get(i), link()));
        }
    }

    private static void fronteira(int s) {
        int limiar = CasperFFG.supermajorityThreshold(s);
        Cena c = new Cena(s);
        assertEquals(limiar, c.casper.getRequiredVotes(), "o no usa a formula pura, S=" + s);
        assertTrue(c.nos.size() >= limiar, "ha votantes distintos suficientes, S=" + s);
        assertFalse(c.casper.isBlockJustified(c.b2));
        assertFalse(c.casper.isBlockFinalized(c.b1));

        for (int i = 0; i < limiar - 1; i++) c.vota(i);
        assertEquals(limiar - 1, c.casper.getVoteCount(c.link()), "S=" + s);
        assertFalse(c.casper.isBlockJustified(c.b2), "S=" + s + ": " + (limiar - 1) + " votos nao justificam");
        assertFalse(c.casper.isBlockFinalized(c.b1), "S=" + s + ": " + (limiar - 1) + " votos nao finalizam");

        c.vota(limiar - 1);
        assertEquals(limiar, c.casper.getVoteCount(c.link()), "S=" + s);
        assertTrue(c.casper.isBlockJustified(c.b2), "S=" + s + ": exatamente " + limiar + " votos justificam");
        assertTrue(c.casper.isBlockFinalized(c.b1), "S=" + s + ": exatamente " + limiar + " votos finalizam");
    }

    @Test
    void s4Exige3() {
        fronteira(4);
    }

    @Test
    void s7Exige5() {
        fronteira(7);
    }

    @Test
    void s10Exige7() {
        fronteira(10);
    }

    @Test
    void s13Exige9() {
        fronteira(13);
    }

    @Test
    void s16Exige11() {
        fronteira(16);
    }

    @Test
    void s64Exige43() {
        fronteira(64);
    }

    @Test
    void s128Exige86() {
        fronteira(128);
    }

    @Test
    void sMultiploDeTresAntigoExigiaDoisVotosAMais() {
        // S = 3k: teorico 2k, antigo 2k+2 (dois a mais). S=9: 6 contra 8; 12: 8 contra 10; 15: 10 contra 12.
        int[][] casos = {{9, 6, 8}, {12, 8, 10}, {15, 10, 12}};
        for (int[] c : casos) {
            assertEquals(c[1], CasperFFG.supermajorityThreshold(c[0]), "novo, S=" + c[0]);
            assertEquals(c[2], limiarAntigo(c[0]), "antigo, S=" + c[0]);
            assertEquals(2, c[2] - c[1], "S=3k: antigo exigia dois votos a mais, S=" + c[0]);
            fronteira(c[0]);
        }
    }

    @Test
    void diferencaAntigoNovoPorRestoDeSPorTres() {
        // S=3k: +2; S=3k+1: +1; S=3k+2: 0 (S=9: 8/6; S=10: 8/7; S=11: 8/8)
        for (int s = 3; s <= 300; s++) {
            int esperado = switch (s % 3) {
                case 0 -> 2;
                case 1 -> 1;
                default -> 0;
            };
            assertEquals(esperado, limiarAntigo(s) - CasperFFG.supermajorityThreshold(s), "S=" + s);
        }
        assertEquals(8, limiarAntigo(9));
        assertEquals(6, CasperFFG.supermajorityThreshold(9));
        assertEquals(8, limiarAntigo(10));
        assertEquals(7, CasperFFG.supermajorityThreshold(10));
        assertEquals(8, limiarAntigo(11));
        assertEquals(8, CasperFFG.supermajorityThreshold(11));
    }

    @Test
    void sNaoMultiploDeTresArredondaPraCima() {
        // S = 3m+1 e 3m+2: 2S/3 nao e inteiro; o teto vale (11 -> 8, 14 -> 10, 20 -> 14, 22 -> 15)
        for (int s : new int[]{11, 14, 20, 22}) {
            fronteira(s);
        }
        assertEquals(8, CasperFFG.supermajorityThreshold(11));
        assertEquals(10, CasperFFG.supermajorityThreshold(14));
        assertEquals(14, CasperFFG.supermajorityThreshold(20));
        assertEquals(15, CasperFFG.supermajorityThreshold(22));
    }

    @Test
    void votoRepetidoDoMesmoVotanteNaoContaDuasVezes() {
        Cena c = new Cena(10);
        int limiar = CasperFFG.supermajorityThreshold(10);
        for (int i = 0; i < limiar - 1; i++) {
            c.vota(i);
            c.vota(i);
        }
        assertEquals(limiar - 1, c.casper.getVoteCount(c.link()));
        assertFalse(c.casper.isBlockFinalized(c.b1), "2*(limiar-1) mensagens de limiar-1 votantes nao finalizam");
        c.vota(limiar - 1);
        assertTrue(c.casper.isBlockFinalized(c.b1));
    }
}
