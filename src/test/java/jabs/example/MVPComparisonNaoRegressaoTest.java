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

    @Test
    void arqMesmaSaidaQueABaseline45eb3e7(@TempDir Path tmp) throws Exception {
        MVPComparison.main(new String[] {"--protocols=ARQ", "--validators=7", "--trials=1", "--duration=60",
                "--seed=1", "--output=" + tmp});
        confere(Files.readString(tmp.resolve("arq-7v-t0.csv")), referencia("ARQ-7v-60s/base-a/arq-7v-t0.csv"),
                "ARQ n=7 seed=1 (arq-7v-t0.csv)");
        confere(Files.readString(tmp.resolve("mvp-results.csv")), referencia("ARQ-7v-60s/base-a/mvp-results.csv"),
                "ARQ n=7 seed=1 (mvp-results.csv)");
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
        confere(Files.readString(tmp.resolve("arq-7v-t0.csv")), referencia("ARQ-7v-60s/base-a/arq-7v-t0.csv"),
                "ARQ via '  arq '");
    }

    @Test
    void normalizacaoIgnoraSoHashELinhasComentario() {
        String a = "#c1\nTime,BlockHashCode,X\n1,111,5\n2,222,6\n";
        String b = "#outro\nTime,BlockHashCode,X\n1,999,5\n2,888,6\n";
        assertEquals(normaliza(a), normaliza(b));
        assertFalse(normaliza(a).equals(normaliza(b.replace(",6\n", ",7\n"))), "valor de coluna comum diferente tem de divergir");
    }
}
