
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
 * Driver da evidencia de nao regressao da tarefa C1-casper-quorum-correcao (loop 2, criterio 3). NAO e teste.
 * Ver {@code src/test/resources/nao-regressao/c1-casper/README.txt}. Mesmo metodo do P2P-1
 * ({@link EvidenciaRodar}); fica neste pacote so para reutilizar {@link EvidenciaHashes#hashEDados} sem alterar a
 * evidencia ja publicada do P2P-1.
 *
 * <p>Roda {@code scripts/jabs/nao_regressao.py} UM CASO POR VEZ e UMA SEMENTE POR VEZ (4 JVMs: base-a, base-b,
 * atual-a, atual-b), em sequencia. Classes de caso:
 * <ul>
 * <li>{@code identico}: protocolos que NAO usam CasperFFG; base x atual deve ser IDENTICO.</li>
 * <li>{@code pedido}: os 3 casos MCO2 da tarefa (resultado real: IDENTICO, ver README); {@code suplementar}: MCO2 onde o limiar muda; base x atual deve DIVERGIR (mudanca intencional) e
 * atual x atual deve ser IDENTICO.</li>
 * </ul>
 *
 * <p>Uso (raiz do repo, apos {@code mvn -o compile test-compile}, sem outro mvn em paralelo):
 * {@code java -cp target/test-classes jabs.network.p2p.EvidenciaCasperRodar BASE OUT}
 */
public final class EvidenciaCasperRodar {
    private EvidenciaCasperRodar() { }

    static final String COMMIT = "e703e3d47da726f5fbcf94657f574db617a59023";
    static final Path EVID = Path.of("src/test/resources/nao-regressao/c1-casper");
    static final int[] SEMENTES = {1, 2, 3};
    static final String[] LADOS = {"base-a", "base-b", "atual-a", "atual-b"};

    static final String[][] IDENTICO = {
            {"ARQ", "ARQ:7:60"}, {"Parlia", "AMBIFY:10:60"}, {"Parlia", "AMBIFY:12:60"},
            {"Nakamoto", "OFFSET:40:20000"}};
    /**
     * Casos pedidos pela tarefa para o Casper. ACHADO: NAO divergem. MCO2:10:60 nao finaliza bloco algum (checkpoint
     * 14 x 15 s = 210 s > 60 s: 0 linhas nas duas versoes) e, para n=20 (S=14) e n=50 (S=35), S%3==2, caso em que o
     * limiar antigo e o novo coincidem (2m+2). Mantidos na evidencia com o resultado real.
     */
    static final String[][] PEDIDO = {
            {"Casper", "MCO2:10:60"}, {"Casper", "MCO2:20:300"}, {"Casper", "MCO2:50:600"}};
    /**
     * Casos suplementares onde o limiar muda (S%3 em {0,1}) e a duracao permite finalizar: n=10 (S=7: 6 -> 5),
     * n=30 (S=21: 16 -> 14), n=40 (S=28: 20 -> 19), n=100 (S=70: 48 -> 47). Controle de limiar igual: n=20, n=50 a 600 s.
     */
    static final String[][] DIVERGE = {
            {"Casper", "MCO2:10:600"}, {"Casper", "MCO2:30:600"}, {"Casper", "MCO2:40:600"},
            {"Casper", "MCO2:100:600"}, {"Casper", "MCO2:20:600"}};

    public static void main(String[] a) throws Exception {
        Path base = Path.of(a[0]).toAbsolutePath();
        Path out = Path.of(a[1]).toAbsolutePath();
        String head = sh(base, "git", "log", "-1", "--format=%H").trim();
        if (!head.equals(COMMIT)) throw new IllegalStateException("baseline nao esta em " + COMMIT + ": " + head);
        if (!sh(base, "git", "status", "--porcelain").isBlank()) throw new IllegalStateException("baseline suja");
        Files.createDirectories(EVID.resolve("saida-final"));
        // reexecutavel: apaga saidas de uma rodada anterior (so arquivos .txt deste diretorio)
        try (var velhos = Files.list(EVID.resolve("saida-final"))) {
            for (Path v : velhos.filter(x -> x.toString().endsWith(".txt")).toList()) Files.delete(v);
        }
        Path cmds = EVID.resolve("comandos.txt");
        Path hashes = EVID.resolve("hashes.txt");
        Files.writeString(cmds, "# baseline " + COMMIT + System.lineSeparator() + "# BASE=" + base
                + System.lineSeparator());
        Files.writeString(hashes, "# sha256 normalizado (sem '#', sem colunas hash) | linhas de dados | pasta/csv"
                + System.lineSeparator());
        for (int s : SEMENTES) for (String[] c : IDENTICO) rodar("identico", c, s, base, out, cmds, hashes);
        for (int s : SEMENTES) for (String[] c : PEDIDO) rodar("pedido", c, s, base, out, cmds, hashes);
        for (int s : SEMENTES) for (String[] c : DIVERGE) rodar("suplementar", c, s, base, out, cmds, hashes);
        System.out.println("fim");
    }

    static void rodar(String classe, String[] caso, int semente, Path base, Path out, Path cmds, Path hashes)
            throws Exception {
        String casoStr = caso[1];
        String nomeCaso = casoStr.replace(':', '_');
        String sub = classe + "/" + nomeCaso + "/s" + semente;
        List<String> cmd = new ArrayList<>(List.of("python3", "scripts/jabs/nao_regressao.py",
                "--base", base.toString(), "--atual", ".", "--casos", casoStr, "--semente", String.valueOf(semente),
                "--timeout", "900", "--saida", out.resolve(sub).toString()));
        Files.writeString(cmds, String.join(" ", cmd) + System.lineSeparator(), StandardOpenOption.APPEND);
        Path saida = EVID.resolve("saida-final").resolve(classe + "-" + caso[0] + "-" + nomeCaso + "-s" + semente + ".txt");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(saida.toFile()).start();
        int rc = p.waitFor();
        Files.writeString(saida, "exit=" + rc + System.lineSeparator(), StandardOpenOption.APPEND);
        String[] partes = casoStr.split(":");
        for (String lado : LADOS) {
            Path dir = out.resolve(sub).resolve(partes[0] + "-" + partes[1] + "v-" + partes[2] + "s-" + lado);
            StringBuilder sb = new StringBuilder();
            if (Files.isDirectory(dir)) {
                try (var st = Files.walk(dir)) {
                    for (Path c : st.filter(x -> x.toString().endsWith(".csv")).sorted().toList()) {
                        String[] h = EvidenciaHashes.hashEDados(c);
                        sb.append(String.format("%s %4s %s/%s/s%d/%s/%s%n", h[0], h[1], classe, nomeCaso, semente,
                                dir.getFileName(), dir.relativize(c)));
                    }
                }
            }
            if (sb.length() == 0) sb.append("SEM_CSV 0 ").append(classe).append('/').append(nomeCaso).append("/s")
                    .append(semente).append('/').append(dir.getFileName()).append(System.lineSeparator());
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
