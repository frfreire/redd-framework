package jabs.network.p2p;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumo da evidencia C1-casper (NAO e teste). Para cada caso e semente das pastas {@code pedido/} e
 * {@code suplementar/} sob DIR (saida de {@link EvidenciaCasperRodar}), imprime base-a x atual-a: Tb_avg, Cb_avg,
 * blocos finalizados (linha "Blocks: G generated, F finalized" do log; F = SimulationMetrics#getBlockCount) e
 * linhas do CSV por bloco (alturas finalizadas pelo primeiro no), e se base-a x base-b e atual-a x atual-b
 * coincidem (sha256 em hashes.txt). Uso: {@code java -cp target/test-classes jabs.network.p2p.EvidenciaCasperResumo DIR}
 */
public final class EvidenciaCasperResumo {
    private EvidenciaCasperResumo() { }

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        String[][] casos = {{"pedido", "10", "60"}, {"pedido", "20", "300"}, {"pedido", "50", "600"},
                {"suplementar", "10", "600"}, {"suplementar", "20", "600"}, {"suplementar", "30", "600"},
                {"suplementar", "40", "600"}, {"suplementar", "100", "600"}};
        System.out.println("classe       n   dur sem | Tb_base    Tb_atual   | Cb_base   Cb_atual  | gerados fin_base fin_atual | linhas_csv base atual | det(b,a)");
        for (String[] c : casos) {
            for (int s = 1; s <= 3; s++) {
                Path d = dir.resolve(c[0]).resolve("MCO2_" + c[1] + "_" + c[2]).resolve("s" + s);
                String pre = d.toString() + "/MCO2-" + c[1] + "v-" + c[2] + "s-";
                String[] rb = resultado(Path.of(pre + "base-a"), c[1]);
                String[] ra = resultado(Path.of(pre + "atual-a"), c[1]);
                boolean detB = hash(Path.of(pre + "base-a"), c[1]).equals(hash(Path.of(pre + "base-b"), c[1]));
                boolean detA = hash(Path.of(pre + "atual-a"), c[1]).equals(hash(Path.of(pre + "atual-b"), c[1]));
                System.out.printf("%-11s %3s %5s %d | %-10s %-10s | %-9s %-9s | %-7s %-8s %-9s | %-9s %-5s %-5s | %s,%s%n",
                        c[0], c[1], c[2], s, rb[0], ra[0], rb[1], ra[1], ra[3], rb[2], ra[2], "", rb[4], ra[4],
                        detB, detA);
            }
        }
    }

    /** {Tb, Cb, finalizados, gerados, linhasCsv}. */
    static String[] resultado(Path dir, String n) throws Exception {
        List<String> res = Files.readAllLines(dir.resolve("mvp-results.csv"));
        String[] cols = res.get(res.size() - 1).split(";");
        String log = Files.readString(Path.of(dir + ".log"));
        Matcher m = Pattern.compile("Blocks: (\\d+) generated, (\\d+) finalized").matcher(log);
        String gerados = "?", fin = "?";
        if (m.find()) { gerados = m.group(1); fin = m.group(2); }
        long linhas = Files.readAllLines(dir.resolve("mco2-" + n + "v-t0.csv")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).count() - 1;
        return new String[]{cols[3], cols[5], fin, gerados, String.valueOf(linhas)};
    }

    static String hash(Path dir, String n) throws Exception {
        String[] h = EvidenciaHashes.hashEDados(dir.resolve("mco2-" + n + "v-t0.csv"));
        String[] r = EvidenciaHashes.hashEDados(dir.resolve("mvp-results.csv"));
        List<String> l = new ArrayList<>(List.of(h[0], r[0]));
        return String.join("/", l);
    }
}
