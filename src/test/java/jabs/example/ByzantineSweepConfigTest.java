package jabs.example;

import jabs.config.ByzantineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loop 2, criterio 4 (modelo de falha explicito): o sweep falha alto em erro de configuracao e em nivel de f sem
 * trial medido; nunca troca o ataque em silencio nem grava NaN/zero como se fosse medicao.
 */
class ByzantineSweepConfigTest {

    @TempDir
    Path tmp;

    private String[] args(String... extra) {
        // --seed e FIXTURE de teste (antigo padrao): --trials e --seed nao tem mais padrao (ADR-0015 Q26-A)
        String[] base = {"--out=" + tmp.resolve("sweep.csv"), "--seed=12345"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void ataqueInvalidoAbortaOSweep() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ByzantineSweep.run(args("--type=VOTING", "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--validators=10", "--trials=30", "--attack=NAO_EXISTE")));
        assertTrue(e.getMessage().contains("NAO_EXISTE"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("sweep.csv")), "nada deve ser gravado quando a configuracao e invalida");
    }

    /** ADR-0015 Q26-A: --trials e --seed sao obrigatorios, sem padrao; falha antes de gravar qualquer coisa. */
    @Test
    void trialsESeedSaoObrigatoriosSemPadrao() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                new String[] {"--type=VOTING", "--validators=10", "--seed=1", "--out=" + tmp.resolve("a.csv")}));
        assertTrue(e.getMessage().contains("--trials"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                new String[] {"--type=VOTING", "--validators=10", "--trials=1", "--out=" + tmp.resolve("b.csv")}));
        assertTrue(e.getMessage().contains("--seed"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                new String[] {"--type=VOTING", "--validators=10", "--trials=", "--seed=1", "--out=" + tmp.resolve("c.csv")}));
        assertTrue(e.getMessage().contains("--trials"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("a.csv")) || Files.exists(tmp.resolve("b.csv")) || Files.exists(tmp.resolve("c.csv")),
                "nada deve ser gravado quando falta trials ou seed");
    }

    @Test
    void dagsperComEquivocationAborta() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> ByzantineSweep.run(args("--type=DAGSPER", "--validators=10", "--attack=EQUIVOCATION",
                        "--trials=1", "--duration=10")));
        assertTrue(e.getMessage().contains("EQUIVOCATION"), e.getMessage());
    }

    @Test
    void dagsperSemAtaqueExplicitoAbortaEmVezDeEscolherOutro() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ByzantineSweep.run(args("--type=DAGSPER", "--validators=10", "--trials=1")));
        assertTrue(e.getMessage().contains("--attack"), e.getMessage());
    }

    @Test
    void resolveAttackAceitaSilentEWithholdNoDagsperEMantemPadraoNosDemais() {
        assertEquals(ByzantineConfig.AttackType.SILENT, ByzantineSweep.resolveAttack("DAGSPER", "silent"));
        assertEquals(ByzantineConfig.AttackType.WITHHOLD, ByzantineSweep.resolveAttack("DAGSPER", "WITHHOLD"));
        assertEquals(ByzantineConfig.AttackType.EQUIVOCATION, ByzantineSweep.resolveAttack("VOTING", null));
        assertThrows(UnsupportedOperationException.class, () -> ByzantineSweep.resolveAttack("DAGSPER", "DOUBLE_SIGN"));
        assertThrows(UnsupportedOperationException.class, () -> ByzantineSweep.resolveAttack("DAGSPER", "NONE"));
    }

    @Test
    void erroDeConfiguracaoDentroDoTrialPropagaEnaoViraFalhaDeTrial() {
        // DAGsper recusa n < 10 no construtor do cenario (IllegalArgumentException dentro do laco de trials)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ByzantineSweep.run(args("--type=DAGSPER", "--validators=5", "--attack=SILENT",
                        "--trials=1", "--duration=10", "--max-byz=0")));
        assertTrue(e.getMessage().contains("n >="), e.getMessage());

        IllegalArgumentException tipo = assertThrows(IllegalArgumentException.class,
                () -> ByzantineSweep.run(args("--type=INEXISTENTE", "--validators=10", "--trials=1",
                        "--max-byz=0")));
        assertTrue(tipo.getMessage().contains("INEXISTENTE"), tipo.getMessage());
    }

    @Test
    void nivelSemTrialMedidoFalhaAltoSemNaN() throws IOException {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ByzantineSweep.run(args("--type=VOTING", "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--validators=10", "--trials=0", "--max-byz=0")));
        assertTrue(e.getMessage().contains("NAO_MEDIDO"), e.getMessage());

        Path csv = tmp.resolve("sweep.csv");
        // se o arquivo existir, so pode ter o cabecalho: nenhuma linha com NaN ou zero fabricado
        if (Files.exists(csv)) {
            // N1: o cabecalho agora e precedido por linhas '#' (comentario); elas nao sao dado
            List<String> linhas = Files.readAllLines(csv).stream().filter(l -> !l.startsWith("#")).toList();
            assertEquals(1, linhas.size(), "so o cabecalho; nenhuma linha de dados");
            assertFalse(String.join("\n", linhas).contains("NaN"));
        }
    }

    @Test
    void requireMeasuredTrialsRejeitaZeroEAceitaPositivo() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ByzantineSweep.requireMeasuredTrials(0, 3, 2, 10));
        assertTrue(e.getMessage().contains("f=2") && e.getMessage().contains("3 falha"), e.getMessage());
        ByzantineSweep.requireMeasuredTrials(1, 0, 0, 10);
    }
}
