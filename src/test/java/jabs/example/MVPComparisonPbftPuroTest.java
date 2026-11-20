package jabs.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PBFT puro (n validadores, {@code PBFTLANScenario}) no MVPComparison: soma da rede, Tt nao medido, flags obrigatorias. */
class MVPComparisonPbftPuroTest {
    private static String[] args(Path out, String... extra) {
        java.util.List<String> a = new java.util.ArrayList<>(List.of("--protocols=PBFT", "--validators=4", "--trials=1",
                "--duration=30", "--seed=1", "--output=" + out, "--csv-out=" + out.resolve("mvp-results.csv")));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    @Test
    void rodaPbftPuroComSomaDaRedeETtNaoMedido(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("pbft");
        MVPComparison.main(args(out, "--pbft-max-future-horizon=1000"));
        List<String> agregado = Files.readAllLines(out.resolve("mvp-results.csv"));
        assertTrue(agregado.stream().anyMatch(l -> l.startsWith("# PBFT: Tt_avg e Tt_std = -1")), agregado.toString());
        String row = agregado.stream().filter(l -> l.startsWith("PBFT;")).findFirst().orElseThrow();
        String[] c = row.split(";");
        assertEquals(9, c.length, row);
        assertTrue(Double.parseDouble(c[3]) > 0 && Double.parseDouble(c[5]) > 0, "Tb e Cb medidos: " + row);
        assertEquals("-1.000000", c[7], "Tt nao medido, nunca zero: " + row);
        assertEquals("-1.000000", c[8], row);
        List<String> cb = Files.readAllLines(out.resolve("mvp-results-cb.csv"));
        String cbRow = cb.stream().filter(l -> l.startsWith("PBFT;")).findFirst()
                .orElseThrow(() -> new AssertionError("linha PBFT ausente no cb: " + cb));
        assertTrue(Double.parseDouble(cbRow.split(";")[4]) > 0, "soma da rede medida: " + cbRow);
        assertTrue(Files.exists(out.resolve("mvp-results-autoentrega.csv")), "PBFT separa a autoentrega (D2)");
        assertTrue(Files.exists(out.resolve("pbft-4v-t0.csv")), "CSV por bloco");
    }

    @Test
    void semOTetoFalhaAltoAntesDeCriarSaida(@TempDir Path tmp) {
        Path out = tmp.resolve("sem-teto");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(args(out)));
        assertTrue(e.getMessage().contains("pbft-max-future-horizon"), e.getMessage());
        assertTrue(Files.notExists(out), "falha antes de criar saida");
    }

    @Test
    void ataqueComPbftPuroFalhaAlto(@TempDir Path tmp) {
        Path out = tmp.resolve("ataque");
        // PBFT puro nao le --attack: junto de um protocolo capaz (ARQ) com ataque ativo, rodaria honesto sob rotulo de ataque
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(args(out, "--protocols=ARQ,PBFT",
                "--pbft-max-future-horizon=1000", "--attack=EQUIVOCATION", "--byzantine-count=1")));
        assertTrue(Files.notExists(out), "falha antes de criar saida");
    }
}
