package jabs.log;

import jabs.scenario.ArqReddVotingScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 4 do D2 (gap do critic): no PBFT a parte so-consenso da autoentrega precisa de coluna propria no CSV por trial,
 * senao os bytes de overhead (ex.: PRE_PREPARE) que saem de TrafficConsensusUntilFinalization nao aparecem em lugar
 * nenhum. Tambem trava semente e SimulatorVersion no cabecalho.
 */
class EnhancedLoggerAutoentregaConsensoTest {

    private static List<String> dados(Path csv) throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(csv)) {
            if (!l.startsWith("#") && !l.isBlank()) {
                out.add(l);
            }
        }
        return out;
    }

    @Test
    void pbftGanhaColunaDeAutoentregaSoConsensoNoFimComSementeEVersao(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("arq-enhanced.csv");
        ArqReddVotingScenario sc = new ArqReddVotingScenario("autoentrega-enhanced", 7, 4, 20.0);
        sc.AddNewLogger(new EnhancedBlockFinalizationLogger(csv, sc.getMetrics()));
        sc.run();

        List<String> d = dados(csv);
        String[] cab = d.get(0).split(",", -1);
        int n = cab.length;
        assertEquals("Cb_autoentrega_consenso_B", cab[n - 1], "coluna so-consenso NO FIM");
        assertEquals("Cb_autoentrega_B", cab[n - 2], "depois da coluna total, que continua no lugar");
        int it = java.util.Arrays.asList(cab).indexOf("TrafficConsensusUntilFinalization");
        assertTrue(it >= 0 && it < n - 2, "colunas existentes intactas");

        for (int i = 1; i < d.size(); i++) {
            String[] r = d.get(i).split(",", -1);
            assertEquals(n, r.length, "linha " + i);
            long total = Long.parseLong(r[n - 2]);
            long cons = Long.parseLong(r[n - 1]);
            assertTrue(cons >= 0 && cons <= total, "linha " + i + ": consenso " + cons + " <= total " + total);
        }

        String c = Files.readAllLines(csv).get(0);
        assertTrue(c.startsWith("#") && c.contains("Cb_autoentrega_consenso_B="), c);
        assertTrue(c.contains("Seed=7;"), "semente declarada: " + c);
        assertTrue(c.contains("SimulatorVersion=" + LinkLoadRecorder.SIMULATOR_VERSION), "versao declarada: " + c);
    }
}
