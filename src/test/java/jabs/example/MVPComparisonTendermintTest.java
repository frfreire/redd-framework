package jabs.example;

import jabs.consensus.config.TendermintConsensusConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integracao do Tendermint ao harness MVP: config explicita por flags e execucao no CSV. */
class MVPComparisonTendermintTest {
    private static final String[] TM_FLAGS = {
            "--tm-propose-timeout=1.0", "--tm-propose-delta=0.5",
            "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
            "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5",
            "--tm-batch-size=20", "--tm-tx-size=512",
            // FIXTURE somente de teste, nao e valor de pesquisa (ADR-0010 PI-16: a definir com o humano)
            "--tm-signature-size=64",
            // FIXTURE somente de teste, nao e valor de pesquisa (ADR-0010 PI-08: a definir com o humano)
            "--tm-message-overhead=10"
    };

    @Test
    void configSoEExigidaQuandoTendermintEstaNaLista() {
        assertNull(MVPComparison.parseTendermintConfigIfRequested(List.of("ARQ"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.parseTendermintConfigIfRequested(List.of("TENDERMINT"), Map.of()));
    }

    @Test
    void flagsPreenchemAConfiguracao() {
        Map<String, String> cli = Map.of(
                "tm-propose-timeout", "1.0", "tm-propose-delta", "0.5",
                "tm-prevote-timeout", "2.0", "tm-prevote-delta", "0.25",
                "tm-precommit-timeout", "3.0", "tm-precommit-delta", "0.1",
                "tm-batch-size", "40", "tm-tx-size", "256", "tm-message-overhead", "12",
                // FIXTURE somente de teste, nao e valor de pesquisa
                "tm-signature-size", "48");
        TendermintConsensusConfig config =
                MVPComparison.parseTendermintConfigIfRequested(List.of("tendermint"), cli);
        assertEquals(40, config.getBatchSize());
        assertEquals(256, config.getTxSizeBytes());
        assertEquals(12, config.getMessageOverheadBytes());
        assertEquals(48, config.getSignatureSizeBytes());
        assertEquals(2.25, config.getTimeouts().durationSeconds(
                jabs.consensus.algorithm.tendermint.TimeoutKind.PREVOTE, 1));
    }

    @Test
    void semSignatureSizeFalhaAltoAntesDeQualquerSimulacao(@TempDir Path tmp) {
        Map<String, String> cli = new java.util.HashMap<>(Map.of(
                "tm-propose-timeout", "1.0", "tm-propose-delta", "0.5",
                "tm-prevote-timeout", "1.0", "tm-prevote-delta", "0.5",
                "tm-precommit-timeout", "1.0", "tm-precommit-delta", "0.5",
                "tm-batch-size", "20", "tm-tx-size", "512", "tm-message-overhead", "10"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.parseTendermintConfigIfRequested(List.of("TENDERMINT"), cli));
        assertTrue(e.getMessage().contains("tm-signature-size"), e.getMessage());

        Path out = tmp.resolve("sem-assinatura");
        String[] args = {"--protocols=TENDERMINT", "--validators=4", "--trials=1", "--output=" + out,
                "--tm-propose-timeout=1.0", "--tm-propose-delta=0.5",
                "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
                "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5",
                "--tm-batch-size=20", "--tm-tx-size=512", "--tm-message-overhead=10"};
        IllegalArgumentException main = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.main(args));
        assertTrue(main.getMessage().contains("tm-signature-size"), main.getMessage());
        assertTrue(Files.notExists(out), "falha deve ocorrer antes de criar saida");
    }

    @Test
    void semMessageOverheadFalhaAltoAntesDeQualquerSimulacao(@TempDir Path tmp) {
        Map<String, String> cli = new java.util.HashMap<>(Map.of(
                "tm-propose-timeout", "1.0", "tm-propose-delta", "0.5",
                "tm-prevote-timeout", "1.0", "tm-prevote-delta", "0.5",
                "tm-precommit-timeout", "1.0", "tm-precommit-delta", "0.5",
                "tm-batch-size", "20", "tm-tx-size", "512", "tm-signature-size", "64"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.parseTendermintConfigIfRequested(List.of("TENDERMINT"), cli));
        assertTrue(e.getMessage().contains("tm-message-overhead"), e.getMessage());

        Path out = tmp.resolve("sem-overhead");
        String[] args = {"--protocols=TENDERMINT", "--validators=4", "--trials=1", "--output=" + out,
                "--tm-propose-timeout=1.0", "--tm-propose-delta=0.5",
                "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
                "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5",
                "--tm-batch-size=20", "--tm-tx-size=512", "--tm-signature-size=64"};
        IllegalArgumentException main = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.main(args));
        assertTrue(main.getMessage().contains("tm-message-overhead"), main.getMessage());
        assertTrue(Files.notExists(out), "falha deve ocorrer antes de criar saida");
    }

    @Test
    void messageOverheadNegativoOuInvalidoNoCliFalhaAlto() {
        Map<String, String> base = Map.of(
                "tm-propose-timeout", "1.0", "tm-propose-delta", "0.5",
                "tm-prevote-timeout", "1.0", "tm-prevote-delta", "0.5",
                "tm-precommit-timeout", "1.0", "tm-precommit-delta", "0.5",
                "tm-batch-size", "20", "tm-tx-size", "512", "tm-signature-size", "64");
        for (String ruim : new String[] {"-1", "abc"}) {
            Map<String, String> cli = new java.util.HashMap<>(base);
            cli.put("tm-message-overhead", ruim);
            assertThrows(IllegalArgumentException.class,
                    () -> MVPComparison.parseTendermintConfigIfRequested(List.of("TENDERMINT"), cli));
        }
    }

    @Test
    void signatureSizeConhecidaPeloCli() {
        assertTrue(MVPComparison.knownFlags().contains("tm-signature-size"));
    }

    @Test
    void nMenorQue4FalhaAntesDeCriarSaida(@TempDir Path tmp) {
        Path out = tmp.resolve("n3");
        String[] args = new String[TM_FLAGS.length + 4];
        args[0] = "--protocols=TENDERMINT";
        args[1] = "--validators=3";
        args[2] = "--trials=1";
        args[3] = "--output=" + out;
        System.arraycopy(TM_FLAGS, 0, args, 4, TM_FLAGS.length);

        assertThrows(IllegalArgumentException.class, () -> MVPComparison.main(args));
        assertTrue(Files.notExists(out), "n invalido nao deve criar diretorio de saida");
    }

    @Test
    void executaTendermintEEscreveMetricasNoCsv(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("mvp");
        Path csv = out.resolve("mvp-results.csv");
        String[] args = new String[TM_FLAGS.length + 7];
        args[0] = "--protocols=TENDERMINT";
        args[1] = "--validators=4";
        args[2] = "--trials=1";
        args[3] = "--duration=5.0";
        args[4] = "--seed=7";
        args[5] = "--output=" + out;
        args[6] = "--csv-out=" + csv;
        System.arraycopy(TM_FLAGS, 0, args, 7, TM_FLAGS.length);

        MVPComparison.main(args);

        List<String> lines = Files.readAllLines(csv);
        String row = lines.stream().filter(l -> l.startsWith("TENDERMINT;")).findFirst()
                .orElseThrow(() -> new AssertionError("linha TENDERMINT ausente no CSV: " + lines));
        String[] cols = row.split(";");
        assertEquals("4", cols[1]);
        assertEquals("1", cols[2]);
        assertTrue(Double.parseDouble(cols[3]) > 0, "Tb_avg deve ser medido: " + row);
        assertTrue(Double.parseDouble(cols[5]) > 0, "Cb_avg deve ser medido: " + row);
    }
}
