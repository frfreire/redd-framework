package jabs.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ataque uniforme no MVPComparison (backlog de 17/11/2026): TENDERMINT e ARQ (PBFT) leem {@code --attack=EQUIVOCATION}
 * e {@code --byzantine-count=f} como o HOTSTUFF. Fixtures de TESTE (nao e calibracao).
 */
class MVPComparisonAtaqueUniformeTest {
    private static final List<String> TM = List.of("--tm-propose-timeout=1.0", "--tm-propose-delta=0.5",
            "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5", "--tm-precommit-timeout=1.0",
            "--tm-precommit-delta=0.5", "--tm-batch-size=20", "--tm-tx-size=512", "--tm-signature-size=64",
            "--tm-proposer-signature-size=80", "--tm-message-overhead=10", "--tm-max-future-horizon=1000");

    private static String[] args(Path out, String protocolo, String... extra) {
        List<String> a = new ArrayList<>(List.of("--protocols=" + protocolo, "--validators=4", "--trials=1",
                "--duration=30", "--seed=7", "--output=" + out, "--csv-out=" + out.resolve("mvp-results.csv"),
                "--pbft-max-future-horizon=1000"));
        if (protocolo.contains("TENDERMINT")) {
            a.addAll(TM);
        }
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    @Test
    void tendermintEArqSobEquivocationRodamERotulamOAtaque(@TempDir Path tmp) throws Exception {
        for (String proto : new String[]{"TENDERMINT", "ARQ"}) {
            Path out = tmp.resolve(proto);
            MVPComparison.main(args(out, proto, "--attack=EQUIVOCATION", "--byzantine-count=1"));
            String bruto = Files.readString(out.resolve("mvp-results.csv"));
            List<String> linhas = bruto.lines().toList();
            assertTrue(linhas.stream().anyMatch(l -> l.startsWith("# attack=EQUIVOCATION byzantine_count=1 (" + proto + ";")),
                    proto + ": rotulo do ataque no cabecalho: " + linhas);
            String row = linhas.stream().filter(l -> l.startsWith(proto + ";")).findFirst()
                    .orElseThrow(() -> new AssertionError(proto + ": linha ausente: " + linhas));
            assertEquals(9, row.split(";").length, row);
        }
    }

    /** O ataque CHEGA ao cenario: o resultado difere do honesto (rotulo sozinho nao prova nada). */
    @Test
    void ataqueMudaOResultadoEmTendermintEArq(@TempDir Path tmp) throws Exception {
        for (String proto : new String[]{"TENDERMINT", "ARQ"}) {
            Path honesto = tmp.resolve(proto + "-h");
            Path ataque = tmp.resolve(proto + "-a");
            MVPComparison.main(args(honesto, proto));
            MVPComparison.main(args(ataque, proto, "--attack=EQUIVOCATION", "--byzantine-count=1"));
            String linhaH = Files.readAllLines(honesto.resolve("mvp-results.csv")).stream()
                    .filter(l -> l.startsWith(proto + ";")).findFirst().orElseThrow();
            String linhaA = Files.readAllLines(ataque.resolve("mvp-results.csv")).stream()
                    .filter(l -> l.startsWith(proto + ";")).findFirst().orElseThrow();
            assertNotEquals(linhaH, linhaA, proto + ": o ataque nao mudou o resultado (nao chegou ao cenario?)");
        }
    }

    /** Sob ataque, trial que falha NAO vira linha de zeros: ARQ n=4 f=2 (fora da faixa, liberado) aborta. */
    @Test
    void trialFalhoSobAtaqueNaoViraLinhaDeZeros(@TempDir Path tmp) {
        Path out = tmp.resolve("arq-f2");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MVPComparison.main(
                args(out, "ARQ", "--attack=EQUIVOCATION", "--byzantine-count=2", "--allow-beyond-bft-threshold=true")));
        assertTrue(e.getMessage().contains("sob ataque falhou"), e.getMessage());
        if (Files.exists(out.resolve("mvp-results.csv"))) {
            try {
                assertTrue(Files.readAllLines(out.resolve("mvp-results.csv")).stream().noneMatch(l -> l.startsWith("ARQ;")),
                        "nenhuma linha de dados de ARQ");
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    @Test
    void ataqueExigeContagemEIncapazMisturadoFalhaAltoAntesDeCriarSaida(@TempDir Path tmp) {
        Path out = tmp.resolve("sem-contagem");
        assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.main(args(out, "TENDERMINT", "--attack=EQUIVOCATION")));
        assertTrue(Files.notExists(out), "falha antes de criar saida");
        Path out2 = tmp.resolve("misto");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.main(args(out2, "ARQ,AMBIFY", "--attack=EQUIVOCATION", "--byzantine-count=1",
                        "--n-semantics=legacy")));
        assertTrue(e.getMessage().contains("HOTSTUFF, TENDERMINT e ARQ"), e.getMessage());
        assertTrue(Files.notExists(out2), "falha antes de criar saida");
    }

    @Test
    void semAtaqueASaidaDeTendermintEArqNaoMuda(@TempDir Path tmp) throws Exception {
        for (String proto : new String[]{"TENDERMINT", "ARQ"}) {
            Path a = tmp.resolve(proto + "-a");
            Path b = tmp.resolve(proto + "-b");
            MVPComparison.main(args(a, proto));
            MVPComparison.main(args(b, proto, "--attack=none"));
            assertEquals(Files.readString(a.resolve("mvp-results.csv")), Files.readString(b.resolve("mvp-results.csv")),
                    proto + ": --attack=none = sem a flag");
            assertTrue(Files.readString(a.resolve("mvp-results.csv")).lines().noneMatch(l -> l.startsWith("# attack=")));
        }
    }
}
