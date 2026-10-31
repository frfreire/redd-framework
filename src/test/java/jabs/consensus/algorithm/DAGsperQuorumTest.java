package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.DAGsperConfig;
import jabs.ledgerdata.dagsper.DAGsperVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.node.nodes.Node;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 4, rodada 4 (criterio 3): quorum teorico 2f+1 (f = floor((n-1)/3)) com {@code >=} no DAGsper,
 * na justificacao e na finalizacao. Antes: {@code > (n/3)*2+1}, que exigia 2f+2 (n=4 -> 4; n=7 -> 6; n=10 -> 8).
 *
 * Os votos sao injetados direto em {@link DAGsper#newIncomingVote} (um unico no receptor, sem rede), com votantes
 * distintos. A rede P2P do DAGsper nao termina com n < 10; por isso os votantes saem de um harness de 10 nos e
 * {@code numOfStakeholders} (n do quorum) e passado a mao na config: o limiar depende so desse n.
 */
class DAGsperQuorumTest {

    private static final int NOS_DO_HARNESS = 10;

    private record Cenario(DAGsper<EthereumBlock, ?> dag, EthereumBlock b1, EthereumBlock b2, List<Node> votantes) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Cenario novo(int n) {
        DAGsperHarness h = new DAGsperHarness(1, 3, NOS_DO_HARNESS - 3, 3, 15);
        EthereumBlock genesis = EthereumBlock.generateGenesisBlock(2097);
        LocalBlockTree<EthereumBlock> arvore = new LocalBlockTree<>(genesis);
        EthereumBlock b1 = new EthereumBlock(1_000, 1, 1.0, null, genesis, new HashSet<>(), 2097, 1.0);
        EthereumBlock b2 = new EthereumBlock(1_000, 2, 2.0, null, b1, new HashSet<>(), 2097, 1.0);
        arvore.add(b1);
        arvore.add(b2);
        DAGsperConfig<EthereumBlock> cfg = new DAGsperConfig<>(genesis, 15, 3, n);
        DAGsper<EthereumBlock, ?> dag = new DAGsper(arvore, cfg);
        List<Node> votantes = h.todos.stream().map(x -> (Node) x).toList();
        return new Cenario(dag, b1, b2, votantes);
    }

    /** Voto de finalizacao em b1: toBeJustified = toBeFinalized = b1, ultimo finalizado = genesis. */
    private static void votoFinalizacao(Cenario c, int votante) {
        c.dag().newIncomingVote(new DAGsperVote<>(c.votantes().get(votante), 0, 1, c.b1(), new HashSet<>()));
    }

    /** Voto de justificacao em b2: toBeJustified = b2, toBeFinalized = b1 (caminho de justificacao = [b2]). */
    private static void votoJustificacao(Cenario c, int votante) {
        c.dag().newIncomingVote(new DAGsperVote<>(c.votantes().get(votante), 0, 1, c.b2(), new HashSet<>()));
    }

    // ---------- formula ----------

    @Test
    void formulaDoQuorum() {
        assertEquals(1, DAGsper.maxFaultyNodes(4));
        assertEquals(3, DAGsper.quorumSize(4));
        assertEquals(2, DAGsper.maxFaultyNodes(7));
        assertEquals(5, DAGsper.quorumSize(7));
        assertEquals(3, DAGsper.maxFaultyNodes(10));
        assertEquals(7, DAGsper.quorumSize(10));
        // n = 3f+2 e 3f+3 nao mudam f.
        assertEquals(3, DAGsper.quorumSize(5));
        assertEquals(3, DAGsper.quorumSize(6));
        assertEquals(7, DAGsper.quorumSize(11));
        assertEquals(9, DAGsper.quorumSize(13));
    }

    @Test
    void construtorAplicaAFormula() {
        for (int n : new int[]{4, 7, 10, 13}) {
            assertEquals(DAGsper.quorumSize(n), novo(n).dag().getRequiredVotes(), "n=" + n);
        }
    }

    // ---------- limiar exato: finalizacao ----------

    private static void finalizacaoLimiarExato(int n, int quorum) {
        Cenario c = novo(n);
        for (int i = 0; i < quorum - 1; i++) votoFinalizacao(c, i);
        assertFalse(c.dag().isBlockFinalized(c.b1()), "n=" + n + ": " + (quorum - 1) + " votos nao finalizam");
        votoFinalizacao(c, quorum - 1);
        assertTrue(c.dag().isBlockFinalized(c.b1()), "n=" + n + ": exatamente " + quorum + " votos finalizam");
    }

    @Test
    void finalizacaoN4Exige3() {
        finalizacaoLimiarExato(4, 3);
    }

    @Test
    void finalizacaoN7Exige5() {
        finalizacaoLimiarExato(7, 5);
    }

    @Test
    void finalizacaoN10Exige7() {
        finalizacaoLimiarExato(10, 7);
    }

    // ---------- limiar exato: justificacao ----------

    private static void justificacaoLimiarExato(int n, int quorum) {
        Cenario c = novo(n);
        for (int i = 0; i < quorum - 1; i++) votoJustificacao(c, i);
        assertFalse(c.dag().isBlockJustified(c.b2()), "n=" + n + ": " + (quorum - 1) + " votos nao justificam");
        votoJustificacao(c, quorum - 1);
        assertTrue(c.dag().isBlockJustified(c.b2()), "n=" + n + ": exatamente " + quorum + " votos justificam");
    }

    @Test
    void justificacaoN4Exige3() {
        justificacaoLimiarExato(4, 3);
    }

    @Test
    void justificacaoN7Exige5() {
        justificacaoLimiarExato(7, 5);
    }

    @Test
    void justificacaoN10Exige7() {
        justificacaoLimiarExato(10, 7);
    }

    // ---------- votos distintos ----------

    @Test
    void votoRepetidoDoMesmoNoNaoContaDuasVezes() {
        Cenario c = novo(4);
        votoFinalizacao(c, 0);
        votoFinalizacao(c, 0);
        votoFinalizacao(c, 1);
        votoFinalizacao(c, 1);
        assertFalse(c.dag().isBlockFinalized(c.b1()), "2 nos distintos (4 mensagens) nao formam quorum 3");
        votoFinalizacao(c, 2);
        assertTrue(c.dag().isBlockFinalized(c.b1()));
    }

    // ---------- higiene ----------

    @Test
    void naoRestaComparacaoEstritaContraFormulaAntiga() throws Exception {
        String codigo = DAGsperTrafficTest.semComentarios("src/main/java/jabs/consensus/algorithm/DAGsper.java");
        assertFalse(codigo.contains("/ 3) * 2) + 1"), "formula (n/3)*2+1 removida");
        assertFalse(codigo.matches("(?s).*size\\(\\)\\s*>\\s*requiredVotes.*"), "sem '>' contra o quorum");
    }
}
