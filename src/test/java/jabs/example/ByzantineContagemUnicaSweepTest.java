package jabs.example;

import jabs.config.ByzantineConfig;
import jabs.config.ByzantineConfig.AttackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BYZ-UNICA (ADR-0015), portas de entrada: {@link ByzantineSweep} (f inteiro direto, universo so de validadores/votantes,
 * faixa BFT) e o rotulo comum com o {@link MVPComparison}.
 */
class ByzantineContagemUnicaSweepTest {
    @TempDir
    Path tmp;

    // ---------- universo so validadores/votantes (Q30 A, Q32 A) ----------

    @Test
    void universo_por_protocolo_no_sweep() {
        int[] sizing = {3, 7}; // nodes, n=10: 3 mineradores + 7 stakeholders
        for (long seed = 1; seed <= 25; seed++) {
            for (int f = 1; f <= 3; f++) {
                // Parlia (POSA): so os mineradores (ids 0..2) validam
                ByzantineConfig posa = ByzantineSweep.configFor("POSA", 10, f, seed, AttackType.WITHHOLD, sizing);
                assertEquals(f, posa.getByzantineCount());
                for (int id : posa.getByzantineValidatorIds()) {
                    assertTrue(id < 3, "Parlia: bizantino fora dos validadores (mineradores): " + id);
                }
                assertEquals(10, posa.getTotalValidators(), "totalValidators = nos construidos");
                // Casper (POS): todos votam; ids 0..9
                ByzantineConfig pos = ByzantineSweep.configFor("POS", 10, f, seed, AttackType.WITHHOLD, sizing);
                assertEquals(f, pos.getByzantineCount());
                for (int id : pos.getByzantineValidatorIds()) {
                    assertTrue(id >= 0 && id < 10);
                }
                assertEquals(10, pos.getTotalValidators());
            }
        }
        // Casper: algum sorteio cai num minerador (id < 3): antes so sorteava stakeholders (ids >= miners)
        boolean sorteouMinerador = false;
        for (long seed = 1; seed <= 25 && !sorteouMinerador; seed++) {
            for (int id : ByzantineSweep.configFor("POS", 10, 3, seed, AttackType.WITHHOLD, sizing).getByzantineValidatorIds()) {
                sorteouMinerador |= id < 3;
            }
        }
        assertTrue(sorteouMinerador, "todos os nos do Casper votam: mineradores tambem entram no universo");
        assertEquals(10, ByzantineSweep.universeSize("POS", 10, sizing));
        assertEquals(3, ByzantineSweep.universeSize("POSA", 10, sizing));
        assertEquals(10, ByzantineSweep.universeSize("VOTING", 10, null));
        assertEquals(10, ByzantineSweep.universeSize("DAGSPER", 10, null));
        assertEquals(10, ByzantineSweep.universeSize("POW", 10, null));
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.configFor("XYZ", 10, 1, 1L, AttackType.SILENT, null));
        // legacy: Parlia com n=10 tem 3 mineradores e 10 stakeholders (13 nos): o universo continua = mineradores
        int[] legacy = ByzantineSweep.sweepSizing(false, 10);
        assertEquals(3, ByzantineSweep.universeSize("POSA", 10, legacy));
        assertEquals(13, ByzantineSweep.universeSize("POS", 10, legacy));
    }

    @Test
    void mesmo_conjunto_no_sweep_e_no_gerador_para_voting_e_pow() {
        for (long seed : new long[] {1L, 2L, 99L}) {
            for (int n : new int[] {4, 7, 10, 13}) {
                for (int f = 0; f <= n; f++) {
                    assertEquals(ByzantineConfig.selectNested(seed, n, f),
                            ByzantineSweep.configFor("VOTING", n, f, seed, AttackType.EQUIVOCATION, null).getByzantineValidatorIds());
                    assertEquals(ByzantineConfig.selectNested(seed, n, f),
                            ByzantineSweep.configFor("POW", n, f, seed, AttackType.EQUIVOCATION, null).getByzantineValidatorIds());
                }
            }
        }
    }

    // ---------- f inteiro direto no sweep, nos pares do defeito ----------

    @Test
    void sweep_pede_f_e_recebe_f_em_todos_os_tipos_e_em_n_pequeno_exaustivo() {
        for (int n = 4; n <= 13; n++) {
            int[] sizing = ByzantineSweep.sweepSizing(true, n);
            for (String tipo : new String[] {"VOTING", "POS", "POSA", "POW"}) {
                int[] s = tipo.startsWith("POS") ? sizing : null;
                int universo = ByzantineSweep.universeSize(tipo, n, s);
                for (int f = 0; f <= universo; f++) {
                    ByzantineConfig c = ByzantineSweep.configFor(tipo, n, f, 3L, AttackType.WITHHOLD, s);
                    assertEquals(f, c.getByzantineCount(), tipo + " n=" + n + " f=" + f);
                }
            }
        }
        // o par do ADR (n=7, f=1) em VOTING: antes 2
        assertEquals(1, ByzantineSweep.configFor("VOTING", 7, 1, 1L, AttackType.EQUIVOCATION, null).getByzantineCount());
    }

    // ---------- (2) fora da faixa ----------

    @Test
    void rotulo_e_o_mesmo_texto_do_hotstuff() {
        for (int[] nf : new int[][] {{4, 2}, {7, 3}, {10, 4}}) {
            assertEquals(List.of(ByzantineConfig.beyondThresholdLine(nf[0], nf[1])),
                    MVPComparison.beyondThresholdLines(AttackType.EQUIVOCATION, nf[1], List.of(nf[0])));
        }
        assertEquals(ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD, MVPComparison.ALLOW_BEYOND_BFT_THRESHOLD);
    }

    private String[] sweep(String nome, String... extra) {
        List<String> a = new ArrayList<>(List.of("--out=" + tmp.resolve(nome).resolve("s.csv"), "--seed=1", "--duration=10",
                "--trials=0", "--attack=WITHHOLD"));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    @Test
    void sweep_fora_da_faixa_sem_flag_falha_antes_de_gravar() {
        // n=10: limite 3; max-byz=4 -> f=4 fora da faixa
        for (String tipo : new String[] {"VOTING", "DAGSPER"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ByzantineSweep.run(sweep("sem-" + tipo, "--type=" + tipo, "--validators=10", "--max-byz=4")), tipo);
            assertTrue(e.getMessage().contains("n=10") && e.getMessage().contains("floor((n-1)/3)=3")
                    && e.getMessage().contains("f=4"), e.getMessage());
            assertTrue(e.getMessage().contains("--allow-beyond-bft-threshold=true"), e.getMessage());
            assertFalse(Files.exists(tmp.resolve("sem-" + tipo)), "nada gravado: " + tipo);
        }
        // POS (nodes): universo = 10 nos, limite 3
        IllegalArgumentException pos = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                sweep("sem-pos", "--type=POS", "--validators=10", "--max-byz=4", "--n-semantics=nodes")));
        assertTrue(pos.getMessage().contains("n=10") && pos.getMessage().contains("=3"), pos.getMessage());
        // POSA (nodes): universo = mineradores = 3 -> limite 0: f=1 ja e fora da faixa
        IllegalArgumentException posa = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                sweep("sem-posa", "--type=POSA", "--validators=10", "--max-byz=1", "--n-semantics=nodes")));
        assertTrue(posa.getMessage().contains("n=3") && posa.getMessage().contains("floor((n-1)/3)=0"), posa.getMessage());
        assertFalse(Files.exists(tmp.resolve("sem-posa")));
        // "false" explicito equivale a ausente
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(sweep("sem-false", "--type=VOTING",
                "--validators=10", "--max-byz=4", "--allow-beyond-bft-threshold=false")));
        // valor invalido da flag falha alto mesmo dentro da faixa
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(sweep("sem-ruim", "--type=VOTING",
                "--validators=10", "--max-byz=1", "--allow-beyond-bft-threshold=TRUE")));
    }

    @Test
    void sweep_fora_da_faixa_com_flag_roda_e_rotula_por_f_so_o_que_esta_fora() throws IOException {
        // trials=0 -> cabecalho e falha NAO_MEDIDO (padrao do NetworkSizingTest): o arquivo traz so as linhas de cabecalho
        Path csv = tmp.resolve("com").resolve("s.csv");
        assertThrows(IllegalStateException.class, () -> ByzantineSweep.run(sweep("com", "--type=VOTING", "--validators=10",
                "--max-byz=5", "--allow-beyond-bft-threshold=true")));
        String txt = Files.readString(csv);
        for (int f : new int[] {4, 5}) {
            assertTrue(txt.contains("# fora_da_faixa=true n=10 byzantine_count=" + f + " limite_bft=3"), txt);
        }
        for (int f : new int[] {0, 1, 2, 3}) {
            assertFalse(txt.contains("byzantine_count=" + f + " "), "dentro da faixa nao rotula: f=" + f);
        }
        // dentro da faixa, com ou sem a flag: CSV identico byte a byte
        assertThrows(IllegalStateException.class, () -> ByzantineSweep.run(sweep("dentro-a", "--type=VOTING",
                "--validators=10", "--max-byz=3")));
        assertThrows(IllegalStateException.class, () -> ByzantineSweep.run(sweep("dentro-b", "--type=VOTING",
                "--validators=10", "--max-byz=3", "--allow-beyond-bft-threshold=true")));
        assertEquals(Files.readString(tmp.resolve("dentro-a").resolve("s.csv")),
                Files.readString(tmp.resolve("dentro-b").resolve("s.csv")));
        assertFalse(Files.readString(tmp.resolve("dentro-a").resolve("s.csv")).contains("fora_da_faixa"));
    }

    @Test
    void sweep_f_maior_que_universo_falha_mesmo_com_flag_e_pow_nao_aceita_a_flag() {
        // POSA nodes n=10: universo 3 mineradores; f=4 > 3 nao e "fora da faixa", e universo: nunca liberado
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                sweep("u", "--type=POSA", "--validators=10", "--max-byz=4", "--n-semantics=nodes",
                        "--allow-beyond-bft-threshold=true")));
        assertTrue(e.getMessage().contains("universo"), e.getMessage());
        // POW: limiar de Nakamoto nao e 1/3 (a definir com o humano): flag recusada, sem guarda de faixa
        assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                sweep("pow", "--type=POW", "--validators=10", "--max-byz=1", "--allow-beyond-bft-threshold=true")));
        assertTrue(ByzantineSweep.knownFlags().contains("allow-beyond-bft-threshold"));
    }

    @Test
    void sweep_legacy_dentro_da_faixa_nao_ganha_linhas_de_comentario() throws IOException {
        Path csv = tmp.resolve("leg").resolve("s.csv");
        assertThrows(IllegalStateException.class, () -> ByzantineSweep.run(sweep("leg", "--type=VOTING", "--validators=10",
                "--max-byz=3")));
        assertTrue(Files.readString(csv).startsWith("protocol,n,f,"), "legacy dentro da faixa: so o cabecalho de colunas");
    }

    // ---------- divida aceita (ADR-0015, BYZ-UNICA parte 3 -> D2): teto do laco ANTES de montar fValues ----------

    @Test
    void sweep_max_byz_absurdo_falha_alto_sem_laco_e_sem_arquivo() {
        // Antes: `for (v = step; v <= maxByz; v += step)` com maxByz = Integer.MAX_VALUE estoura v para negativo (laco sem
        // fim, lista sem fim). Agora o teto min(maxByz, universo) e checado ANTES: falha alto, mensagem clara, nada gravado.
        for (String max : new String[] {"2147483647", "2147483646", "11"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ByzantineSweep.run(sweep("absurdo-" + max, "--type=VOTING", "--validators=10", "--max-byz=" + max)), max);
            assertTrue(e.getMessage().contains("--max-byz") && e.getMessage().contains("maior que o universo de sorteio")
                    && e.getMessage().contains("f=" + max) && e.getMessage().contains("10 no(s)")
                    && e.getMessage().contains("n_validadores") && e.getMessage().contains("tipo VOTING"), e.getMessage());
            assertFalse(Files.exists(tmp.resolve("absurdo-" + max)), "nada gravado: " + max);
        }
        // a flag nao libera f > universo (so f > floor((n-1)/3))
        IllegalArgumentException comFlag = assertThrows(IllegalArgumentException.class, () -> ByzantineSweep.run(
                sweep("absurdo-flag", "--type=VOTING", "--validators=10", "--max-byz=2147483647",
                        "--allow-beyond-bft-threshold=true")));
        assertTrue(comFlag.getMessage().contains("nao libera f > universo"), comFlag.getMessage());
        // POW (sem faixa BFT) tambem: o limite de universo vale para todos os tipos
        assertThrows(IllegalArgumentException.class,
                () -> ByzantineSweep.run(sweep("absurdo-pow", "--type=POW", "--validators=10", "--max-byz=2147483647")));
    }

    @Test
    void sweep_max_byz_igual_ao_universo_ainda_passa_pelo_teto() {
        // max-byz == universo NAO e rejeitado pelo teto novo (cai na faixa BFT, como antes, com a mensagem de faixa)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ByzantineSweep.run(sweep("igual", "--type=VOTING", "--validators=10", "--max-byz=10")));
        assertTrue(e.getMessage().contains("fora da faixa BFT"), e.getMessage());
    }
}
