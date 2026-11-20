package jabs.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ByzantineSweep VOTING: a politica de lideranca do PBFT (PI-14) e obrigatoria, sem padrao, e so STABLE/ROTATING exatos. */
class ByzantineSweepPoliticaDeLiderancaTest {
    @Test
    void sweepVotingExigeAPoliticaDeLideranca(@TempDir Path tmp) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                new String[]{"--type=VOTING", "--validators=4", "--max-byz=0", "--trials=1", "--duration=5", "--attack=SILENT",
                        "--seed=1", "--pbft-max-future-horizon=1000", "--out=" + tmp.resolve("s.csv")}));
        assertTrue(e.getMessage().contains("pbft-leader-policy"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                new String[]{"--type=VOTING", "--validators=4", "--max-byz=0", "--trials=1", "--duration=5", "--attack=SILENT",
                        "--seed=1", "--pbft-max-future-horizon=1000", "--pbft-leader-policy=stable", "--out=" + tmp.resolve("t.csv")}));
    }
}
