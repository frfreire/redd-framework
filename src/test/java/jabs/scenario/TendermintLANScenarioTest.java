package jabs.scenario;

import jabs.consensus.config.TendermintConsensusConfig;
import jabs.network.networks.tendermint.TendermintTestConfig;
import jabs.network.node.nodes.tendermint.TendermintNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cenario executavel Tendermint: converge, finaliza e preenche metricas a partir dos eventos. */
class TendermintLANScenarioTest {
    private static final int N = 4;

    @Test
    void executaConvergeEFinalizaComMetricas(@TempDir Path tempDir) throws IOException {
        TendermintLANScenario scenario = new TendermintLANScenario(
                "tendermint-test", 7, N, 20.0, TendermintTestConfig.config());
        scenario.addMetricsLogger(tempDir.resolve("tendermint.csv").toString());

        scenario.run();

        assertEquals(N, scenario.getNetwork().getAllNodes().size());
        assertTrue(scenario.getMetrics().getBlockCount() > 0, "deve finalizar blocos");
        assertTrue(scenario.getMetrics().getAverageBlockFinalizationTime() > 0,
                "Tb deve ser medido a partir do BlockFinalizationEvent");
        assertTrue(scenario.getMetrics().getAverageTrafficPerBlock() > 0,
                "Cb deve ser medido a partir do BlockFinalizationEvent");

        TendermintNode primeiro = (TendermintNode) scenario.getNetwork().getAllNodes().get(0);
        for (Object node : scenario.getNetwork().getAllNodes()) {
            assertTrue(node instanceof TendermintNode);
            assertSame(primeiro.getConsensusAlgorithm().getCanonicalChainHead(),
                    ((TendermintNode) node).getConsensusAlgorithm().getCanonicalChainHead());
        }
    }

    @Test
    void configuracaoObrigatoriaEExigeQuatroNos() {
        TendermintConsensusConfig config = TendermintTestConfig.config();
        assertThrows(IllegalArgumentException.class,
                () -> new TendermintLANScenario("n3", 1, 3, 1.0, config));
        assertThrows(IllegalArgumentException.class,
                () -> new TendermintLANScenario("sem-config", 1, N, 1.0, null));
    }
}
