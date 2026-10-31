package jabs.network.p2p;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utilitario da evidencia de nao regressao (ADR-0011, CA-01; ver
 * {@code src/test/resources/nao-regressao/p2p-1/README.txt}). NAO e teste: so imprime, para cada CSV sob os
 * diretorios dados, {@code <sha256> <linhas-de-dados> <diretorio>/<csv>}.
 *
 * <p>Normalizacao identica a de {@code scripts/jabs/nao_regressao.py} ({@code ler}): descarta linhas vazias e
 * linhas que comecam com '#', e descarta as COLUNAS cujo cabecalho contem "hash" (sem diferenciar caixa);
 * delimitador ';' se houver mais ';' que ',' na primeira linha. O sha256 cobre cabecalho + linhas restantes,
 * campos separados por 0x1F e linhas por '\n'. Dois CSV com o mesmo sha256 sao IDENTICOS para o script.
 *
 * <p>Uso: {@code java -cp target/test-classes jabs.network.p2p.EvidenciaHashes DIR [DIR...]}
 */
public final class EvidenciaHashes {
    private EvidenciaHashes() { }

    static List<String[]> ler(Path p) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> linhas = Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        if (linhas.isEmpty()) return rows;
        String primeira = linhas.get(0);
        char delim = count(primeira, ';') > count(primeira, ',') ? ';' : ',';
        for (String l : linhas) rows.add(split(l, delim));
        return rows;
    }

    private static long count(String s, char c) { return s.chars().filter(x -> x == c).count(); }

    /** Divide respeitando aspas duplas (como csv.reader do Python no caso comum). */
    private static String[] split(String linha, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (aspas) {
                if (c == '"' && i + 1 < linha.length() && linha.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') aspas = false;
                else cur.append(c);
            } else if (c == '"') aspas = true;
            else if (c == delim) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String[] hashEDados(Path p) throws Exception {
        List<String[]> rows = ler(p);
        if (rows.isEmpty()) return new String[]{"-", "0"};
        String[] cab = rows.get(0);
        List<Integer> manter = new ArrayList<>();
        for (int i = 0; i < cab.length; i++) if (!cab[i].toLowerCase().contains("hash")) manter.add(i);
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            for (int k = 0; k < manter.size(); k++) {
                int i = manter.get(k);
                if (k > 0) sb.append('\u001f');
                if (i < r.length) sb.append(r[i]);
            }
            sb.append('\n');
        }
        byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        return new String[]{HexFormat.of().formatHex(d), String.valueOf(rows.size() - 1)};
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            Path base = Path.of(a);
            List<Path> csvs;
            try (Stream<Path> s = Files.walk(base)) {
                csvs = s.filter(x -> x.toString().endsWith(".csv")).sorted().toList();
            }
            if (csvs.isEmpty()) System.out.println("SEM_CSV 0 " + base.getFileName());
            for (Path c : csvs) {
                String[] h = hashEDados(c);
                System.out.printf("%s %4s %s/%s%n", h[0], h[1], base.getFileName(), base.relativize(c));
            }
        }
    }
}
