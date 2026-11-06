package jabs.consensus.algorithm.tendermint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TendermintQuorumAndTimeoutsTest {

    @Test
    void limiaresConhecidos() {
        assertEquals(3, new TendermintQuorum(4).strong());
        assertEquals(2, new TendermintQuorum(4).weak());
        assertEquals(4, new TendermintQuorum(5).strong());
        assertEquals(5, new TendermintQuorum(6).strong());
        assertEquals(5, new TendermintQuorum(7).strong());
        assertEquals(7, new TendermintQuorum(10).strong());
        assertEquals(34, new TendermintQuorum(50).strong());
        assertEquals(17, new TendermintQuorum(50).weak());
        assertEquals(267, new TendermintQuorum(400).strong());
    }

    @Test
    void coincideCom2fMais1QuandoNE3fMais1() {
        for (int f = 1; f <= 133; f++) {
            int n = 3 * f + 1;
            assertEquals(2 * f + 1, new TendermintQuorum(n).strong(), "n=" + n);
            assertEquals(f + 1, new TendermintQuorum(n).weak(), "n=" + n);
            assertEquals(TendermintQuorum.legacyTwoFPlusOne(n), new TendermintQuorum(n).strong());
        }
    }

    /** Dois quoruns fortes sempre se cruzam em ao menos f+1 nos (logo em um correto), para todo n. */
    @Test
    void doisQuorunsSeCruzamEmUmCorretoParaTodoN() {
        for (int n = 1; n <= 400; n++) {
            TendermintQuorum q = new TendermintQuorum(n);
            assertTrue(2 * q.strong() - n >= q.maxFaulty() + 1, "n=" + n);
            assertTrue(q.strong() <= n - q.maxFaulty(), "quorum alcancavel sem os f bizantinos, n=" + n);
            assertTrue(q.weak() >= q.maxFaulty() + 1, "f+1 contem um correto, n=" + n);
        }
    }

    /** Documenta a divergencia: o 2f+1 de PBFT/HotStuff NAO garante o cruzamento quando n != 3f+1. */
    @Test
    void legado2fMais1FalhaOCruzamentoForaDe3fMais1() {
        int n = 5; // f = 1, 2f+1 = 3: dois quoruns de 3 em 5 se cruzam em 1 no, que pode ser o bizantino
        int legacy = TendermintQuorum.legacyTwoFPlusOne(n);
        assertEquals(3, legacy);
        assertTrue(2 * legacy - n < new TendermintQuorum(n).maxFaulty() + 1);
        assertEquals(33, TendermintQuorum.legacyTwoFPlusOne(50));
    }

    @Test
    void timeoutsCrescemComORound() {
        TendermintTimeouts t = new TendermintTimeouts(3.0, 0.5, 1.0, 0.5, 1.0, 0.5);
        assertEquals(3.0, t.durationSeconds(TimeoutKind.PROPOSE, 0), 1e-12);
        assertEquals(4.0, t.durationSeconds(TimeoutKind.PROPOSE, 2), 1e-12);
        assertEquals(2.5, t.durationSeconds(TimeoutKind.PREVOTE, 3), 1e-12);
        assertEquals(1.5, t.durationSeconds(TimeoutKind.PRECOMMIT, 1), 1e-12);
    }

    @Test
    void timeoutsInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new TendermintTimeouts(0, 0, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TendermintTimeouts(1, -1, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TendermintTimeouts(1, 0, Double.NaN, 0, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TendermintTimeouts(1, 0, 1, 0, Double.POSITIVE_INFINITY, 0));
        TendermintTimeouts t = new TendermintTimeouts(1, 0, 1, 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> t.durationSeconds(TimeoutKind.PROPOSE, -1));
        assertThrows(IllegalArgumentException.class, () -> t.durationSeconds(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new TendermintQuorum(0));
    }
}
