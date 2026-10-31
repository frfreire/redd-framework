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
 * Driver da evidencia de nao regressao do CA-01 (ADR-0011, tarefa P2P-1). NAO e teste.
 * Ver {@code src/test/resources/nao-regressao/p2p-1/README.txt}.
 *
 * <p>Roda {@code scripts/jabs/nao_regressao.py} UM CASO POR VEZ e UMA SEMENTE POR VEZ (4 JVMs simultaneas:
 * base-a, base-b, atual-a, atual-b), em sequencia, sem outro trabalho pesado na maquina. Motivo medido: com
 * 12 ou 40 JVMs simultaneas o Nakamoto/OFFSET diverge ate entre base-a e base-b (a baseline e sensivel a
 * carga), o que mascara/imita regressao; com 4 JVMs deu IDENTICO.
 *
 * <p>Uso (raiz do repo, apos {@code mvn -o compile test-compile}):
 * {@code java -cp target/test-classes jabs.network.p2p.EvidenciaRodar BASE OUT [termina|trava|todos]}
 * onde BASE e o worktree da baseline e703e3d (compilado, com target/cp.txt) e OUT e o diretorio de
 * trabalho dos CSV (fora do repo; os hashes e as saidas de texto ficam no repo).
 */
public final class EvidenciaRodar {
    private EvidenciaRodar() { }

    static final String COMMIT = "e703e3d47da726f5fbcf94657f574db617a59023";
    static final Path EVID = Path.of("src/test/resources/nao-regressao/p2p-1");
    static final int[] SEMENTES = {1, 2, 3};
    static final String[] LADOS = {"base-a", "base-b", "atual-a", "atual-b"};

    /** Terminam na baseline: fronteira e folgado por familia. */
    static final String[][] TERMINA = {
            {"Casper", "MCO2:10:60"}, {"Casper", "MCO2:20:300"}, {"Casper", "MCO2:50:600"},
            {"Parlia", "AMBIFY:10:60"}, {"Parlia", "AMBIFY:12:60"}, {"Parlia", "AMBIFY:30:300"},
            {"Nakamoto", "OFFSET:30:20000"}, {"Nakamoto", "OFFSET:35:20000"}, {"Nakamoto", "OFFSET:40:20000"},
            {"ARQ", "ARQ:7:60"}};
    /** Travam na baseline (laco infinito em connectToNetwork): so a saida nova importa. */
    static final String[][] TRAVA = {
            {"Casper", "MCO2:4:60"}, {"Casper", "MCO2:7:60"}, {"Parlia", "AMBIFY:4:60"},
            {"Parlia", "AMBIFY:7:60"}, {"Nakamoto", "OFFSET:10:20000"}, {"Nakamoto", "OFFSET:20:20000"}};

    public static void main(String[] a) throws Exception {
        Path base = Path.of(a[0]).toAbsolutePath();
        Path out = Path.of(a[1]).toAbsolutePath();
        String quais = a.length > 2 ? a[2] : "todos";
        String head = sh(base, "git", "rev-parse", "HEAD").trim();
        if (!head.equals(COMMIT)) throw new IllegalStateException("baseline nao esta em " + COMMIT + ": " + head);
        if (!sh(base, "git", "status", "--porcelain").isBlank()) throw new IllegalStateException("baseline suja");
        Files.createDirectories(EVID.resolve("saida-final"));
        Path cmds = EVID.resolve("comandos.txt");
        Path hashes = EVID.resolve("hashes.txt");
        if (!quais.equals("todos")) {
            throw new IllegalArgumentException("use 'todos': cada invocacao reescreve comandos.txt e hashes.txt");
        }
        Files.writeString(cmds, "# baseline " + COMMIT + System.lineSeparator() + "# BASE=" + base
                + System.lineSeparator());
        Files.writeString(hashes, "# sha256 normalizado (sem '#', sem colunas hash) | linhas de dados | pasta/csv"
                + System.lineSeparator());
        if (quais.equals("termina") || quais.equals("todos")) {
            for (int s : SEMENTES) for (String[] c : TERMINA) rodar("termina", c, s, base, out, 900, cmds, hashes);
        }
        if (quais.equals("trava") || quais.equals("todos")) {
            for (int s : SEMENTES) for (String[] c : TRAVA) rodar("trava", c, s, base, out, 30, cmds, hashes);
        }
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
        Files.writeString(cmds, String.join(" ", cmd) + System.lineSeparator(),
                StandardOpenOption.APPEND);
        Path saida = EVID.resolve("saida-final").resolve(classe + "-" + caso[0] + "-" + nomeCaso + "-s" + semente + ".txt");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(saida.toFile()).start();
        int rc = p.waitFor();
        Files.writeString(saida, "exit=" + rc + System.lineSeparator(), StandardOpenOption.APPEND);
        String[] partes = casoStr.split(":");
        for (String lado : LADOS) {
            Path dir = out.resolve(sub).resolve(partes[0] + "-" + partes[1] + "v-" + partes[2] + "s-" + lado);
            StringBuilder sb = new StringBuilder();
            if (!Files.isDirectory(dir)) {
                sb.append("SEM_CSV 0 ").append(classe).append('/').append(nomeCaso).append("/s").append(semente)
                        .append('/').append(dir.getFileName()).append(System.lineSeparator());
            } else {
                try (var st = Files.walk(dir)) {
                    for (Path c : st.filter(x -> x.toString().endsWith(".csv")).sorted().toList()) {
                        String[] h = EvidenciaHashes.hashEDados(c);
                        sb.append(String.format("%s %4s %s/%s/s%d/%s/%s%n", h[0], h[1], classe, nomeCaso, semente,
                                dir.getFileName(), dir.relativize(c)));
                    }
                }
                if (sb.length() == 0) sb.append("SEM_CSV 0 ").append(classe).append('/').append(nomeCaso).append("/s")
                        .append(semente).append('/').append(dir.getFileName()).append(System.lineSeparator());
            }
            Files.writeString(hashes, sb.toString(), StandardOpenOption.APPEND);
        }
        System.out.println(classe + " " + casoStr + " s" + semente + " exit=" + rc);
    }

    static String sh(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        return o;
    }
}
