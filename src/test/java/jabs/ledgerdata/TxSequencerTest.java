package jabs.ledgerdata;

import jabs.ledgerdata.hotstuff.HotStuffTx;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato do {@link TxSequencer} (chave por valor das transacoes de UMA simulacao, sem hashCode de identidade).
 * O ponto central e o escopo: o contador e da instancia, nao estatico (um contador global quebraria o determinismo de
 * simulacoes que dividem a JVM).
 */
class TxSequencerTest {
    private static HotStuffTx tx() {
        return new HotStuffTx(512, 32);
    }

    @Test
    void numeraDeZeroNaOrdemDaPrimeiraVisaoEMantemOIdDaMesmaTx() {
        TxSequencer s = new TxSequencer();
        HotStuffTx a = tx();
        HotStuffTx b = tx();
        HotStuffTx c = tx();
        assertEquals(0L, s.idOf(a));
        assertEquals(1L, s.idOf(b));
        assertEquals(0L, s.idOf(a), "mesma tx, mesmo id");
        assertEquals(2L, s.idOf(c));
        assertEquals(1L, s.idOf(b));
        assertEquals(3L, s.assigned());
    }

    /** Falha se o contador for estatico/global: o segundo sequenciador tem de recomecar do zero. */
    @Test
    void doisSequenciadoresSaoIndependentesEAMesmaTxTemUmIdEmCadaUm() {
        TxSequencer s1 = new TxSequencer();
        TxSequencer s2 = new TxSequencer();
        HotStuffTx a = tx();
        HotStuffTx b = tx();
        assertEquals(0L, s1.idOf(a));
        assertEquals(1L, s1.idOf(b));
        // s2 nunca viu b antes de a: a ordem de s2 e a dele, e comeca do zero
        assertEquals(0L, s2.idOf(b));
        assertEquals(1L, s2.idOf(a));
        // e s1 nao foi alterado por s2
        assertEquals(0L, s1.idOf(a));
        assertEquals(1L, s1.idOf(b));
        assertEquals(2L, s1.assigned());
        assertEquals(2L, s2.assigned());
    }

    @Test
    void chaveEPorValorEntaoNaoDependeDoHashDeIdentidadeDaTx() {
        TxSequencer s = new TxSequencer();
        HotStuffTx a = tx();
        Object chave = s.idOf(a); // Long boxed
        assertEquals(Long.valueOf(0L), chave);
        Set<Object> conjunto = new HashSet<>();
        conjunto.add(chave);
        assertTrue(conjunto.contains(Long.valueOf(0L)), "outra instancia de Long com o mesmo valor e a mesma chave");
        assertEquals(Long.hashCode(0L), chave.hashCode());
        assertNotEquals(s.idOf(tx()), chave);
    }

    @Test
    void txNulaFalhaAlto() {
        assertThrows(IllegalArgumentException.class, () -> new TxSequencer().idOf(null));
    }

    /**
     * Uma Tx compartilhada entre simulacoes em threads diferentes (ex.: genesis estatico) recebe um id em cada
     * sequenciador, e cada sequenciador, usado por uma thread so, numera 0..n-1 sem buracos.
     */
    @Test
    void sequenciadoresEmThreadsDiferentesNaoSeInterferemMesmoComTxCompartilhada() throws Exception {
        final int threads = 4;
        final int txs = 2000;
        HotStuffTx compartilhada = tx();
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Set<Long>>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<Set<Long>> job = () -> {
                    TxSequencer s = new TxSequencer();
                    Set<Long> ids = new HashSet<>();
                    ids.add(s.idOf(compartilhada));
                    for (int i = 0; i < txs; i++) {
                        ids.add(s.idOf(tx()));
                    }
                    return ids;
                };
                fs.add(ex.submit(job));
            }
            for (Future<Set<Long>> f : fs) {
                Set<Long> ids = f.get();
                assertEquals(txs + 1, ids.size());
                for (long i = 0; i <= txs; i++) {
                    assertTrue(ids.contains(i), "sem buraco: " + i);
                }
            }
        } finally {
            ex.shutdownNow();
        }
    }
}
