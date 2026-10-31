package jabs.network.p2p;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Driver do DIAGNOSTICO DE HASH da N1 (NAO e teste). Ver
 * {@code src/test/resources/nao-regressao/n1-conversao-n/diagnostico-hash.txt}.
 *
 * <p>Testa a hipotese: a divergencia base x atual da N1 vem de sensibilidade PRE-EXISTENTE a ordem de iteracao de
 * HashSet/HashMap com chaves sem equals/hashCode (identityHashCode da thread principal). Cada execucao e UMA JVM
 * de simulacao, lancada em sequencia (nunca em paralelo), com
 * {@code jabs.example.HashPerturbado K --protocols=... } (test-classes do repositorio so fornece essa classe;
 * {@code MVPComparison} vem de {@code RAIZ/target/classes}, onde RAIZ = baseline 325f4e3 ou o repositorio).
 * Nenhum mvn roda na baseline. Normalizacao dos CSV = {@link EvidenciaHashes} (= nao_regressao.py: colunas com
 * 'hash' e linhas '#' ignoradas).
 *
 * <p>Uso (raiz do repo, apos {@code mvn -o -q test-compile}):
 * {@code java -cp target/test-classes:target/classes jabs.network.p2p.HashDiagnosticoRodar PASSO}, com PASSO em
 * {@code base-k, atual-k, busca, flags}. Brutos em /tmp/opencode/n1-hash/runs; resumo em
 * {@code .../n1-conversao-n/diagnostico-hash/}.
 */
public final class HashDiagnosticoRodar {
    private HashDiagnosticoRodar() { }

    static final String COMMIT = "325f4e3db42864a2aa7e1e92e6bf22a706759436";
    static final Path BASE = Path.of("/tmp/opencode/base-325f4e3");
    static final Path REPO = Path.of("").toAbsolutePath();
    static final Path RUNS = Path.of("/tmp/opencode/n1-hash/runs");
    static final Path RESUMO = REPO.resolve("src/test/resources/nao-regressao/n1-conversao-n/diagnostico-hash");
    static final int TIMEOUT_S = 900;
    static final int[] KS = {0, 1, 2, 5, 10, 50, 200};

    /** proto, n, duracao, semente. O ultimo (ARQ) e controle: nao deve variar com k. */
    static final String[][] CASOS = {
            {"AMBIFY", "10", "60", "1"}, {"MCO2", "50", "600", "2"}, {"OFFSET", "40", "20000", "1"},
            {"ARQ", "7", "60", "1"}};

    record Res(String tag, int rc, long seg, Map<String, String> arquivos, String cmd) { }

    public static void main(String[] a) throws Exception {
        Files.createDirectories(RESUMO);
        Files.createDirectories(RUNS);
        String passo = a[0];
        List<String> out = new ArrayList<>();
        switch (passo) {
            case "base-k" -> porK("base", out);
            case "atual-k" -> porK("atual", out);
            case "busca" -> busca(out);
            case "flags" -> flags(out);
            case "direto" -> direto(out);
            case "matriz" -> matriz(out);
            case "todos" -> todos(out);
            case "flags2" -> flags2(out);
            case "hc3-desloc" -> hc3Desloc(out);
            case "sonda" -> sonda(out);
            default -> throw new IllegalArgumentException("passo: base-k | atual-k | busca | flags");
        }
        Files.write(RESUMO.resolve("resumo-" + passo + ".txt"), out, StandardCharsets.UTF_8);
        System.out.println("fim " + passo);
    }

    // ---- passos ------------------------------------------------------------------------------------------

    static void porK(String lado, List<String> out) throws Exception {
        for (String[] c : CASOS) {
            Res ref = rodar(lado, c, 0, "", List.of());
            out.add(linha(ref, null));
            // repeticao de k=0 (determinismo do proprio lado, mesma JVM flags): rodada separada, nao do cache
            Res rep = rodar(lado, c, 0, "-rep", List.of());
            out.add(linha(rep, ref) + "   <- repeticao de k=0");
            for (int k : KS) {
                if (k == 0) continue;
                Res r = rodar(lado, c, k, "", List.of());
                out.add(linha(r, ref));
            }
        }
    }

    static void busca(List<String> out) throws Exception {
        for (String[] c : CASOS) {
            if (c[0].equals("ARQ")) continue;
            for (int kAlvo : new int[] {0, -1}) {
                Res alvo = rodar("base", c, kAlvo, "", List.of());
                out.add("== " + String.join(":", c) + "  alvo = BASE " + (kAlvo < 0 ? "DIRETO (sem wrapper, = N1)" : "wrapper k=0")
                        + "  " + resumoHash(alvo));
                int achado = -1;
                for (int k = 0; k <= 200 && achado < 0; k++) {
                    Res r = rodar("atual", c, k, "", List.of());
                    boolean ig = r.rc == 0 && r.arquivos.equals(alvo.arquivos);
                    out.add(linha(r, alvo) + (ig ? "   <== IGUAL ao alvo" : ""));
                    if (ig) achado = k;
                }
                if (achado < 0) {
                    out.add("   RESULTADO: nenhum k em 0..200 do ATUAL (wrapper) reproduz o alvo");
                    continue;
                }
                out.add("   RESULTADO: primeiro k do ATUAL (wrapper) que reproduz o alvo: d=" + achado);
                if (kAlvo < 0) continue;
                // verificacao do deslocamento: ATUAL(d+j) deve igualar BASE(j) para outros j
                for (int j : new int[] {1, 2, 5, 10, 50}) {
                    Res bj = rodar("base", c, j, "", List.of());
                    Res at = rodar("atual", c, achado + j, "", List.of());
                    boolean ig = bj.rc == 0 && at.rc == 0 && at.arquivos.equals(bj.arquivos);
                    out.add("   verificacao: ATUAL k=" + (achado + j) + " x BASE k=" + j + " -> "
                            + (ig ? "IDENTICO" : "DIVERGENTE") + "  [" + resumoHash(at) + " vs " + resumoHash(bj) + "]");
                }
            }
        }
    }

    static void flags(List<String> out) throws Exception {
        String[][] modos = {{"-hc2", "2"}, {"-hc3", "3"}};
        String[] c = CASOS[0];
        for (String[] m : modos) {
            List<String> jvm = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=" + m[1]);
            Res b = rodar("base", c, -1, m[0], jvm);
            Res br = rodar("base", c, -1, m[0] + "-rep", jvm);
            Res at = rodar("atual", c, -1, m[0], jvm);
            Res ar = rodar("atual", c, -1, m[0] + "-rep", jvm);
            out.add("== hashCode=" + m[1] + "  " + String.join(":", c) + " (MVPComparison direto)");
            out.add("   " + linha(b, null));
            out.add("   " + linha(br, b) + "   <- base x base");
            out.add("   " + linha(at, null));
            out.add("   " + linha(ar, at) + "   <- atual x atual");
            out.add("   BASE x ATUAL: " + (b.rc == 0 && at.rc == 0 ? (b.arquivos.equals(at.arquivos) ? "IDENTICO" : "DIVERGENTE")
                    : "INCONCLUSIVO (rc base=" + b.rc + ", atual=" + at.rc + ")"));
            if (b.rc == 0 && at.rc == 0 && !b.arquivos.equals(at.arquivos)) out.add("   " + primeiraDiferenca(b, at));
        }
    }

    /**
     * Matriz BASE(kb) x ATUAL(ka), kb e ka em 0..200 (wrapper nos dois lados). O ATUAL consome MAIS hashes antes da
     * simulacao, entao o esperado e BASE(kb) == ATUAL(ka) com kb = ka + d, d > 0 (a BASE precisa de k extra).
     * Reporta TODOS os pares iguais e quantas saidas distintas existem (para separar coincidencia de deslocamento).
     */
    static void matriz(List<String> out) throws Exception {
        for (String[] c : CASOS) {
            if (c[0].equals("ARQ")) continue;
            Map<Integer, Res> b = new LinkedHashMap<>();
            Map<Integer, Res> at = new LinkedHashMap<>();
            for (int k = 0; k <= 200; k++) {
                b.put(k, rodar("base", c, k, "", List.of()));
                at.put(k, rodar("atual", c, k, "", List.of()));
            }
            long dB = b.values().stream().map(r -> r.arquivos).distinct().count();
            long dA = at.values().stream().map(r -> r.arquivos).distinct().count();
            out.add("== " + String.join(":", c) + "  saidas distintas: BASE " + dB + " de 201, ATUAL " + dA + " de 201");
            List<String> pares = new ArrayList<>();
            Map<Integer, Integer> porD = new java.util.TreeMap<>();
            for (var eb : b.entrySet()) {
                for (var ea : at.entrySet()) {
                    if (eb.getValue().rc == 0 && ea.getValue().rc == 0 && eb.getValue().arquivos.equals(ea.getValue().arquivos)) {
                        pares.add("BASE k=" + eb.getKey() + " == ATUAL k=" + ea.getKey() + " (d=kb-ka=" + (eb.getKey() - ea.getKey()) + ")");
                        porD.merge(eb.getKey() - ea.getKey(), 1, Integer::sum);
                    }
                }
            }
            out.add("   pares iguais: " + pares.size());
            out.add("   contagem por d=kb-ka: " + porD);
            for (String p : pares.stream().limit(60).toList()) out.add("   " + p);
            if (pares.size() > 60) out.add("   (... " + (pares.size() - 60) + " pares omitidos; ver contagem por d)");
        }
    }

    /**
     * As 12 chamadas divergentes da N1 (e os controles IDENTICO), sementes 1..3. Para cada uma: BASE direto, ATUAL
     * direto (o que nao_regressao.py compara) e BASE com wrapper k = kd, onde kd e o deslocamento medido na matriz
     * (ATUAL direto == BASE wrapper k=11 para MCO2/AMBIFY, k=7 para OFFSET). Se BASE(kd) == ATUAL direto, o ATUAL
     * so deslocou a sequencia de identityHashCode.
     */
    static void todos(List<String> out) throws Exception {
        String[][] casos = {{"MCO2", "10", "60"}, {"MCO2", "50", "600"}, {"AMBIFY", "10", "60"}, {"AMBIFY", "12", "60"},
                {"OFFSET", "40", "20000"}, {"ARQ", "7", "60"}};
        for (String[] c0 : casos) {
            for (int sem = 1; sem <= 3; sem++) {
                String[] c = {c0[0], c0[1], c0[2], String.valueOf(sem)};
                int kd = c[0].equals("OFFSET") ? 7 : 11;
                Res b = rodar("base", c, -1, "", List.of());
                Res at = rodar("atual", c, -1, "", List.of());
                Res bk = rodar("base", c, kd, "", List.of());
                boolean div = !b.arquivos.equals(at.arquivos);
                boolean ig = at.arquivos.equals(bk.arquivos);
                out.add(String.format("%-7s n=%-3s dur=%-6s s%d  BASE x ATUAL(direto): %-10s  ATUAL(direto) x BASE(wrapper k=%d): %s",
                        c[0], c[1], c[2], sem, div ? "DIVERGENTE" : "IDENTICO", kd, ig ? "IDENTICO" : "DIVERGENTE")
                        + "   [rc " + b.rc + "/" + at.rc + "/" + bk.rc + "]");
            }
        }
    }

    /** Extra: -XX:hashCode=2 (constante) nos outros dois casos divergentes, semente 1. Timeout 900 s; trava e relatada. */
    static void flags2(List<String> out) throws Exception {
        List<String> jvm = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2");
        for (String[] c : new String[][] {{"MCO2", "50", "600", "1"}, {"OFFSET", "40", "20000", "1"}}) {
            Res b = rodar("base", c, -1, "-hc2", jvm);
            Res at = rodar("atual", c, -1, "-hc2", jvm);
            out.add("== hashCode=2  " + String.join(":", c) + " (MVPComparison direto)");
            out.add("   " + linha(b, null));
            out.add("   " + linha(at, b));
        }
    }

    /**
     * -XX:hashCode=3 (contador): BASE e ATUAL divergem (flags). Se o contador e global, o ATUAL direto deveria igualar a
     * BASE com wrapper k = d' para algum d'. Procura d' em 0..60 (AMBIFY:10:60:1, hashCode=3).
     */
    static void hc3Desloc(List<String> out) throws Exception {
        List<String> jvm = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=3");
        String[] c = CASOS[0];
        Res at = rodar("atual", c, -1, "-hc3", jvm);
        out.add("alvo: " + linha(at, null));
        for (int k = 0; k <= 60; k++) {
            Res b = rodar("base", c, k, "-hc3", jvm);
            boolean ig = b.rc == 0 && b.arquivos.equals(at.arquivos);
            out.add(linha(b, at) + (ig ? "   <== IGUAL ao ATUAL direto" : ""));
        }
    }

    /**
     * Sonda (jabs.example.SondaHash): posicao da sequencia de identityHashCode da thread principal ao FIM da
     * MVPComparison, BASE x ATUAL. {@code ref} lista a sequencia de uma JVM nova; a posicao do valor sondado nela e o
     * numero de hashes de identidade consumidos (inclui o wrapper, igual nos dois lados). Caso: semente 1.
     */
    static void sonda(List<String> out) throws Exception {
        Path dirRef = RUNS.getParent().resolve("sonda");
        Files.createDirectories(dirRef);
        String cpRepo = REPO.resolve("target/classes") + ":" + Files.readString(REPO.resolve("target/cp.txt")).trim()
                + ":" + REPO.resolve("target/test-classes");
        Path ref = dirRef.resolve("ref.txt");
        new ProcessBuilder("java", "-cp", cpRepo, "jabs.example.SondaHash", "ref", "300000")
                .redirectErrorStream(true).redirectOutput(ref.toFile()).start().waitFor();
        List<String> seq = Files.readAllLines(ref);
        out.add("sequencia de referencia: " + seq.size() + " valores; primeiros 5: " + seq.subList(0, 5));
        for (String[] c : CASOS) {
            for (String lado : new String[] {"base", "atual"}) {
                Path raiz = lado.equals("base") ? BASE : REPO;
                String cp = raiz.resolve("target/classes") + ":" + Files.readString(raiz.resolve("target/cp.txt")).trim()
                        + ":" + REPO.resolve("target/test-classes");
                Path d = dirRef.resolve(lado + "-" + c[0]);
                Files.createDirectories(d);
                Path log = dirRef.resolve(lado + "-" + c[0] + ".log");
                Process p = new ProcessBuilder("java", "-cp", cp, "jabs.example.SondaHash", "run", "0",
                        "--protocols=" + c[0], "--validators=" + c[1], "--trials=1", "--duration=" + c[2],
                        "--seed=" + c[3], "--output=" + d).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                p.waitFor(TIMEOUT_S, TimeUnit.SECONDS);
                String v = null;
                for (String l : Files.readAllLines(log)) if (l.startsWith("SONDA=")) v = l.substring(6);
                int pos = v == null ? -1 : seq.indexOf(v);
                out.add(String.format("%-6s %-8s SONDA=%s posicao na sequencia (0-based)=%d  [chamadas de identityHashCode da thread principal ate o fim]",
                        lado, c[0] + ":" + c[1] + ":" + c[2], v, pos));
            }
        }
    }

    /** Controle: MVPComparison direto (sem wrapper). Compara com o hashes.txt da N1 (nao_regressao.py). */
    static void direto(List<String> out) throws Exception {
        for (String[] c : CASOS) {
            Res b = rodar("base", c, -1, "", List.of());
            Res at = rodar("atual", c, -1, "", List.of());
            out.add(linha(b, null));
            out.add(linha(at, b));
            Res w = rodar("base", c, 0, "", List.of());
            out.add(linha(w, b) + "   <- BASE com wrapper k=0 x BASE direto");
        }
    }

    // ---- execucao ---------------------------------------------------------------------------------------

    static Res rodar(String lado, String[] c, int k, String extra, List<String> jvm) throws Exception {
        Path raiz = lado.equals("base") ? BASE : REPO;
        String tag = lado + "-" + c[0] + "-" + c[1] + "-" + c[2] + "-s" + c[3] + "-k" + k + extra;
        Path dir = RUNS.resolve(tag);
        Path info = dir.resolve("info.txt");
        if (Files.exists(info)) {
            return carregar(tag, dir);
        }
        Files.createDirectories(dir.resolve("csv"));
        String cp = raiz.resolve("target/classes") + ":" + Files.readString(raiz.resolve("target/cp.txt")).trim()
                + ":" + REPO.resolve("target/test-classes");
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.addAll(jvm);
        // k < 0 = CONTROLE: MVPComparison direto, sem o wrapper (como nao_regressao.py)
        cmd.addAll(List.of("-cp", cp));
        cmd.add(k < 0 ? "jabs.example.MVPComparison" : "jabs.example.HashPerturbado");
        if (k >= 0) cmd.add(String.valueOf(k));
        cmd.addAll(List.of("--protocols=" + c[0], "--validators=" + c[1], "--trials=1", "--duration=" + c[2],
                "--seed=" + c[3], "--output=" + dir.resolve("csv")));
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("log.txt").toFile()).start();
        int rc;
        if (p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            rc = p.exitValue();
        } else {
            p.destroyForcibly().waitFor();
            rc = 124;
        }
        long seg = (System.currentTimeMillis() - t0) / 1000;
        Map<String, String> arq = hashes(dir.resolve("csv"));
        StringBuilder sb = new StringBuilder();
        sb.append("rc=").append(rc).append('\n').append("seg=").append(seg).append('\n');
        sb.append("cmd=").append(String.join(" ", cmd)).append('\n');
        for (var e : arq.entrySet()) sb.append("csv=").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        Files.writeString(info, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(RESUMO.resolve("comandos.txt"), tag + "  (cwd=" + dir + ")\n    " + String.join(" ", cmd)
                + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println(tag + " rc=" + rc + " " + seg + "s");
        return new Res(tag, rc, seg, arq, String.join(" ", cmd));
    }

    static Res carregar(String tag, Path dir) throws IOException {
        int rc = -1;
        long seg = 0;
        String cmd = "";
        Map<String, String> arq = new LinkedHashMap<>();
        for (String l : Files.readAllLines(dir.resolve("info.txt"), StandardCharsets.UTF_8)) {
            if (l.startsWith("rc=")) rc = Integer.parseInt(l.substring(3));
            else if (l.startsWith("seg=")) seg = Long.parseLong(l.substring(4));
            else if (l.startsWith("cmd=")) cmd = l.substring(4);
            else if (l.startsWith("csv=")) {
                String r = l.substring(4);
                int i = r.indexOf(' ');
                arq.put(r.substring(0, i), r.substring(i + 1));
            }
        }
        return new Res(tag, rc, seg, arq, cmd);
    }

    static Map<String, String> hashes(Path csvDir) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        List<Path> csvs;
        try (Stream<Path> s = Files.walk(csvDir)) {
            csvs = s.filter(x -> x.toString().endsWith(".csv")).sorted().toList();
        }
        for (Path c : csvs) {
            try {
                String[] h = EvidenciaHashes.hashEDados(c);
                m.put(csvDir.relativize(c).toString(), h[0] + " linhas=" + h[1]);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return m;
    }

    // ---- relato -----------------------------------------------------------------------------------------

    static String resumoHash(Res r) {
        StringBuilder sb = new StringBuilder();
        for (var e : r.arquivos.entrySet()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(e.getKey()).append(' ').append(e.getValue(), 0, 12).append(e.getValue().substring(64));
        }
        return sb.length() == 0 ? "SEM_CSV" : sb.toString();
    }

    static String linha(Res r, Res ref) {
        String cmp = "";
        if (ref != null && r.rc == 0 && ref.rc == 0) {
            cmp = r.arquivos.equals(ref.arquivos) ? "  == IDENTICO a " + ref.tag : "  != DIVERGENTE de " + ref.tag;
        } else if (ref != null) {
            cmp = "  ?? INCONCLUSIVO (rc " + r.rc + " / " + ref.rc + ")";
        }
        return r.tag + " rc=" + r.rc + " " + r.seg + "s  " + resumoHash(r) + cmp;
    }

    static String primeiraDiferenca(Res x, Res y) throws IOException {
        for (String f : x.arquivos.keySet()) {
            if (!y.arquivos.containsKey(f)) return f + " so em " + x.tag;
            if (x.arquivos.get(f).equals(y.arquivos.get(f))) continue;
            List<String[]> rx = EvidenciaHashes.ler(RUNS.resolve(x.tag).resolve("csv").resolve(f));
            List<String[]> ry = EvidenciaHashes.ler(RUNS.resolve(y.tag).resolve("csv").resolve(f));
            if (rx.size() != ry.size()) return f + ": " + (rx.size() - 1) + " vs " + (ry.size() - 1) + " linhas de dados";
            for (int i = 1; i < rx.size(); i++) {
                for (int j = 0; j < Math.min(rx.get(i).length, ry.get(i).length); j++) {
                    if (rx.get(0)[j].toLowerCase().contains("hash")) continue; // colunas 'hash' sao ignoradas
                    if (!rx.get(i)[j].equals(ry.get(i)[j])) {
                        return f + " linha " + i + " coluna " + rx.get(0)[j] + ": " + rx.get(i)[j] + " vs " + ry.get(i)[j];
                    }
                }
            }
        }
        return "diferenca so em colunas/arquivos";
    }
}
