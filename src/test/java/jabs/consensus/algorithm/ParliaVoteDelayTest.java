package jabs.consensus.algorithm;

import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-0006, tarefa 3: distribuicao e reprodutibilidade da amostra do atraso de voto. */
class ParliaVoteDelayTest {

    @Test
    void uniformeEmZeroUmAZeroSeis() {
        RandomnessEngine rng = new RandomnessEngine(42);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double soma = 0;
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            double d = Parlia.sampleVoteDelay(rng);
            assertTrue(d >= 0.1 && d < 0.6, "fora de [0,1; 0,6): " + d);
            min = Math.min(min, d);
            max = Math.max(max, d);
            soma += d;
        }
        assertEquals(0.35, soma / n, 0.005, "media de U[0,1; 0,6) e 0,35");
        assertTrue(min < 0.101 && max > 0.599, "cobre o intervalo");
    }

    @Test
    void mesmaSementeMesmaSequencia() {
        RandomnessEngine a = new RandomnessEngine(7);
        RandomnessEngine b = new RandomnessEngine(7);
        for (int i = 0; i < 100; i++) {
            assertEquals(Parlia.sampleVoteDelay(a), Parlia.sampleVoteDelay(b));
        }
    }

    @Test
    void mesmaFormaDaOriginalComGeradorDaSemente() {
        // 0,1 + u * 0,5 com u = nextDouble() do gerador do simulador: mesma forma e ponto de consumo da original.
        RandomnessEngine a = new RandomnessEngine(5);
        RandomnessEngine b = new RandomnessEngine(5);
        assertEquals(0.1 + a.nextDouble() * 0.5, Parlia.sampleVoteDelay(b));
    }
}
