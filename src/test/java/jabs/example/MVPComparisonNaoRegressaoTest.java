package jabs.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nao regressao (criterio 7) do que a tarefa D3a-2a-2c-4 tocou diretamente no {@link MVPComparison}: a normalizacao
 * de nome de protocolo ({@code normalizeProtocol}: trim + maiusculas, usada em parse, switch e descricao) e a assinatura
 * de {@code runProtocol}. Roda ARQ e TENDERMINT com semente fixa e compara o CSV com a saida da BASELINE 45eb3e7,
 * arquivada em {@code src/test/resources/nao-regressao/2c-4-hotstuff-mvpcomparison/} (README.txt: comando exato,
 * estado da arvore e resultado de base x atual).
 *
 * <p>Normalizacao: a mesma de {@code scripts/jabs/nao_regressao.py} (linhas '#' e colunas cujo nome contem "hash" sao
 * ignoradas; o hashCode de objeto varia por execucao). Todo o resto, inclusive as casas decimais, tem de ser igual.
 *
 * <p>Os valores {@code --tm-*} abaixo sao FIXTURE DE TESTE (nao sao calibracao nem valor de pesquisa). Limite: este
 * teste cobre ARQ e TENDERMINT, nao os demais protocolos (esses ficam com o {@code nao_regressao.py}, ver README).
 */
class MVPComparisonNaoRegressaoTest {
    private static final String RAIZ = "/nao-regressao/2c-4-hotstuff-mvpcomparison/s1/teste/";

    /** FIXTURE DE TESTE, identica a usada para gerar a referencia (ver README.txt). */
    private static final String[] TM_FIXTURE = {
            "--tm-propose-timeout=1.0", "--tm-propose-delta=0.5", "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
            "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5", "--tm-batch-size=10", "--tm-tx-size=512",
            "--tm-signature-size=64", "--tm-proposer-signature-size=80", "--tm-message-overhead=10"};

    private static String referencia(String caminho) throws IOException {
        try (InputStream in = MVPComparisonNaoRegressaoTest.class.getResourceAsStream(RAIZ + caminho)) {
            assertNotNull(in, "referencia ausente: " + RAIZ + caminho);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Mesma regra de {@code nao_regressao.ler}: descarta '#' e vazias; descarta colunas "hash" pelo cabecalho. */
    static List<List<String>> normaliza(String csv) {
        List<String> linhas = new ArrayList<>();
        for (String l : csv.split("\\R")) {
            if (!l.isBlank() && !l.startsWith("#")) {
                linhas.add(l);
            }
        }
        assertFalse(linhas.isEmpty(), "CSV sem cabecalho");
        String sep = count(linhas.get(0), ';') > count(linhas.get(0), ',') ? ";" : ",";
        String[] cab = linhas.get(0).split(sep, -1);
        List<Integer> manter = new ArrayList<>();
        for (int i = 0; i < cab.length; i++) {
            if (!cab[i].toLowerCase().contains("hash")) {
                manter.add(i);
            }
        }
        List<List<String>> out = new ArrayList<>();
        for (String l : linhas) {
            String[] c = l.split(sep, -1);
            List<String> r = new ArrayList<>();
            for (int i : manter) {
                if (i < c.length) {
                    r.add(c[i]);
                }
            }
            out.add(r);
        }
        return out;
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }

    private static void confere(String csvGerado, String csvReferencia, String rotulo) {
        List<List<String>> g = normaliza(csvGerado);
        List<List<String>> r = normaliza(csvReferencia);
        assertTrue(r.size() >= 2, rotulo + ": a referencia precisa ter ao menos 1 linha de dados alem do cabecalho");
        assertEquals(r.get(0), g.get(0), rotulo + ": cabecalho");
        assertEquals(r.size(), g.size(), rotulo + ": numero de linhas");
        for (int i = 1; i < r.size(); i++) {
            assertEquals(r.get(i), g.get(i), rotulo + ": linha " + i);
        }
    }

    /**
     * D2 (ADR-0015, registro de 10/11/2026): a autoentrega do PBFT saiu do C_B e foi para a coluna propria
     * {@code Cb_autoentrega_B} (ao FIM do CSV por trial). A referencia (HEAD antes do D2) continua sendo a verdade da
     * DINAMICA e da CONSERVACAO do byte: o gerado, sem a coluna nova, e identico a ela em toda coluna que nao e de
     * trafego, e {@code TrafficUntilFinalization(ref) == TrafficUntilFinalization(gerado) + Cb_autoentrega_B(gerado)} em
     * cada linha. {@code Cb_AvgTraffic_MB} (media corrente de trafego) e {@code Cb_avg/Cb_std} (agregado) sao derivados do
     * trafego: so podem CAIR; {@code Tb_*} e {@code Tt_*} do agregado sao identicos.
     */
    private static void confereComAutoentrega(String csvGerado, String csvReferencia, String rotulo) {
        List<List<String>> g = normaliza(csvGerado);
        List<List<String>> r = normaliza(csvReferencia);
        assertEquals(r.get(0).size() + 1, g.get(0).size(), rotulo + ": uma coluna a mais");
        assertEquals("Cb_autoentrega_B", g.get(0).get(g.get(0).size() - 1), rotulo + ": a coluna nova e a ULTIMA");
        assertEquals(r.get(0), g.get(0).subList(0, r.get(0).size()), rotulo + ": colunas existentes: nome e posicao");
        assertEquals(r.size(), g.size(), rotulo + ": numero de linhas");
        int iTraf = r.get(0).indexOf("TrafficUntilFinalization");
        int iAvg = r.get(0).indexOf("Cb_AvgTraffic_MB");
        int iCons = r.get(0).indexOf("TrafficConsensusUntilFinalization");
        int iAuto = g.get(0).size() - 1;
        assertTrue(iTraf >= 0 && iAvg >= 0, rotulo);
        for (int i = 1; i < r.size(); i++) {
            for (int c = 0; c < r.get(i).size(); c++) {
                if (c == iTraf) {
                    assertEquals(Long.parseLong(r.get(i).get(c)),
                            Long.parseLong(g.get(i).get(c)) + Long.parseLong(g.get(i).get(iAuto)),
                            rotulo + ": linha " + i + ": o byte mudou de coluna, nao sumiu");
                } else if (c == iCons) {
                    // so o TOTAL da autoentrega e exposto por linha; a parte so-consenso dela saiu do C_B_consenso: so cai
                    assertTrue(Long.parseLong(g.get(i).get(c)) <= Long.parseLong(r.get(i).get(c)),
                            rotulo + ": linha " + i + ": C_B_consenso so pode cair");
                } else if (c == iAvg) {
                    assertTrue(Double.parseDouble(g.get(i).get(c)) <= Double.parseDouble(r.get(i).get(c)) + 1e-12,
                            rotulo + ": linha " + i + ": media de trafego so pode cair");
                } else {
                    assertEquals(r.get(i).get(c), g.get(i).get(c), rotulo + ": linha " + i + " coluna " + r.get(0).get(c));
                }
            }
        }
    }

    /** D2: {@code mvp-results.csv} do ARQ: so {@code Cb_avg}/{@code Cb_std} podem diferir (e so cair). */
    private static void confereAgregadoComAutoentrega(String csvGerado, String csvReferencia, String rotulo) {
        List<List<String>> g = normaliza(csvGerado);
        List<List<String>> r = normaliza(csvReferencia);
        assertEquals(r.get(0), g.get(0), rotulo + ": cabecalho (mvp-results.csv nao ganha coluna)");
        assertEquals(r.size(), g.size(), rotulo + ": numero de linhas");
        for (int i = 1; i < r.size(); i++) {
            for (int c = 0; c < r.get(i).size(); c++) {
                String col = r.get(0).get(c);
                if (col.equals("Cb_avg")) {
                    assertTrue(Double.parseDouble(g.get(i).get(c)) < Double.parseDouble(r.get(i).get(c)),
                            rotulo + ": Cb_avg cai (a autoentrega do PBFT deixou de ser C_B)");
                } else if (!col.equals("Cb_std")) {
                    assertEquals(r.get(i).get(c), g.get(i).get(c), rotulo + ": " + col + " identico");
                }
            }
        }
    }

    @Test
    void arqMesmaSaidaQueABaseline45eb3e7(@TempDir Path tmp) throws Exception {
        MVPComparison.main(new String[] {"--protocols=ARQ", "--validators=7", "--trials=1", "--duration=60",
                "--seed=1", "--output=" + tmp});
        confereComAutoentrega(Files.readString(tmp.resolve("arq-7v-t0.csv")),
                referencia("ARQ-7v-60s/base-a/arq-7v-t0.csv"), "ARQ n=7 seed=1 (arq-7v-t0.csv)");
        confereAgregadoComAutoentrega(Files.readString(tmp.resolve("mvp-results.csv")),
                referencia("ARQ-7v-60s/base-a/mvp-results.csv"), "ARQ n=7 seed=1 (mvp-results.csv)");
    }

    @Test
    void tendermintFixtureMesmaSaidaQueABaseline45eb3e7(@TempDir Path tmp) throws Exception {
        List<String> a = new ArrayList<>(List.of("--protocols=TENDERMINT", "--validators=4", "--trials=1",
                "--duration=5", "--seed=1", "--output=" + tmp));
        a.addAll(List.of(TM_FIXTURE));
        MVPComparison.main(a.toArray(new String[0]));
        confere(Files.readString(tmp.resolve("tendermint-4v-t0.csv")),
                referencia("TENDERMINT-4v-5s/base-a/tendermint-4v-t0.csv"), "TENDERMINT n=4 seed=1 (tendermint-4v-t0.csv)");
        confere(Files.readString(tmp.resolve("mvp-results.csv")),
                referencia("TENDERMINT-4v-5s/base-a/mvp-results.csv"), "TENDERMINT n=4 seed=1 (mvp-results.csv)");
    }

    @Test
    void protocoloEmMinusculasEComEspacosEquivaleAoCanonico(@TempDir Path tmp) throws Exception {
        // A normalizacao compartilhada (trim + maiusculas) e o que o diff de 45eb3e7 tocou: "  arq " tem de rodar como ARQ.
        MVPComparison.main(new String[] {"--protocols=  arq ", "--validators=7", "--trials=1", "--duration=60",
                "--seed=1", "--output=" + tmp});
        confereComAutoentrega(Files.readString(tmp.resolve("arq-7v-t0.csv")),
                referencia("ARQ-7v-60s/base-a/arq-7v-t0.csv"), "ARQ via '  arq '");
    }

    @Test
    void normalizacaoIgnoraSoHashELinhasComentario() {
        String a = "#c1\nTime,BlockHashCode,X\n1,111,5\n2,222,6\n";
        String b = "#outro\nTime,BlockHashCode,X\n1,999,5\n2,888,6\n";
        assertEquals(normaliza(a), normaliza(b));
        assertFalse(normaliza(a).equals(normaliza(b.replace(",6\n", ",7\n"))), "valor de coluna comum diferente tem de divergir");
    }
}
