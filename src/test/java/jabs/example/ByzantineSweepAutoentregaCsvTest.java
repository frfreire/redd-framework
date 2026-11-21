package jabs.example;

import jabs.log.LinkLoadRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 (ADR-0015, decisao 2 do humano de 17/11/2026), granularidade declarada no CSV do {@link ByzantineSweep}:
 * {@code avgTraffic}/{@code stdTraffic} EXCLUEM a autoentrega; ela tem 2 colunas PROPRIAS e ADITIVAS no FIM
 * ({@code avgSelfDelivery_MB}, {@code stdSelfDelivery_MB}) so para os tipos que a separam (VOTING, POS, POSA, POW); DAGSPER
 * segue byte a byte o CSV da baseline 99d50cc (sem coluna e sem linha '#'); linhas '#' declaram semantica, semente, versao do
 * simulador e granularidade; conservacao: media antes de D2 = avgTraffic + avgSelfDelivery_MB (conferida contra o CSV bruto
 * por trial, que traz TrafficUntilFinalization e Cb_autoentrega_B por altura).
 */
class ByzantineSweepAutoentregaCsvTest {
    @TempDir
    Path tmp;

    /** Cabecalho de colunas da baseline 99d50cc, copiado literal (nao referencia a constante do codigo). */
    private static final String CABECALHO_BASELINE =
            "protocol,n,f,trials,avgPdv,stdPdv,avgFinalizationTime,stdFinalizationTime,avgConfirmationLatency,"
                    + "stdConfirmationLatency,avgBlockCount,stdBlockCount,avgTraffic,stdTraffic,avgEmpiricalBFT_pct,"
                    + "stdEmpiricalBFT_pct,secureCount";
    private static final String COLUNAS_NOVAS = ",avgSelfDelivery_MB,stdSelfDelivery_MB";
    private static final String[] SEPARAM = {"VOTING", "POS", "POSA", "POW"};

    /** Roda o sweep com 0 trials (so grava o cabecalho e falha alto em NAO_MEDIDO) e devolve o CSV bruto. */
    private String soCabecalho(String tipo, String... extra) throws IOException {
        Path csv = tmp.resolve("so-" + tipo + extra.length).resolve("s.csv");
        List<String> a = new ArrayList<>(List.of("--type=" + tipo, "--seed=12345" /* FIXTURE: sem padrao (ADR-0015 Q26-A) */, "--validators=10", "--max-byz=0", "--trials=0",
                "--duration=10", "--attack=WITHHOLD", "--n-semantics=legacy", "--out=" + csv));
        a.addAll(List.of(extra));
        assertThrows(IllegalStateException.class, () -> ByzantineSweep.run(a.toArray(new String[0])), tipo);
        return Files.readString(csv);
    }

    // ---------- cabecalho: colunas aditivas no FIM, so para quem separa ----------

    @Test
    void cabecalho_ganha_as_colunas_no_fim_so_para_quem_separa_a_autoentrega() {
        for (String tipo : SEPARAM) {
            assertTrue(ByzantineSweep.hasSelfDeliveryColumn(tipo), tipo);
            assertEquals(CABECALHO_BASELINE + COLUNAS_NOVAS, ByzantineSweep.header(tipo), tipo);
        }
        // DAGSPER ja filtrava a autoentrega: cabecalho identico ao da baseline, sem coluna nova
        assertFalse(ByzantineSweep.hasSelfDeliveryColumn("DAGSPER"));
        assertEquals(CABECALHO_BASELINE, ByzantineSweep.header("DAGSPER"));
        assertFalse(ByzantineSweep.hasSelfDeliveryColumn("INEXISTENTE"));
    }

    @Test
    void dagsper_sai_byte_a_byte_como_a_baseline_sem_coluna_e_sem_linha_de_comentario() throws IOException {
        assertEquals(CABECALHO_BASELINE + "\n", soCabecalho("DAGSPER"));
        // mesmo com --seed e modo nodes: so as linhas '#' de dimensionamento de antes; nenhuma de D2/semente
        String nodes = soCabecalho("DAGSPER", "--seed=7", "--n-semantics=nodes");
        assertFalse(nodes.contains("# D2 ") || nodes.contains("# Seed=") || nodes.contains("SelfDelivery"), nodes);
        assertTrue(nodes.endsWith(CABECALHO_BASELINE + "\n"), nodes);
    }

    @Test
    void tipos_que_separam_gravam_comentarios_depois_cabecalho_com_colunas_no_fim() throws IOException {
        for (String tipo : SEPARAM) {
            List<String> linhas = soCabecalho(tipo, "--seed=7").lines().toList();
            assertEquals(3, linhas.size(), tipo + ": 2 linhas '#' + cabecalho: " + linhas);
            assertTrue(linhas.get(0).startsWith("# D2 "), linhas.get(0));
            assertTrue(linhas.get(1).startsWith("# Seed=7 "), linhas.get(1));
            assertEquals(CABECALHO_BASELINE + COLUNAS_NOVAS, linhas.get(2), tipo);
            assertTrue(linhas.get(2).endsWith(COLUNAS_NOVAS), "as colunas novas sao as ULTIMAS: " + tipo);
        }
        // sem override, a semente escrita pelo helper (12345, fixture; o sweep nao tem mais padrao) e a declarada
        assertTrue(soCabecalho("VOTING").contains("# Seed=12345 "));
    }

    // ---------- comentarios: semantica, conservacao, granularidade, semente e versao ----------

    @Test
    void comentario_declara_a_semantica_a_conservacao_a_granularidade_a_semente_e_a_versao() {
        String[] c = ByzantineSweep.selfDeliveryComments("42");
        assertEquals(2, c.length);
        String semantica = c[0];
        assertTrue(semantica.startsWith("#"), "linha '#': os scripts a ignoram");
        for (String trecho : new String[] {"avgTraffic e stdTraffic EXCLUEM a autoentrega", "coluna PROPRIA e ADITIVA",
                "avgSelfDelivery_MB", "stdSelfDelivery_MB", "-1 = nao medido, nunca zero",
                "avgTraffic antes de D2 = avgTraffic + avgSelfDelivery_MB", "MB por bloco"}) {
            assertTrue(semantica.contains(trecho), "falta '" + trecho + "' em: " + semantica);
        }
        String proveniencia = c[1];
        assertTrue(proveniencia.startsWith("# Seed=42 "), proveniencia);
        assertTrue(proveniencia.contains("SimulatorVersion=" + LinkLoadRecorder.SIMULATOR_VERSION + ";"), proveniencia);
        assertTrue(proveniencia.contains("Granularity=") && proveniencia.contains("media por bloco")
                && proveniencia.contains("primeiro cada altura") && proveniencia.contains("MB"), proveniencia);
        assertTrue(proveniencia.contains("Seed + n*1000 + indice do trial"), proveniencia);
        assertNotEquals(ByzantineSweep.selfDeliveryComments("1")[1], proveniencia, "a semente declarada e a pedida");
    }

    // ---------- sentinela: -1 e nao medido, nunca zero ----------

    @Test
    void sentinela_menos_um_nunca_zero() {
        assertEquals("-1", ByzantineSweep.selfFmt(-1.0));
        assertEquals("0.000000", ByzantineSweep.selfFmt(0.0), "zero medido continua zero");
        assertEquals("0.114704", ByzantineSweep.selfFmt(0.1147041));
        double[] nada = ByzantineSweep.selfDeliveryStats(0.0, 0.0, 0);
        assertEquals(-1.0, nada[0]);
        assertEquals(-1.0, nada[1]);
        // 1 trial medido: media = o valor, desvio 0 (mesma convencao das demais colunas); 2 trials: desvio amostral
        double[] um = ByzantineSweep.selfDeliveryStats(0.5, 0.25, 1);
        assertEquals(0.5, um[0], 1e-12);
        assertEquals(0.0, um[1], 1e-12);
        double[] dois = ByzantineSweep.selfDeliveryStats(0.1 + 0.3, 0.1 * 0.1 + 0.3 * 0.3, 2);
        assertEquals(0.2, dois[0], 1e-12);
        assertEquals(Math.sqrt(0.02), dois[1], 1e-12);
    }

    // ---------- conservacao contra o CSV bruto (simulacao real) ----------

    @Test
    void conservacao_antigo_igual_novo_mais_coluna_contra_o_csv_bruto_por_trial() throws IOException {
        Path out = tmp.resolve("e2e").resolve("s.csv");
        ByzantineSweep.run(new String[] {"--type=VOTING", "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--validators=10", "--max-byz=1", "--trials=1", "--duration=40",
                "--attack=EQUIVOCATION", "--seed=1", "--out=" + out});
        List<String> linhas = Files.readAllLines(out);
        assertTrue(linhas.get(0).startsWith("# D2 ") && linhas.get(1).startsWith("# Seed=1 "), linhas.toString());
        List<String> dados = linhas.stream().filter(l -> !l.startsWith("#")).toList();
        String[] cab = dados.get(0).split(",");
        assertEquals(CABECALHO_BASELINE + COLUNAS_NOVAS, dados.get(0));
        assertEquals(3, dados.size(), "cabecalho + f=0 + f=1");
        int iTraf = List.of(cab).indexOf("avgTraffic");
        int iStdTraf = List.of(cab).indexOf("stdTraffic");
        int iBlocos = List.of(cab).indexOf("avgBlockCount");
        int iAuto = List.of(cab).indexOf("avgSelfDelivery_MB");
        int iStdAuto = List.of(cab).indexOf("stdSelfDelivery_MB");
        assertEquals(cab.length - 2, iAuto, "avgSelfDelivery_MB e a penultima coluna");
        assertEquals(cab.length - 1, iStdAuto, "stdSelfDelivery_MB e a ultima coluna");
        for (int f = 0; f <= 1; f++) {
            String[] linha = dados.get(1 + f).split(",");
            assertEquals(cab.length, linha.length, "mesma quantidade de celulas do cabecalho: f=" + f);
            // CSV bruto do trial (um valor por altura, no que finalizou primeiro): TrafficUntilFinalization (SEM autoentrega)
            // e Cb_autoentrega_B (so a autoentrega); antigo = soma das duas
            Path bruto = out.getParent().resolve("tmp/VOTING/10n-" + f + "-0.csv");
            List<String> br = Files.readAllLines(bruto).stream().filter(l -> !l.startsWith("#")).toList();
            List<String> bc = List.of(br.get(0).split(","));
            int iT = bc.indexOf("TrafficUntilFinalization");
            int iA = bc.indexOf("Cb_autoentrega_B");
            assertTrue(iT >= 0 && iA >= 0, bc.toString());
            double somaSem = 0;
            double somaAuto = 0;
            int alturas = 0;
            for (String l : br.subList(1, br.size())) {
                String[] c = l.split(",");
                somaSem += Long.parseLong(c[iT]);
                somaAuto += Long.parseLong(c[iA]);
                alturas++;
            }
            assertTrue(alturas > 0, "o trial finalizou blocos");
            assertEquals(alturas, Math.round(Double.parseDouble(linha[iBlocos])), "mesma populacao: um valor por altura");
            double mb = 1024.0 * 1024.0;
            double novo = Double.parseDouble(linha[iTraf]);
            double coluna = Double.parseDouble(linha[iAuto]);
            double antigo = (somaSem + somaAuto) / alturas / mb;
            assertTrue(coluna > 0, "autoentrega medida (>0) para PBFT: f=" + f);
            assertEquals(somaSem / alturas / mb, novo, 1.5e-6, "avgTraffic exclui a autoentrega: f=" + f);
            assertEquals(somaAuto / alturas / mb, coluna, 1.5e-6, "a celula da coluna e a autoentrega medida: f=" + f);
            assertEquals(antigo, novo + coluna, 2.5e-6, "conservacao: antigo = novo + coluna: f=" + f);
            assertEquals(0.0, Double.parseDouble(linha[iStdTraf]), 1e-12, "1 trial: desvio 0");
            assertEquals(0.0, Double.parseDouble(linha[iStdAuto]), 1e-12, "1 trial: desvio da coluna 0 (nao -1: foi medida)");
        }
    }
}
