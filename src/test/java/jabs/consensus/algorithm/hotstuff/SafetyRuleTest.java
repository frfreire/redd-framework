package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.algorithm.hotstuff.Fixtures.Tree;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** safeNode: extensao do bloco travado (seguranca) e QC de visao maior (vivacidade), em cadeias de varias alturas. */
class SafetyRuleTest {

    private final Tree tree = new Tree();
    private final HotStuffBlock g = tree.genesis();
    /** Cadeia principal b1..b8 (alturas 1..8). */
    private final List<HotStuffBlock> main = tree.chain(g, 8);
    /** Ramo concorrente a partir de b2: f3..f6 (alturas 3..6). */
    private final List<HotStuffBlock> fork = tree.chain(main.get(1), 4);

    private Optional<HotStuffQuorumCertificate> locked(HotStuffBlock b, long view) {
        return Optional.of(Fixtures.qc(Phase.PRE_COMMIT, view, b));
    }

    private Optional<HotStuffQuorumCertificate> proposalQc(HotStuffBlock b, long view) {
        return Optional.of(Fixtures.qc(Phase.PREPARE, view, b));
    }

    @Test
    void semLockedAceitaQualquerProposta() {
        assertTrue(SafetyRule.safeNode(fork.get(3), Optional.empty(), Optional.empty(), tree));
        assertTrue(SafetyRule.safeNode(main.get(0), proposalQc(g, 0), Optional.empty(), tree));
    }

    @Test
    void estendeOLockedAceitaMesmoComQcAntigoOuAusente() {
        Optional<HotStuffQuorumCertificate> lk = locked(main.get(2), 5); // b3
        for (int h = 3; h < 8; h++) { // b4..b8 estendem b3
            assertTrue(SafetyRule.safeNode(main.get(h), Optional.empty(), lk, tree), "b" + (h + 1));
            assertTrue(SafetyRule.safeNode(main.get(h), proposalQc(main.get(h - 1), 1), lk, tree), "b" + (h + 1));
        }
    }

    @Test
    void oProprioBlocoTravadoContaComoEstendido() {
        assertTrue(SafetyRule.safeNode(main.get(2), Optional.empty(), locked(main.get(2), 5), tree));
    }

    @Test
    void naoEstendeMasQcDeVisaoMaiorAceita() {
        Optional<HotStuffQuorumCertificate> lk = locked(main.get(4), 5); // b5, visao 5
        assertTrue(SafetyRule.safeNode(fork.get(3), proposalQc(main.get(1), 6), lk, tree));
        assertTrue(SafetyRule.safeNode(fork.get(3), proposalQc(main.get(1), 100), lk, tree));
    }

    @Test
    void naoEstendeEVisaoIgualRecusa() {
        Optional<HotStuffQuorumCertificate> lk = locked(main.get(4), 5);
        assertFalse(SafetyRule.safeNode(fork.get(3), proposalQc(main.get(1), 5), lk, tree));
    }

    @Test
    void naoEstendeEVisaoMenorRecusa() {
        Optional<HotStuffQuorumCertificate> lk = locked(main.get(4), 5);
        assertFalse(SafetyRule.safeNode(fork.get(3), proposalQc(main.get(1), 4), lk, tree));
        assertFalse(SafetyRule.safeNode(fork.get(3), proposalQc(main.get(1), 0), lk, tree));
    }

    @Test
    void naoEstendeESemQcDaPropostaRecusa() {
        assertFalse(SafetyRule.safeNode(fork.get(3), Optional.empty(), locked(main.get(4), 5), tree));
    }

    @Test
    void propostaMaisBaixaQueOLockedNaoEstende() {
        Optional<HotStuffQuorumCertificate> lk = locked(main.get(5), 5); // b6
        assertFalse(SafetyRule.safeNode(main.get(3), Optional.empty(), lk, tree)); // b4 e ancestral, nao descendente
        assertFalse(SafetyRule.safeNode(g, Optional.empty(), lk, tree));
    }

    @Test
    void ramoConcorrenteNaMesmaAlturaNaoEstende() {
        // fork f3 (altura 3) e b3 (altura 3): mesma altura, referencias distintas
        assertFalse(SafetyRule.safeNode(fork.get(0), Optional.empty(), locked(main.get(2), 5), tree));
        assertTrue(SafetyRule.safeNode(fork.get(3), Optional.empty(), locked(main.get(1), 5), tree)); // ancestral comum b2
    }

    @Test
    void paiDesconhecidoNaArvoreLocalNaoEstende() {
        tree.forget(main.get(5)); // b6 sem pai conhecido
        assertFalse(SafetyRule.safeNode(main.get(7), Optional.empty(), locked(main.get(2), 5), tree));
    }

    @Test
    void extendsBlockEhReflexivoETransitivo() {
        assertTrue(SafetyRule.extendsBlock(main.get(7), g, tree));
        assertTrue(SafetyRule.extendsBlock(main.get(7), main.get(7), tree));
        assertFalse(SafetyRule.extendsBlock(g, main.get(0), tree));
    }

    @Test
    void argumentosNulosLancam() {
        Optional<HotStuffQuorumCertificate> vazio = Optional.empty();
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.safeNode(null, vazio, vazio, tree));
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.safeNode(g, null, vazio, tree));
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.safeNode(g, vazio, null, tree));
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.safeNode(g, vazio, vazio, null));
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.extendsBlock(null, g, tree));
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.extendsBlock(g, null, tree));
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.extendsBlock(g, g, null));
    }

    @Test
    void arvoreComPaiDeAlturaNaoMenorLanca() {
        tree.force(main.get(5), main.get(6)); // pai mais alto que o filho
        assertThrows(IllegalArgumentException.class,
                () -> SafetyRule.extendsBlock(main.get(5), g, tree));
        tree.force(main.get(5), main.get(5)); // pai de si mesmo: loop infinito se nao fosse checado
        assertThrows(IllegalArgumentException.class,
                () -> SafetyRule.extendsBlock(main.get(5), g, tree));
    }

    @Test
    void arvoreQueDevolveNullLanca() {
        ParentLookup ruim = b -> null;
        assertThrows(IllegalArgumentException.class, () -> SafetyRule.extendsBlock(main.get(2), g, ruim));
    }
}
