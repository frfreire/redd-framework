package jabs.network.p2p;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Driver da evidencia de nao regressao da N1 (criterio 7, modo legacy contra 325f4e3). NAO e teste.
 * Ver {@code src/test/resources/nao-regressao/n1-conversao-n/README.txt}. Copia de {@link EvidenciaRodar}
 * com baseline, pasta de evidencia e casos parametrizados para a N1; nao altera {@code EvidenciaRodar}.
 *
 * <p>Roda {@code scripts/jabs/nao_regressao.py} UM CASO POR VEZ e UMA SEMENTE POR VEZ (4 JVMs simultaneas:
 * base-a, base-b, atual-a, atual-b), em sequencia, SEM {@code --n-semantics} (modo legacy, o padrao).
 *
 * <p>Uso (raiz do repo, apos {@code mvn -o compile test-compile}):
 * {@code java -cp target/test-classes:target/classes jabs.network.p2p.EvidenciaN1Rodar BASE OUT [PASTA CMDS HASHES]}
 */
public final class EvidenciaN1Rodar {
    private EvidenciaN1Rodar() { }

    static final String COMMIT = "325f4e3db42864a2aa7e1e92e6bf22a706759436";
    static final Path EVID = Path.of("src/test/resources/nao-regressao/n1-conversao-n");
    static final int[] SEMENTES = {1, 2, 3};
    static final String[] LADOS = {"base-a", "base-b", "atual-a", "atual-b"};

    /** Todos terminam na baseline (n e duracao conforme os casos validados em P2P-1/C1). */
    static final String[][] TERMINA = {
            {"ARQ", "ARQ:7:60"}, {"Casper", "MCO2:10:60"}, {"Casper", "MCO2:50:600"},
            {"Parlia", "AMBIFY:10:60"}, {"Parlia", "AMBIFY:12:60"}, {"Nakamoto", "OFFSET:40:20000"}};

    /** Pasta/arquivos de evidencia; a rodada 2 passa {@code saida-r2 comandos-r2.txt hashes-r2.txt} (a 1 e o padrao). */
    static String pastaSaida = "saida-final";
    static String arqComandos = "comandos.txt";
    static String arqHashes = "hashes.txt";

    public static void main(String[] a) throws Exception {
        Path base = Path.of(a[0]).toAbsolutePath();
        Path out = Path.of(a[1]).toAbsolutePath();
        if (a.length >= 5) {
            pastaSaida = a[2];
            arqComandos = a[3];
            arqHashes = a[4];
        }
        String head = sh(base, "git", "rev-parse", "HEAD").trim();
        if (!head.equals(COMMIT)) throw new IllegalStateException("baseline nao esta em " + COMMIT + ": " + head);
        if (!sh(base, "git", "status", "--porcelain").isBlank()) throw new IllegalStateException("baseline suja");
        Files.createDirectories(EVID.resolve(pastaSaida));
        Path cmds = EVID.resolve(arqComandos);
        Path hashes = EVID.resolve(arqHashes);
        Files.writeString(cmds, "# baseline " + COMMIT + System.lineSeparator() + "# BASE=" + base
                + System.lineSeparator() + "# modo legacy (sem --n-semantics)" + System.lineSeparator());
        Files.writeString(hashes, "# sha256 normalizado (sem '#', sem colunas hash) | linhas de dados | pasta/csv"
                + System.lineSeparator() + "# comentarios: contagem de linhas '#' por CSV (nao entram no hash)"
                + System.lineSeparator());
        for (int s : SEMENTES) for (String[] c : TERMINA) rodar("termina", c, s, base, out, 900, cmds, hashes);
        System.out.println("fim");
    }

    static void rodar(String classe, String[] caso, int semente, Path base, Path out, int timeoutS,
                      Path cmds, Path hashes) throws Exception {
        String casoStr = caso[1];
        String nomeCaso = casoStr.replace(':', '_');
        String sub = classe + "/" + nomeCaso + "/s" + semente;
        List<String> cmd = new ArrayList<>(List.of("python3", "scripts/jabs/nao_regressao.py",
                "--base", base.toString(), "--atual", ".", "--casos", casoStr, "--semente", String.valueOf(semente),
                "--timeout", String.valueOf(timeoutS), "--saida", out.resolve(sub).toString()));
        Files.writeString(cmds, String.join(" ", cmd) + System.lineSeparator(), StandardOpenOption.APPEND);
        Path saida = EVID.resolve(pastaSaida).resolve(classe + "-" + caso[0] + "-" + nomeCaso + "-s" + semente + ".txt");
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(saida.toFile()).start();
        int rc = p.waitFor();
        Files.writeString(saida, "exit=" + rc + System.lineSeparator() + "tempo_total_s="
                + (System.currentTimeMillis() - t0) / 1000 + System.lineSeparator(), StandardOpenOption.APPEND);
        String[] partes = casoStr.split(":");
        for (String lado : LADOS) {
            Path dir = out.resolve(sub).resolve(partes[0] + "-" + partes[1] + "v-" + partes[2] + "s-" + lado);
            StringBuilder sb = new StringBuilder();
            List<Path> csvs = Files.isDirectory(dir) ? listar(dir) : List.of();
            if (csvs.isEmpty()) {
                sb.append("SEM_CSV 0 ").append(classe).append('/').append(nomeCaso).append("/s").append(semente)
                        .append('/').append(dir.getFileName()).append(System.lineSeparator());
            }
            for (Path c : csvs) {
                String[] h = EvidenciaHashes.hashEDados(c);
                long coment = Files.readAllLines(c, StandardCharsets.UTF_8).stream()
                        .filter(l -> l.startsWith("#")).count();
                sb.append(String.format("%s %4s %s/%s/s%d/%s/%s comentarios=%d%n", h[0], h[1], classe, nomeCaso,
                        semente, dir.getFileName(), dir.relativize(c), coment));
            }
            Files.writeString(hashes, sb.toString(), StandardOpenOption.APPEND);
        }
        System.out.println(classe + " " + casoStr + " s" + semente + " exit=" + rc);
    }

    static List<Path> listar(Path dir) throws IOException {
        try (var st = Files.walk(dir)) {
            return st.filter(x -> x.toString().endsWith(".csv")).sorted().toList();
        }
    }

    static String sh(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        return o;
    }
}
