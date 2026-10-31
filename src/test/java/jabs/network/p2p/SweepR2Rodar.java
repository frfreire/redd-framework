package jabs.network.p2p;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Driver da evidencia de nao regressao do {@code ByzantineSweep} (N1, rodada 2). NAO e teste.
 *
 * <p>Para cada caso {@code TIPO:N:DURACAO:ATAQUE} roda, em SEQUENCIA e uma JVM por vez, com a JVM PADRAO (sem
 * {@code -XX:hashCode}), modo legacy (sem {@code --n-semantics}), semente 1, 1 trial, {@code --max-byz=1} (f = 0 e 1):
 * baseline 325f4e3 duas vezes (a, b) e atual duas vezes (a, b). Compara o hash normalizado dos CSV
 * ({@link EvidenciaHashes}: sem linhas '#', sem colunas 'hash', como {@code nao_regressao.py}). Nenhum mvn na baseline.
 *
 * <p>Uso: {@code java -cp target/test-classes:target/classes jabs.network.p2p.SweepR2Rodar BASE OUT PASTA_EVID TIPO:N:DUR:ATK ...}
 */
public final class SweepR2Rodar {
    private SweepR2Rodar() { }

    static final Path REPO = Path.of("").toAbsolutePath();

    public static void main(String[] a) throws Exception {
        Path base = Path.of(a[0]).toAbsolutePath();
        Path out = Path.of(a[1]).toAbsolutePath();
        Path evid = Path.of(a[2]);
        Files.createDirectories(evid);
        // argumento opcional "arquivo=NOME" (em qualquer posicao a partir da 4a) escolhe o arquivo de resumo
        String nomeResumo = "sweep-r2.txt";
        for (int i = 3; i < a.length; i++) {
            if (a[i].startsWith("arquivo=")) {
                nomeResumo = a[i].substring("arquivo=".length());
            }
        }
        Path resumo = evid.resolve(nomeResumo);
        Files.writeString(resumo, "# ByzantineSweep, modo legacy, JVM padrao, semente 1, trials 1, max-byz 1; sha256 normalizado dos CSV\n");
        for (int i = 3; i < a.length; i++) {
            if (a[i].startsWith("arquivo=")) {
                continue;
            }
            String[] c = a[i].split(":");
            caso(base, out, resumo, c[0], c[1], c[2], c[3]);
        }
        System.out.println("fim");
    }

    static void caso(Path base, Path out, Path resumo, String tipo, String n, String dur, String atk) throws Exception {
        String[] hs = new String[4];
        String[] nomes = {"base-a", "base-b", "atual-a", "atual-b"};
        for (int i = 0; i < 4; i++) {
            Path raiz = i < 2 ? base : REPO;
            Path d = out.resolve(tipo + "-" + n + "-" + dur).resolve(nomes[i]);
            Files.createDirectories(d);
            String cp = raiz.resolve("target/classes") + ":" + Files.readString(raiz.resolve("target/cp.txt")).trim();
            List<String> cmd = new ArrayList<>(List.of("java", "-cp", cp, "jabs.example.ByzantineSweep", "--type=" + tipo,
                    "--validators=" + n, "--max-byz=1", "--trials=1", "--duration=" + dur, "--seed=1",
                    "--attack=" + atk, "--out=" + d.resolve("sweep.csv")));
            Files.writeString(resumo, "CMD " + nomes[i] + " " + String.join(" ", cmd) + System.lineSeparator(),
                    StandardOpenOption.APPEND);
            long t0 = System.currentTimeMillis();
            Process p = new ProcessBuilder(cmd).directory(d.toFile()).redirectErrorStream(true)
                    .redirectOutput(d.resolve("log.txt").toFile()).start();
            int rc;
            if (p.waitFor(900, TimeUnit.SECONDS)) {
                rc = p.exitValue();
            } else {
                p.destroyForcibly().waitFor();
                rc = 124;
            }
            long seg = (System.currentTimeMillis() - t0) / 1000;
            // todos os CSV (agregado sweep.csv e os CSV por bloco em tmp/), em ordem de caminho
            List<Path> csvs;
            try (var st = Files.walk(d)) {
                csvs = st.filter(x -> x.toString().endsWith(".csv")).sorted().toList();
            }
            StringBuilder agg = new StringBuilder();
            for (Path c : csvs) {
                String[] h = EvidenciaHashes.hashEDados(c);
                agg.append(d.relativize(c)).append(' ').append(h[0]).append(' ').append(h[1]).append('\n');
                Files.writeString(resumo, String.format("   %s %s %s linhas=%s%n", nomes[i], d.relativize(c), h[0], h[1]),
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
            String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(agg.toString().getBytes(StandardCharsets.UTF_8)));
            hs[i] = rc == 0 && !csvs.isEmpty() ? sha : "RC" + rc;
            Files.writeString(resumo, String.format("%s %s:%s:%s:%s rc=%d t=%ds agregado=%s arquivos=%d%n", nomes[i], tipo,
                    n, dur, atk, rc, seg, sha.substring(0, 16), csvs.size()), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
        String v = hs[0].startsWith("RC") || hs[2].startsWith("RC") ? "INCONCLUSIVO"
                : hs[0].equals(hs[2]) ? "IDENTICO" : "DIVERGENTE";
        String linha = String.format("%s:%s:%s:%s  base-a x atual-a: %s | base-a x base-b: %s | atual-a x atual-b: %s",
                tipo, n, dur, atk, v, hs[0].equals(hs[1]) ? "IDENTICO" : "DIVERGENTE",
                hs[2].equals(hs[3]) ? "IDENTICO" : "DIVERGENTE");
        Files.writeString(resumo, linha + System.lineSeparator(), StandardOpenOption.APPEND);
        System.out.println(linha);
    }
}
