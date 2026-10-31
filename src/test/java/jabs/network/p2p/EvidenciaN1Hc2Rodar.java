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
 * Driver da evidencia de nao regressao da N1 com HASH DE IDENTIDADE CONSTANTE (criterio 7). NAO e teste.
 * Copia de {@link EvidenciaN1Rodar} (que nao foi alterada) com saidas separadas: {@code comandos-hc2.txt},
 * {@code hashes-hc2.txt}, {@code saida-hc2/}. Exige {@code JAVA_TOOL_OPTIONS} no ambiente do processo contendo
 * {@code -XX:hashCode=2} (variavel de ambiente herdada pelas JVMs filhas de {@code nao_regressao.py}); falha
 * alto se ausente. Alem dos hashes, registra por execucao quantas linhas "Picked up JAVA_TOOL_OPTIONS" ha no
 * log (stderr) e quantas ha nos CSV comparados (esperado: >0 nos logs, 0 nos CSV).
 *
 * <p>Uso (raiz do repo, apos {@code mvn -o -q test-compile}):
 * {@code JAVA_TOOL_OPTIONS="-XX:+UnlockExperimentalVMOptions -XX:hashCode=2"
 *  java -cp target/test-classes:target/classes jabs.network.p2p.EvidenciaN1Hc2Rodar BASE OUT}
 */
public final class EvidenciaN1Hc2Rodar {
    private EvidenciaN1Hc2Rodar() { }

    static final String COMMIT = "325f4e3db42864a2aa7e1e92e6bf22a706759436";
    static final Path EVID = Path.of("src/test/resources/nao-regressao/n1-conversao-n");
    static final int[] SEMENTES = {1, 2, 3};
    static final String[] LADOS = {"base-a", "base-b", "atual-a", "atual-b"};
    static final String[][] TERMINA = {
            {"ARQ", "ARQ:7:60"}, {"Casper", "MCO2:10:60"}, {"Casper", "MCO2:50:600"},
            {"Parlia", "AMBIFY:10:60"}, {"Parlia", "AMBIFY:12:60"}, {"Nakamoto", "OFFSET:40:20000"}};

    public static void main(String[] a) throws Exception {
        String jto = System.getenv("JAVA_TOOL_OPTIONS");
        if (jto == null || !jto.contains("-XX:hashCode=2")) {
            throw new IllegalStateException("JAVA_TOOL_OPTIONS sem -XX:hashCode=2: " + jto);
        }
        Path base = Path.of(a[0]).toAbsolutePath();
        Path out = Path.of(a[1]).toAbsolutePath();
        String head = sh(base, "git", "rev-parse", "HEAD").trim();
        if (!head.equals(COMMIT)) throw new IllegalStateException("baseline nao esta em " + COMMIT + ": " + head);
        if (!sh(base, "git", "status", "--porcelain").isBlank()) throw new IllegalStateException("baseline suja");
        Files.createDirectories(EVID.resolve("saida-hc2"));
        Path cmds = EVID.resolve("comandos-hc2.txt");
        Path hashes = EVID.resolve("hashes-hc2.txt");
        Files.writeString(cmds, "# baseline " + COMMIT + System.lineSeparator() + "# BASE=" + base
                + System.lineSeparator() + "# modo legacy (sem --n-semantics)" + System.lineSeparator()
                + "# ambiente do processo: JAVA_TOOL_OPTIONS=" + jto + System.lineSeparator());
        Files.writeString(hashes, "# sha256 normalizado (sem '#', sem colunas hash) | linhas de dados | pasta/csv"
                + System.lineSeparator() + "# comentarios: contagem de linhas '#' por CSV (nao entram no hash)"
                + System.lineSeparator() + "# JAVA_TOOL_OPTIONS=" + jto + System.lineSeparator()
                + "# PICKED_UP: lado | linhas 'Picked up JAVA_TOOL_OPTIONS' no log | idem nos CSV (esperado 0)"
                + System.lineSeparator());
        for (int s : SEMENTES) for (String[] c : TERMINA) rodar("hc2", c, s, base, out, 900, cmds, hashes);
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
        Path saida = EVID.resolve("saida-hc2").resolve(classe + "-" + caso[0] + "-" + nomeCaso + "-s" + semente + ".txt");
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(saida.toFile()).start();
        int rc = p.waitFor();
        Files.writeString(saida, "exit=" + rc + System.lineSeparator() + "tempo_total_s="
                + (System.currentTimeMillis() - t0) / 1000 + System.lineSeparator(), StandardOpenOption.APPEND);
        String[] partes = casoStr.split(":");
        for (String lado : LADOS) {
            Path dir = out.resolve(sub).resolve(partes[0] + "-" + partes[1] + "v-" + partes[2] + "s-" + lado);
            Path log = out.resolve(sub).resolve(dir.getFileName() + ".log");
            StringBuilder sb = new StringBuilder();
            List<Path> csvs = Files.isDirectory(dir) ? listar(dir) : List.of();
            if (csvs.isEmpty()) {
                sb.append("SEM_CSV 0 ").append(classe).append('/').append(nomeCaso).append("/s").append(semente)
                        .append('/').append(dir.getFileName()).append(System.lineSeparator());
            }
            long picadoCsv = 0;
            for (Path c : csvs) {
                String[] h = EvidenciaHashes.hashEDados(c);
                List<String> linhas = Files.readAllLines(c, StandardCharsets.UTF_8);
                long coment = linhas.stream().filter(l -> l.startsWith("#")).count();
                picadoCsv += linhas.stream().filter(l -> l.contains("Picked up")).count();
                sb.append(String.format("%s %4s %s/%s/s%d/%s/%s comentarios=%d%n", h[0], h[1], classe, nomeCaso,
                        semente, dir.getFileName(), dir.relativize(c), coment));
            }
            long picadoLog = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                    .filter(l -> l.startsWith("Picked up JAVA_TOOL_OPTIONS")).count() : -1;
            sb.append(String.format("PICKED_UP %s/%s/s%d/%s log=%d csv=%d%n", classe, nomeCaso, semente,
                    dir.getFileName(), picadoLog, picadoCsv));
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
