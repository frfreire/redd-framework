package jabs.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HS-CB-fase2, parte A: so HOTSTUFF e TENDERMINT ganham o segundo arquivo; os demais ficam como estavam. */
class MVPComparisonCbRedeTest {
    @Test
    void soHotStuffETendermintTemCbRede() {
        assertTrue(MVPComparison.hasCbRede("HOTSTUFF"));
        assertTrue(MVPComparison.hasCbRede(" tendermint "));
        for (String p : new String[]{"ARQ", "EARTH", "MCO2", "TREE", "AMBIFY", "OFFSET", "DAGSPER"}) {
            assertFalse(MVPComparison.hasCbRede(p), p);
        }
    }

    @Test
    void arquivoAditivoFicaAoLadoDoMvpResults() {
        assertEquals("/x/mvp-results-cb.csv", MVPComparison.cbCsvPath("/x/mvp-results.csv"));
        assertEquals("/x/out-cb.csv", MVPComparison.cbCsvPath("/x/out"));
    }
}
