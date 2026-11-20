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
        MVPComparison.main(args(out, "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING"));
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

    /** Trial falho (aqui: 0 blocos em 0,5 s) NAO vira linha de zeros nem exit 0: falha alto, sem Tt = 0. */
    @Test
    void trialFalhoDoPbftPuroFalhaAltoSemLinhaDeZeros(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("zero");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MVPComparison.main(
                args(out, "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--duration=0.5", "--trials=2")));
        assertTrue(e.getMessage().contains("trial PBFT falhou") && e.getMessage().contains("0 blocos"), e.getMessage());
        if (Files.exists(out.resolve("mvp-results.csv"))) {
            assertTrue(Files.readAllLines(out.resolve("mvp-results.csv")).stream().noneMatch(l -> l.startsWith("PBFT;")),
                    "nenhuma linha de dados do PBFT");
        }
    }

    /** n < 4 nao tem tolerancia bizantina (f = 0) nem quorum util: falha alto antes de criar saida, como HotStuff/Tendermint. */
    @Test
    void pbftPuroComNPequenoFalhaAltoAntesDeCriarSaida(@TempDir Path tmp) {
        for (String n : new String[]{"1", "3", "4,3"}) {
            Path out = tmp.resolve("n" + n.replace(',', '_'));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(
                    args(out, "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--validators=" + n)), n);
            assertTrue(e.getMessage().contains("PBFT puro exige n >= 4"), e.getMessage());
            assertTrue(Files.notExists(out), "falha antes de criar saida: n=" + n);
        }
        MVPComparison.exigirNMinimoDoPbftPuro(List.of("ARQ"), List.of(1)); // so o PBFT puro e restrito
    }

    /** PI-14: a politica de lideranca e obrigatoria e sem padrao; valor invalido tambem falha alto, antes de criar saida. */
    @Test
    void politicaDeLiderancaObrigatoriaEValidada(@TempDir Path tmp) throws Exception {
        java.util.List<String> sem = new java.util.ArrayList<>(List.of("--protocols=PBFT", "--validators=4", "--trials=1",
                "--duration=10", "--seed=1", "--pbft-max-future-horizon=1000"));
        Path o1 = tmp.resolve("sem");
        sem.add("--output=" + o1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(sem.toArray(new String[0])));
        assertTrue(e.getMessage().contains("pbft-leader-policy"), e.getMessage());
        assertTrue(Files.notExists(o1), "falha antes de criar saida");
        Path o2 = tmp.resolve("invalida");
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(
                args(o2, "--pbft-max-future-horizon=1000", "--pbft-leader-policy=stable")));
        assertTrue(e2.getMessage().contains("STABLE, ROTATING"), e2.getMessage());
        // com STABLE roda e o CSV por bloco existe
        Path o3 = tmp.resolve("stable");
        MVPComparison.main(args(o3, "--pbft-max-future-horizon=1000", "--pbft-leader-policy=STABLE"));
        assertTrue(Files.exists(o3.resolve("pbft-4v-t0.csv")));
    }

    @Test
    void ataqueComPbftPuroFalhaAlto(@TempDir Path tmp) {
        Path out = tmp.resolve("ataque");
        // PBFT puro nao le --attack: junto de um protocolo capaz (ARQ) com ataque ativo, rodaria honesto sob rotulo de ataque
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(args(out, "--protocols=ARQ,PBFT",
                "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--attack=EQUIVOCATION", "--byzantine-count=1")));
        assertTrue(Files.notExists(out), "falha antes de criar saida");
    }
}
