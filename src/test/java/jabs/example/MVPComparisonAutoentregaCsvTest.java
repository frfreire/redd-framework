package jabs.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 (ADR-0015): coluna PROPRIA da autoentrega no arquivo ADITIVO {@code mvp-results-autoentrega.csv}: cabecalho '#',
 * sentinela -1 (nunca zero) e protocolos que a ganham. {@code mvp-results.csv} mantem nome e posicao das colunas.
 */
class MVPComparisonAutoentregaCsvTest {

    @Test
    void soOsProtocolosQueContavamGanhamAColuna() {
        for (String p : new String[] {"ARQ", "MCO2", "TREE", "AMBIFY", "OFFSET", "EARTH", " arq "}) {
            assertTrue(MVPComparison.hasSelfDeliveryColumn(p), p);
        }
        for (String p : new String[] {"DAGSPER", "HOTSTUFF", "TENDERMINT"}) {
            assertFalse(MVPComparison.hasSelfDeliveryColumn(p), p + " ja filtrava a autoentrega: sem coluna");
        }
    }

    @Test
    void caminhoDoAditivoSeguePadraoDoCb() {
        assertEquals("/x/mvp-results-autoentrega.csv", MVPComparison.selfDeliveryCsvPath("/x/mvp-results.csv"));
        assertEquals("/x/sem-extensao-autoentrega.csv", MVPComparison.selfDeliveryCsvPath("/x/sem-extensao"));
    }

    @Test
    void sentinelaMenosUmNuncaViraZero() {
        assertEquals("ARQ;7;1;-1;-1;-1", MVPComparison.selfDeliveryLine("ARQ", 7, 1, -1.0, -1.0, -1.0));
        assertEquals("MCO2;10;1;0.000;0.000;-1", MVPComparison.selfDeliveryLine("MCO2", 10, 1, 0.0, 0.0, -1.0),
                "zero MEDIDO sai 0.000, distinto da sentinela");
        assertEquals("ARQ;7;2;123933.081;5.500;79.405",
                MVPComparison.selfDeliveryLine("ARQ", 7, 2, 123933.0811, 5.5, 79.4049));
    }

    @Test
    void cabecalhoDeclaraGranularidadeUnidadeESentinela() {
        String todos = String.join("\n", MVPComparison.SELF_DELIVERY_COMMENTS);
        for (String c : MVPComparison.SELF_DELIVERY_COMMENTS) {
            assertTrue(c.startsWith("#"), "toda linha de declaracao comeca com '#': " + c);
        }
        assertTrue(todos.contains("-1 = nao medido (nunca zero)"));
        assertTrue(todos.contains("Packet.getFrom() == receptor"));
        assertTrue(todos.contains("regra 6 do AGENTS.md"));
        assertTrue(todos.contains("primeiro cada altura"), "declara a granularidade (mesma populacao do Cb_avg)");
        assertTrue(todos.contains("bytes"), "declara a unidade");
        assertEquals("project;n;trials;Cb_autoentrega_avg_B;Cb_autoentrega_std_B;Cb_autoentrega_consenso_avg_B",
                MVPComparison.SELF_DELIVERY_HEADER);
    }

    @Test
    void arqGravaAditivoELegacyNaoGanhaColuna(@TempDir Path tmp) throws Exception {
        MVPComparison.main(new String[] {"--protocols=ARQ", "--validators=4", "--trials=1", "--duration=20",
                "--seed=1", "--output=" + tmp});
        List<String> agg = Files.readAllLines(tmp.resolve("mvp-results.csv"));
        assertEquals("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std", agg.get(0),
                "mvp-results.csv: nome e posicao das colunas intactos");
        List<String> aut = Files.readAllLines(tmp.resolve("mvp-results-autoentrega.csv"));
        assertTrue(aut.get(0).startsWith("# mvp-results-autoentrega.csv"));
        String cab = aut.get(aut.size() - 2);
        assertEquals(MVPComparison.SELF_DELIVERY_HEADER, cab);
        String[] f = aut.get(aut.size() - 1).split(";");
        assertEquals("ARQ", f[0]);
        assertTrue(Double.parseDouble(f[3]) > 0, "PBFT mede autoentrega > 0: " + aut.get(aut.size() - 1));
        // o CSV por trial ganha a coluna no FIM
        String cabTrial = Files.readAllLines(tmp.resolve("arq-4v-t0.csv")).get(1);
        assertTrue(cabTrial.endsWith(",TrafficConsensusUntilFinalization,Cb_autoentrega_B"), cabTrial);
    }

    @Test
    void dagsperHotstuffTendermintNaoGeramAditivoNemColuna(@TempDir Path tmp) throws Exception {
        MVPComparison.main(new String[] {"--protocols=DAGSPER", "--validators=10", "--trials=1", "--duration=600",
                "--seed=1", "--output=" + tmp});
        assertFalse(Files.exists(tmp.resolve("mvp-results-autoentrega.csv")), "DAGSPER nao tem aditivo");
        String cabTrial = Files.readAllLines(tmp.resolve("dagsper-10v-t0.csv")).get(1);
        assertTrue(cabTrial.endsWith(",TrafficConsensusUntilFinalization"), cabTrial);
        assertFalse(cabTrial.contains("Cb_autoentrega_B"), cabTrial);
    }
}
