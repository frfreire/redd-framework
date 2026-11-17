package jabs.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BYZ-UNICA (ADR-0015, registros de 10/11, 11/11 e 16/11), camada comum {@link ByzantineConfig}: contagem inteira, faixa
 * BFT e gerador unico aninhado. As portas de entrada (ByzantineSweep, cenarios) ficam em
 * {@code jabs.example.ByzantineContagemUnicaSweepTest}.
 */
class ByzantineContagemUnicaTest {
    private static final String EQ = "EQUIVOCATION";

    // ---------- (1) contagem inteira ----------

    /** Oraculo INDEPENDENTE do caminho por percentual: teto exato de n * (valor binario exato de pct) / 100, em BigDecimal. */
    private static int oraculoExato(int n, double pct) {
        return new BigDecimal(pct).multiply(new BigDecimal(n)).divide(new BigDecimal(100)).setScale(0, RoundingMode.CEILING)
                .intValueExact();
    }

    @Test
    void caminho_por_contagem_inteira_da_f_exato_em_todos_os_pares_exaustivo() {
        // f e a ENTRADA PRIMARIA (ADR-0015 decisao 1): nenhum double no caminho. n=1..64, f<=n e n=4..200, f<=n/2
        for (int n = 1; n <= 64; n++) {
            for (int f = 0; f <= n; f++) {
                assertEquals(f, ByzantineConfig.withExactCount(n, f, EQ, 7L).getByzantineCount(), "n=" + n + " f=" + f);
                assertEquals(f, ByzantineConfig.forUniverse(n, ByzantineConfig.range(0, n), f, EQ, 7L).getByzantineCount());
                assertEquals(f, ByzantineConfig.selectNested(7L, n, f).size());
            }
        }
        for (int n = 4; n <= 200; n++) {
            for (int f = 0; f <= n / 2; f++) {
                assertEquals(f, ByzantineConfig.withExactCount(n, f, EQ, 7L).getByzantineCount(), "n=" + n + " f=" + f);
                assertEquals(f, ByzantineConfig.selectNested(7L, n, f).size(), "n=" + n + " f=" + f);
            }
        }
        // o par do ADR (n=7, f=1): f=1, nao 2
        assertEquals(1, ByzantineConfig.withExactCount(7, 1, EQ, 1L).getByzantineCount());
        assertEquals(2, ByzantineConfig.withExactCount(7, 2, EQ, 1L).getByzantineCount());
        assertEquals(1, ByzantineConfig.withExactCount(14, 1, EQ, 1L).getByzantineCount());
        assertEquals(3, ByzantineConfig.withExactCount(9, 3, EQ, 1L).getByzantineCount());
        // phi em aritmetica inteira (sem double): f = ceil(phi * n) = ceil(1/7 * 7) = 1
        assertEquals(1, ByzantineConfig.countFromFraction(7, 1, 7));
        assertEquals(1, ByzantineConfig.countFromFraction(14, 1, 14));
    }

    @Test
    void caminho_legado_por_percentual_e_o_teto_exato_do_double_sem_tolerancia() {
        // Contraexemplo do critic (rodada 2): n=7, pct = 100.0/7. O double vale ligeiramente MAIS que 100/7, entao
        // 7 * pct / 100 e ligeiramente maior que 1 e o teto EXATO e 2. NAO ha excecao bit a bit: o resultado e 2.
        double cemSetimos = 100.0 / 7;
        assertTrue(new BigDecimal(cemSetimos).multiply(new BigDecimal(7)).compareTo(new BigDecimal(100)) > 0,
                "premissa: o double de 100/7 excede 100/7");
        assertEquals(2, ByzantineConfig.countFromPercent(7, cemSetimos));
        assertEquals(2, ByzantineConfig.countFromPercent(7, 100.0 * 1 / 7));
        assertEquals(2, new ByzantineConfig(7, cemSetimos, EQ, 1L).getByzantineCount());
        // contrastes: o caminho por contagem inteira da f=1 para o mesmo n
        assertEquals(1, ByzantineConfig.withExactCount(7, 1, EQ, 1L).getByzantineCount());
        // outros pares do ADR (o double de 100*f/n fica acima do racional): teto exato = f + 1
        assertEquals(3, ByzantineConfig.countFromPercent(7, 100.0 * 2 / 7));
        assertEquals(2, ByzantineConfig.countFromPercent(14, 100.0 * 1 / 14));
        assertEquals(4, ByzantineConfig.countFromPercent(9, 100.0 * 3 / 9));
        // exaustivo: SEMPRE o teto exato do double (oraculo BigDecimal independente), para pct = 100.0*f/n
        int difereDeF = 0;
        int pares = 0;
        for (int n = 1; n <= 64; n++) {
            for (int f = 0; f <= n; f++) {
                double pct = 100.0 * f / n;
                int obtido = ByzantineConfig.countFromPercent(n, pct);
                assertEquals(oraculoExato(n, pct), obtido, "n=" + n + " f=" + f + " pct=" + pct);
                assertTrue(obtido == f || obtido == f + 1, "n=" + n + " f=" + f + " obtido=" + obtido);
                pares++;
                if (obtido != f) {
                    difereDeF++;
                }
            }
        }
        for (int n = 4; n <= 200; n++) {
            for (int f = 0; f <= n / 2; f++) {
                double pct = 100.0 * f / n;
                assertEquals(oraculoExato(n, pct), ByzantineConfig.countFromPercent(n, pct), "n=" + n + " f=" + f);
            }
        }
        // NAO e tolerante: ha pares em que o percentual reconstruido de f NAO volta a f (por isso o caminho por contagem existe)
        assertTrue(difereDeF > 0, "o caminho por percentual nao e tolerante: ha pares com f+1 (" + difereDeF + " de " + pares + ")");
        // e o teto exato tambem nao e "arredondado para baixo": 1 ULP acima de um inteiro exato sobe, 1 ULP abaixo nao
        assertEquals(2, ByzantineConfig.countFromPercent(10, Math.nextUp(10.0)));
        assertEquals(1, ByzantineConfig.countFromPercent(10, 10.0));
        assertEquals(1, ByzantineConfig.countFromPercent(10, Math.nextDown(10.0)));
        assertEquals(3, ByzantineConfig.countFromPercent(10, 30.0));
        assertEquals(4, ByzantineConfig.countFromPercent(10, Math.nextUp(30.0)));
    }

    @Test
    void teto_de_phi_vezes_n_confere_com_oraculo_racional_para_os_niveis_do_estudo() {
        // niveis do ADR-0000: phi em {0; 0,10; 0,30}; oraculo independente: BigDecimal com CEILING (sem double)
        int[][] niveis = {{0, 100}, {10, 100}, {30, 100}};
        for (int[] nivel : niveis) {
            for (int n = 1; n <= 500; n++) {
                int esperado = new BigDecimal(n).multiply(new BigDecimal(nivel[0])).divide(new BigDecimal(nivel[1]), 0,
                        RoundingMode.CEILING).intValueExact();
                assertEquals(esperado, ByzantineConfig.countFromFraction(n, nivel[0], nivel[1]),
                        "n=" + n + " phi=" + nivel[0] + "/" + nivel[1]);
                // porta legada de percentual: teto exato do DOUBLE recebido (10.0 e 30.0 sao exatos em binario)
                double pct = nivel[0] == 0 ? 0.0 : nivel[0] * 100.0 / nivel[1];
                assertEquals(esperado, ByzantineConfig.countFromPercent(n, pct),
                        "pela porta de percentual: n=" + n + " phi=" + nivel[0]);
                assertEquals(oraculoExato(n, pct), ByzantineConfig.countFromPercent(n, pct));
            }
        }
        // igual em todos os protocolos: a regra nao depende do protocolo; n=4, phi=0,30 -> f=2 (> floor(3/3)=1: fora da faixa)
        assertEquals(2, ByzantineConfig.countFromFraction(4, 30, 100));
        assertTrue(ByzantineConfig.beyondBftThreshold(4, 2));
        assertEquals(3, ByzantineConfig.countFromFraction(7, 30, 100)); // 2,1 -> 3 (limite 2: fora da faixa)
        assertEquals(1, ByzantineConfig.countFromFraction(7, 10, 100));
        assertEquals(1, ByzantineConfig.countFromFraction(10, 10, 100));
        assertEquals(3, ByzantineConfig.countFromFraction(10, 30, 100)); // 3 = floor(9/3): no limite
    }

    /** Oraculo independente: ceil(n * pct / 100) em BigDecimal exato a partir do TEXTO decimal (sem double). */
    private static int oraculoDecimal(int n, String pctDecimal) {
        return new BigDecimal(n).multiply(new BigDecimal(pctDecimal)).movePointLeft(2).setScale(0, RoundingMode.CEILING)
                .intValueExact();
    }

    @Test
    void percentual_com_mais_de_seis_casas_nao_e_truncado_em_silencio() {
        // contraexemplo do critic (rodada 1): phi*n = 1,0000000001 -> ceil = 2; o truncamento a 6 casas devolvia 1
        assertEquals(2, ByzantineConfig.countFromPercent(10, 10.000000001));
        assertEquals(2, oraculoDecimal(10, "10.000000001"));
        // mesmo defeito em outras escalas: 7a casa e alem (1e-7, 1e-9, 1e-12 por cento acima do inteiro)
        assertEquals(2, ByzantineConfig.countFromPercent(10, 10.0000001));
        assertEquals(2, ByzantineConfig.countFromPercent(10, 10.000000000001));
        assertEquals(4, ByzantineConfig.countFromPercent(10, 30.000000001));
        assertEquals(31, ByzantineConfig.countFromPercent(100, 30.0000001));
        assertEquals(11, ByzantineConfig.countFromPercent(100, 10.0000001));
        // do outro lado: um fio abaixo do inteiro continua no inteiro
        assertEquals(1, ByzantineConfig.countFromPercent(10, 9.999999999));
        assertEquals(3, ByzantineConfig.countFromPercent(10, 29.999999999));
        // varredura contra o oraculo exato, com entradas de ate 12 casas decimais acima e abaixo de 100k/n
        String[] sufixos = {"000000001", "0000001", "0000000001", "9999999", "999999999"};
        for (int n : new int[] {1, 2, 3, 4, 7, 10, 16, 21, 100, 1000}) {
            for (int k = 1; k < n; k += Math.max(1, n / 9)) {
                BigDecimal base = new BigDecimal(k * 100).divide(new BigDecimal(n), 3, RoundingMode.DOWN);
                if (base.multiply(new BigDecimal(n)).compareTo(new BigDecimal(k * 100)) != 0) {
                    continue; // so onde 100k/n tem expansao decimal curta: o oraculo parte de texto exato
                }
                for (String suf : sufixos) {
                    String acima = base.toPlainString() + (base.scale() == 0 ? "." : "") + suf;
                    assertEquals(oraculoExato(n, Double.parseDouble(acima)),
                            ByzantineConfig.countFromPercent(n, Double.parseDouble(acima)), "n=" + n + " pct=" + acima);
                }
            }
        }
    }

    @Test
    void produto_phi_n_inteiro_em_binario_exato_nao_sobe_e_literal_decimal_nao_exato_segue_o_double() {
        // pct exato em binario (inteiros, 12,5, 0,5, 0,25...): phi*n inteiro => ceil(inteiro) = inteiro, sem excecao
        for (int n : new int[] {10, 20, 30, 40, 50, 100, 200, 1000}) {
            assertEquals(n / 10, ByzantineConfig.countFromPercent(n, 10.0), "n=" + n);
            assertEquals(n * 3 / 10, ByzantineConfig.countFromPercent(n, 30.0), "n=" + n);
            assertEquals((n + 3) / 4, ByzantineConfig.countFromPercent(n, 25.0), "n=" + n);
        }
        assertEquals(5, ByzantineConfig.countFromPercent(10, 50.0));
        assertEquals(1, ByzantineConfig.countFromPercent(8, 12.5));
        assertEquals(1, ByzantineConfig.countFromPercent(16, 6.25));
        // contrato documentado: o literal 0,1 NAO e exato em double (0,1000000000000000055...), entao o teto EXATO de
        // 1000 * 0,1% e 2, nao 1. A tolerancia que devolveria 1 foi removida (rodada 3).
        assertEquals(2, ByzantineConfig.countFromPercent(1000, 0.1));
        assertEquals(oraculoExato(1000, 0.1), ByzantineConfig.countFromPercent(1000, 0.1));
        // para cada literal, o resultado e sempre o oraculo exato do double, nunca o do texto
        for (int n : new int[] {1, 3, 7, 10, 64, 100, 999, 1000}) {
            for (int milesimos = 0; milesimos <= 100000; milesimos += 137) {
                String txt = new BigDecimal(milesimos).movePointLeft(3).toPlainString();
                double pct = Double.parseDouble(txt);
                assertEquals(oraculoExato(n, pct), ByzantineConfig.countFromPercent(n, pct), "n=" + n + " pct=" + txt);
                // e nunca abaixo do teto do texto decimal (so pode subir 1 quando o double excede o literal)
                int doTexto = oraculoDecimal(n, txt);
                int obtido = ByzantineConfig.countFromPercent(n, pct);
                assertTrue(obtido == doTexto || obtido == doTexto + 1, "n=" + n + " pct=" + txt);
            }
        }
    }

    @Test
    void phi_zero_n_pequeno_e_um_terco() {
        for (int n = 0; n <= 5; n++) {
            assertEquals(0, ByzantineConfig.countFromPercent(n, 0.0), "phi=0 n=" + n);
        }
        assertEquals(0, ByzantineConfig.countFromPercent(10, 0.0));
        // phi > 0 com n pequeno: ceil leva ao menos 1 (nunca 0)
        assertEquals(1, ByzantineConfig.countFromPercent(1, 10.0));
        assertEquals(1, ByzantineConfig.countFromPercent(1, 0.000000001));
        assertEquals(1, ByzantineConfig.countFromPercent(2, 10.0));
        assertEquals(1, ByzantineConfig.countFromPercent(4, 10.0));
        assertEquals(2, ByzantineConfig.countFromPercent(4, 30.0));
        assertEquals(1, ByzantineConfig.countFromPercent(3, 33.0));
        assertEquals(1, ByzantineConfig.countFromPercent(1, 100.0));
        assertEquals(0, ByzantineConfig.countFromPercent(0, 30.0));
        // pct = 100/3 (o double mais proximo, ACIMA de 100/3): n=3 da 2 (teto exato de 1,00000000000000007), nao 1
        double umTerco = 100.0 / 3;
        assertEquals(2, ByzantineConfig.countFromPercent(3, umTerco));
        for (int n = 1; n <= 300; n++) {
            assertEquals(oraculoExato(n, umTerco), ByzantineConfig.countFromPercent(n, umTerco), "n=" + n + " pct=100/3");
        }
        // texto decimal explicito: 33,33 < 1/3 < 33,34 (exato)
        assertEquals(4, ByzantineConfig.countFromPercent(10, 33.33));
        assertEquals(3, ByzantineConfig.countFromPercent(9, 33.33));
        assertEquals(4, ByzantineConfig.countFromPercent(9, 33.34));
    }

    @Test
    void entradas_invalidas_falham_alto() {
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromPercent(10, -0.1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromPercent(10, 100.1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromPercent(10, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromPercent(-1, 10.0));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromFraction(10, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromFraction(10, 11, 10));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.countFromFraction(10, -1, 10));
        assertEquals(10, ByzantineConfig.countFromPercent(10, 100.0));
        assertEquals(0, ByzantineConfig.countFromPercent(10, 0.0));
    }

    @Test
    void construtor_por_percentual_do_legado_usa_o_teto_exato_e_o_gerador_unico() {
        for (int n = 4; n <= 60; n++) {
            for (int f = 0; f <= n / 2; f++) {
                double pct = 100.0 * f / n;
                ByzantineConfig c = new ByzantineConfig(n, pct, EQ, 7L);
                int esperado = oraculoExato(n, pct);
                assertEquals(esperado, c.getByzantineCount(), "n=" + n + " f=" + f);
                assertEquals(ByzantineConfig.selectNested(7L, n, esperado), c.getByzantineValidatorIds(), "n=" + n + " f=" + f);
            }
        }
        // o construtor por contagem da f exato onde o legado por percentual da f+1 (n=7, f=1)
        assertEquals(2, new ByzantineConfig(7, 100.0 / 7, EQ, 7L).getByzantineCount());
        assertEquals(1, ByzantineConfig.withExactCount(7, 1, EQ, 7L).getByzantineCount());
    }

    // ---------- (3) gerador unico, aninhado ----------

    @Test
    void gerador_e_aninhado_deterministico_e_dentro_do_universo() {
        for (long seed : new long[] {1L, 2L, 5004L, 12345L, -7L}) {
            for (List<Integer> universo : List.of(ByzantineConfig.range(0, 10), ByzantineConfig.range(3, 7),
                    List.of(9, 2, 5, 11, 4), ByzantineConfig.range(0, 1))) {
                Set<Integer> anterior = Set.of();
                List<Integer> ordemAnterior = List.of();
                for (int f = 0; f <= universo.size(); f++) {
                    Set<Integer> ids = ByzantineConfig.selectNested(seed, universo, f);
                    assertEquals(f, ids.size());
                    assertTrue(universo.containsAll(ids), "so ids do universo: " + ids + " em " + universo);
                    assertTrue(ids.containsAll(anterior), "aninhado: " + anterior + " em " + ids);
                    // prefixo na ordem de sorteio (nao so contido)
                    List<Integer> ordem = new ArrayList<>(ids);
                    assertEquals(ordemAnterior, ordem.subList(0, ordemAnterior.size()));
                    assertEquals(ids, ByzantineConfig.selectNested(seed, universo, f), "mesma semente, mesmo conjunto");
                    anterior = ids;
                    ordemAnterior = ordem;
                }
            }
        }
    }

    @Test
    void gerador_unico_e_o_mesmo_caminho_do_hotstuff_e_do_construtor_legado() {
        for (long seed : new long[] {1L, 2L, 99L}) {
            for (int n : new int[] {4, 7, 10, 13}) {
                for (int f = 0; f <= n; f++) {
                    Set<Integer> hs = ByzantineConfig.withExactCount(n, f, EQ, seed).getByzantineValidatorIds();
                    assertEquals(ByzantineConfig.selectNested(seed, n, f), hs);
                    // legado por percentual: teto EXATO do double; o conjunto e o prefixo do gerador unico com essa contagem
                    double pct = 100.0 * f / n;
                    assertEquals(ByzantineConfig.selectNested(seed, n, oraculoExato(n, pct)),
                            new ByzantineConfig(n, pct, EQ, seed).getByzantineValidatorIds());
                    if (oraculoExato(n, pct) == f) {
                        assertEquals(hs, new ByzantineConfig(n, pct, EQ, seed).getByzantineValidatorIds());
                    }
                    assertEquals(hs, ByzantineConfig.forUniverse(n, ByzantineConfig.range(0, n), f, EQ, seed)
                            .getByzantineValidatorIds());
                }
            }
        }
        // universos diferentes (n diferente) podem dar conjuntos diferentes: o universo e parte da chave do sorteio
        boolean algumaDifere = false;
        for (long seed = 1; seed <= 20 && !algumaDifere; seed++) {
            algumaDifere = !ByzantineConfig.selectNested(seed, 10, 3).equals(ByzantineConfig.selectNested(seed, 7, 3));
        }
        assertTrue(algumaDifere);
    }

    @Test
    void gerador_recusa_universo_invalido_e_f_fora_do_universo() {
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.selectNested(1L, List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.selectNested(1L, List.of(1, 1, 2), 1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.selectNested(1L, List.of(1, 2, 3), 4));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.selectNested(1L, List.of(1, 2, 3), -1));
        assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.selectNested(1L, 0, 0));
    }

    // ---------- (2) fora da faixa: guarda comum ----------

    @Test
    void guarda_de_faixa_falha_alto_sem_flag_e_rotula_com_flag() {
        for (int n = 1; n <= 40; n++) {
            int limite = (n - 1) / 3;
            assertEquals(limite, ByzantineConfig.bftLimit(n));
            assertFalse(ByzantineConfig.requireWithinBftThreshold("t", n, limite, false), "no limite: dentro");
            assertFalse(ByzantineConfig.requireWithinBftThreshold("t", n, limite, true), "no limite com flag: nao rotula");
            final int nn = n;
            if (limite + 1 <= n) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> ByzantineConfig.requireWithinBftThreshold("t", nn, limite + 1, false));
                assertTrue(e.getMessage().contains("n=" + n + " f=" + (limite + 1)), e.getMessage());
                assertTrue(e.getMessage().contains("floor((n-1)/3)=" + limite), e.getMessage());
                assertTrue(e.getMessage().contains("--allow-beyond-bft-threshold=true"), e.getMessage());
                assertTrue(ByzantineConfig.requireWithinBftThreshold("t", n, limite + 1, true), "com flag: fora da faixa, rotular");
            }
            assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.requireWithinBftThreshold("t", nn, nn + 1, true));
            assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.requireWithinBftThreshold("t", nn, -1, true));
        }
        assertEquals(-1, ByzantineConfig.bftLimit(0), "floorDiv: n=0 nao trunca para 0");
        assertFalse(ByzantineConfig.parseAllowBeyondBftThreshold(null));
        assertFalse(ByzantineConfig.parseAllowBeyondBftThreshold("false"));
        assertTrue(ByzantineConfig.parseAllowBeyondBftThreshold("true"));
        for (String ruim : new String[] {"", "TRUE", "1", "sim", " true"}) {
            assertThrows(IllegalArgumentException.class, () -> ByzantineConfig.parseAllowBeyondBftThreshold(ruim), ruim);
        }
    }

    // ---------- (1b) countFromFraction sem overflow (BYZ-UNICA, rodada 4) ----------

    /** Oraculo INDEPENDENTE: teto de universe*num/den por divisao e resto em BigInteger (sem somar den-1). */
    private static int oraculoTeto(int universe, long num, long den) {
        BigInteger[] qr = BigInteger.valueOf(universe).multiply(BigInteger.valueOf(num))
                .divideAndRemainder(BigInteger.valueOf(den));
        return qr[0].add(qr[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE).intValueExact();
    }

    @Test
    void countFromFraction_nao_transborda_long_caso_do_critic() {
        // antes: (long) 2 * Long.MAX_VALUE = -2 e o teto dava 0
        assertEquals(2, ByzantineConfig.countFromFraction(2, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(1, ByzantineConfig.countFromFraction(2, Long.MAX_VALUE / 2, Long.MAX_VALUE)); // 2*(M/2)/M = 0,99.. -> 1
        assertEquals(1, ByzantineConfig.countFromFraction(1, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertEquals(1, ByzantineConfig.countFromFraction(1, 1, Long.MAX_VALUE));
    }

    @Test
    void countFromFraction_fronteiras() {
        assertEquals(0, ByzantineConfig.countFromFraction(100, 0, 1));          // num = 0
        assertEquals(0, ByzantineConfig.countFromFraction(100, 0, Long.MAX_VALUE));
        assertEquals(100, ByzantineConfig.countFromFraction(100, 7, 7));        // num = den
        assertEquals(100, ByzantineConfig.countFromFraction(100, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(37, ByzantineConfig.countFromFraction(37, 1, 1));          // den = 1
        assertEquals(0, ByzantineConfig.countFromFraction(0, 1, 1));            // universo = 0
        assertEquals(0, ByzantineConfig.countFromFraction(0, Long.MAX_VALUE, Long.MAX_VALUE));
        int m = Integer.MAX_VALUE;
        assertEquals(m, ByzantineConfig.countFromFraction(m, 1, 1));
        assertEquals(m, ByzantineConfig.countFromFraction(m, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(1 << 30, ByzantineConfig.countFromFraction(m, 1, 2));      // ceil((2^31-1)/2) = 2^30
        assertEquals(1 << 30, ByzantineConfig.countFromFraction(m, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1));
        assertEquals(m, ByzantineConfig.countFromFraction(m, Long.MAX_VALUE - 1, Long.MAX_VALUE)); // ceil(m*(1-eps)) = m
        assertEquals(1, ByzantineConfig.countFromFraction(m, 1, Long.MAX_VALUE));
    }

    @Test
    void countFromFraction_confere_com_oraculo_BigInteger_em_grid() {
        long M = Long.MAX_VALUE;
        long[][] fracoes = {
                {0, 1}, {1, 1}, {1, 2}, {1, 3}, {2, 3}, {1, 7}, {25, 100}, {30, 100}, {99, 100}, {100, 100},
                {1, M}, {M - 1, M}, {M, M}, {M / 2, M}, {M / 3, M}, {M / 2, M - 1}, {M - 2, M - 1},
                {1L << 62, 1L << 62}, {(1L << 62) - 1, 1L << 62}, {(1L << 61) + 1, 1L << 62}, {123456789012345L, 987654321098765L},
                {M / 7, M / 7}, {M / 7 - 1, M / 7}, {3, 1L << 40}, {(1L << 40) - 1, 1L << 40}
        };
        int comparados = 0;
        for (long[] f : fracoes) {
            for (int u = 0; u <= 200; u++) {
                assertEquals(oraculoTeto(u, f[0], f[1]), ByzantineConfig.countFromFraction(u, f[0], f[1]),
                        "u=" + u + " num=" + f[0] + " den=" + f[1]);
                comparados++;
            }
            for (int u : new int[] {1000, 65536, 1 << 20, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
                assertEquals(oraculoTeto(u, f[0], f[1]), ByzantineConfig.countFromFraction(u, f[0], f[1]),
                        "u=" + u + " num=" + f[0] + " den=" + f[1]);
                comparados++;
            }
        }
        // grid exaustivo de fracoes pequenas
        for (int u = 0; u <= 200; u++) {
            for (long den = 1; den <= 40; den++) {
                for (long num = 0; num <= den; num++) {
                    assertEquals(oraculoTeto(u, num, den), ByzantineConfig.countFromFraction(u, num, den),
                            "u=" + u + " num=" + num + " den=" + den);
                    comparados++;
                }
            }
        }
        assertTrue(comparados > 100_000, "grid: " + comparados);
    }

    @Test
    void chamador_HybridArchitectureValidation_25_sobre_100_segue_igual() {
        // o chamador usa countFromFraction(totalValidators = 20, 25, 100)
        assertEquals(5, ByzantineConfig.countFromFraction(20, 25, 100));
        for (int n = 0; n <= 200; n++) {
            assertEquals((n + 3) / 4, ByzantineConfig.countFromFraction(n, 25, 100), "n=" + n); // ceil(n/4)
        }
    }
}
