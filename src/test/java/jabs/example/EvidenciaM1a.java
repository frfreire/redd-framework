package jabs.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Auxiliar de EVIDENCIA da M1a-1 (NAO e teste JUnit; nao roda no {@code mvn test}). Existe porque
 * {@code nao_regressao.py} monta o classpath com ':' (Linux) e nao roda no Windows. Mesma logica: 2 execucoes da
 * baseline e 2 do atual, em paralelo, mesma semente; compara TODOS os arquivos de saida BYTE A BYTE (cru) e, se
 * difere, tambem sem as colunas cujo cabecalho contem "hash" (a normalizacao do script).
 *
 * <p>Modos (classpath = target/test-classes + dependencias; as arvores BASE e ATUAL sao dadas por argumento):
 * <pre>
 *   nr    BASE ATUAL OUT SEMENTE FLAGS_JVM CASO...   nao regressao (atual SEM registro ligado)
 *   sonda BASE ATUAL OUT FLAGS_JVM CASO...           posicao na sequencia de identityHashCode (SondaHash)
 *   tempo ATUAL OUT REPS CASO                        tempo de parede desligado x ligado (--load-record)
 * </pre>
 * FLAGS_JVM: "-" = nenhuma; senao lista separada por virgula. CASO = PROTO:N:DURACAO.
 */
public final class EvidenciaM1a {
    private EvidenciaM1a() { }

    private static final String SEP = File_pathSeparator();

    private static String File_pathSeparator() {
        return java.io.File.pathSeparator;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String cp(Path root) throws IOException {
        return root.resolve("target/classes") + SEP + Files.readString(root.resolve("target/cp.txt")).trim();
    }

    private static List<String> jvm(String flags) {
        List<String> l = new ArrayList<>();
        if (!flags.equals("-")) {
            l.addAll(Arrays.asList(flags.split(",")));
        }
        return l;
    }

    private record Res(int rc, double seg, String stdout) { }

    private static Res run(List<String> cmd, Path stdoutFile) throws Exception {
        long t = System.nanoTime();
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(stdoutFile.toFile()).start();
        if (!p.waitFor(900, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Res(124, 900, "");
        }
        return new Res(p.exitValue(), (System.nanoTime() - t) / 1e9,
                Files.readString(stdoutFile, StandardCharsets.UTF_8));
    }

    private static List<String> mvp(Path root, String flags, String proto, String n, String dur, String seed,
                                    Path out, String... extra) throws IOException {
        List<String> c = new ArrayList<>(List.of(java()));
        c.addAll(jvm(flags));
        c.addAll(List.of("-cp", cp(root), "jabs.example.MVPComparison", "--protocols=" + proto, "--validators=" + n,
                "--trials=1", "--duration=" + dur, "--seed=" + seed, "--output=" + out));
        c.addAll(Arrays.asList(extra));
        return c;
    }

    private static Map<String, byte[]> arquivos(Path dir) throws IOException {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                m.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return m;
    }

    /** Conteudo do CSV sem as colunas cujo cabecalho contem "hash"; linhas '#' mantidas; linhas em branco puladas. */
    private static String semHash(byte[] b) {
        List<String> out = new ArrayList<>();
        int[] manter = null;
        String delim = ",";
        for (String l : new String(b, StandardCharsets.UTF_8).split("\\R")) {
            if (l.isBlank()) {
                continue;
            }
            if (l.startsWith("#")) {
                out.add(l);
                continue;
            }
            if (manter == null) {
                long pv = l.chars().filter(c -> c == ';').count();
                long vg = l.chars().filter(c -> c == ',').count();
                delim = pv > vg ? ";" : ",";
                String[] h = l.split(delim, -1);
                List<Integer> k = new ArrayList<>();
                for (int i = 0; i < h.length; i++) {
                    if (!h[i].toLowerCase().contains("hash")) {
                        k.add(i);
                    }
                }
                manter = k.stream().mapToInt(Integer::intValue).toArray();
            }
            String[] v = l.split(delim, -1);
            StringBuilder sb = new StringBuilder();
            for (int i : manter) {
                if (i < v.length) {
                    if (sb.length() > 0) {
                        sb.append(delim);
                    }
                    sb.append(v[i]);
                }
            }
            out.add(sb.toString());
        }
        return String.join("\n", out);
    }

    private static String comparar(Path a, Path b) throws IOException {
        Map<String, byte[]> fa = arquivos(a);
        Map<String, byte[]> fb = arquivos(b);
        if (!fa.keySet().equals(fb.keySet())) {
            return "DIVERGENTE arquivos: " + fa.keySet() + " vs " + fb.keySet();
        }
        long bytes = 0;
        List<String> cru = new ArrayList<>();
        List<String> sh = new ArrayList<>();
        for (String k : fa.keySet()) {
            bytes += fa.get(k).length;
            if (!Arrays.equals(fa.get(k), fb.get(k))) {
                cru.add(k);
                if (!k.endsWith(".csv") || !semHash(fa.get(k)).equals(semHash(fb.get(k)))) {
                    sh.add(k);
                }
            }
        }
        String base = fa.size() + " arquivos " + fa.keySet() + ", " + bytes + " bytes: ";
        if (cru.isEmpty()) {
            return base + "BYTE-A-BYTE IDENTICO";
        }
        return base + "cru difere em " + cru + (sh.isEmpty() ? "; SEM colunas hash: IDENTICO"
                : "; SEM colunas hash: DIVERGENTE em " + sh);
    }

    private static Path dir(Path out, String[] c, String lado, String rep) {
        return out.resolve(c[0] + "-" + c[1] + "v-" + c[2] + "s-" + lado + "-" + rep);
    }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "nr":
                nr(a);
                break;
            case "sonda":
                sonda(a);
                break;
            case "tempo":
                tempo(a);
                break;
            case "ligado":
                ligado(a);
                break;
            case "carga":
                carga(a);
                break;
            case "posblocos":
                posBlocos(a);
                break;
            case "fases":
                fases(a);
                break;
            default:
                throw new IllegalArgumentException(a[0]);
        }
    }

    private static void nr(String[] a) throws Exception {
        Path base = Path.of(a[1]);
        Path atual = Path.of(a[2]);
        Path out = Path.of(a[3]);
        String seed = a[4];
        String flags = a[5];
        System.out.println("flags JVM=" + flags + " semente=" + seed);
        ExecutorService ex = Executors.newFixedThreadPool(4);
        for (int i = 6; i < a.length; i++) {
            String[] c = a[i].split(":");
            Map<String, Future<Res>> fut = new LinkedHashMap<>();
            for (String lado : List.of("base", "atual")) {
                for (String rep : List.of("a", "b")) {
                    Path o = dir(out, c, lado, rep);
                    Files.createDirectories(o);
                    List<String> cmd = mvp(lado.equals("base") ? base : atual, flags, c[0], c[1], c[2], seed, o);
                    Path so = out.resolve(o.getFileName() + ".stdout");
                    fut.put(lado + rep, ex.submit(() -> run(cmd, so)));
                }
            }
            System.out.println("\n== " + a[i]);
            boolean ok = true;
            for (Map.Entry<String, Future<Res>> e : fut.entrySet()) {
                Res r = e.getValue().get();
                System.out.printf("   %s rc=%d %.1fs%n", e.getKey(), r.rc(), r.seg());
                ok &= r.rc() == 0;
            }
            if (!ok) {
                System.out.println("   INCONCLUSIVO (erro/timeout)");
                continue;
            }
            System.out.println("   base a x base b   : " + comparar(dir(out, c, "base", "a"), dir(out, c, "base", "b")));
            System.out.println("   atual a x atual b : " + comparar(dir(out, c, "atual", "a"), dir(out, c, "atual", "b")));
            System.out.println("   base a x ATUAL a  : " + comparar(dir(out, c, "base", "a"), dir(out, c, "atual", "a")));
            Path ob = dir(out, c, "base", "a");
            Path oa = dir(out, c, "atual", "a");
            String sb = Files.readString(out.resolve(ob.getFileName() + ".stdout")).replace(ob.toString(), "<OUT>");
            String sa = Files.readString(out.resolve(oa.getFileName() + ".stdout")).replace(oa.toString(), "<OUT>");
            System.out.println("   stdout base a x atual a (caminho normalizado): " + (sb.equals(sa) ? "IDENTICO" : "DIFERE"));
        }
        ex.shutdown();
    }

    private static List<Integer> ref(Path root, String flags, int n, Path saida) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java()));
        cmd.addAll(jvm(flags));
        cmd.addAll(List.of("-cp", cp(root) + SEP + System.getProperty("java.class.path"), "jabs.example.SondaHash",
                "ref", "" + n));
        Res r = run(cmd, saida);
        List<Integer> l = new ArrayList<>();
        for (String s : r.stdout().split("\\R")) {
            if (!s.isBlank()) {
                l.add(Integer.parseInt(s.trim()));
            }
        }
        return l;
    }

    private static void sonda(String[] a) throws Exception {
        Path base = Path.of(a[1]);
        Path atual = Path.of(a[2]);
        Path out = Path.of(a[3]);
        String flags = a[4];
        Files.createDirectories(out);
        List<Integer> seq = ref(atual, flags, 60000, out.resolve("ref.txt"));
        System.out.println("flags JVM=" + flags + "; ref: " + seq.size() + " valores, distintos="
                + seq.stream().distinct().count());
        Pattern pat = Pattern.compile("SONDA=(-?\\d+)");
        for (int i = 5; i < a.length; i++) {
            String[] c = a[i].split(":");
            StringBuilder sb = new StringBuilder(a[i]);
            int[] pos = {-2, -2};
            int j = 0;
            for (String lado : List.of("base", "atual")) {
                Path root = lado.equals("base") ? base : atual;
                Path o = out.resolve(c[0] + "-" + c[1] + "-" + lado);
                Files.createDirectories(o);
                // SondaHash vem de target/test-classes do atual (o classpath deste processo); MVPComparison, da arvore do lado
                List<String> cmd = new ArrayList<>(List.of(java()));
                cmd.addAll(jvm(flags));
                cmd.addAll(List.of("-cp", cp(root) + SEP + System.getProperty("java.class.path"),
                        "jabs.example.SondaHash", "run", "0", "--protocols=" + c[0], "--validators=" + c[1],
                        "--trials=1", "--duration=" + c[2], "--seed=1", "--output=" + o));
                // variavel de ambiente EXTRA=--flag1=v,--flag2=v : flags extras de MVPComparison, SO no lado "atual"
                String extra = System.getProperty("EXTRA", System.getenv("EXTRA"));
                if (extra != null && lado.equals("atual")) {
                    cmd.addAll(Arrays.asList(extra.split(",")));
                }
                Res r = run(cmd, out.resolve(c[0] + "-" + c[1] + "-" + lado + ".stdout"));
                Matcher m = pat.matcher(r.stdout());
                if (r.rc() != 0 || !m.find()) {
                    sb.append(' ').append(lado).append("=ERRO rc=").append(r.rc());
                    j++;
                    continue;
                }
                int v = Integer.parseInt(m.group(1));
                pos[j] = seq.indexOf(v);
                sb.append(' ').append(lado).append(": SONDA=").append(v).append(" posicao=").append(pos[j]);
                j++;
            }
            sb.append(pos[0] == pos[1] && pos[0] >= 0 ? "  => POSICAO IGUAL"
                    : "  => POSICAO DIFERE/INCONCLUSIVO (" + (pos[1] - pos[0]) + ")");
            System.out.println(sb);
        }
    }

    /**
     * ligado BASE ATUAL OUT FLAGS_JVM T0 CASO...: com o registro LIGADO, os arquivos que a baseline tambem gera
     * devem ser BYTE A BYTE iguais, e os arquivos extras devem ser so {@code *-nos.csv} e {@code *-corrida.csv}.
     */
    private static void ligado(String[] a) throws Exception {
        Path base = Path.of(a[1]);
        Path atual = Path.of(a[2]);
        Path out = Path.of(a[3]);
        String flags = a[4];
        String t0 = a[5];
        System.out.println("flags JVM=" + flags + " t0=" + t0);
        for (int i = 6; i < a.length; i++) {
            String[] c = a[i].split(":");
            Path ob = out.resolve(c[0] + "-" + c[1] + "-base");
            Path ol = out.resolve(c[0] + "-" + c[1] + "-ligado");
            Files.createDirectories(ob);
            Files.createDirectories(ol);
            String semente = System.getProperty("SEED", "1");
            Res rb = run(mvp(base, flags, c[0], c[1], c[2], semente, ob), out.resolve(ob.getFileName() + ".stdout"));
            Res rl = run(mvp(atual, flags, c[0], c[1], c[2], semente, ol, "--load-record=true", "--load-record-t0=" + t0),
                    out.resolve(ol.getFileName() + ".stdout"));
            Map<String, byte[]> fb = arquivos(ob);
            Map<String, byte[]> fl = arquivos(ol);
            List<String> difere = new ArrayList<>();
            List<String> extras = new ArrayList<>();
            List<String> faltam = new ArrayList<>();
            for (String k : fb.keySet()) {
                if (!fl.containsKey(k)) {
                    faltam.add(k);
                } else if (!Arrays.equals(fb.get(k), fl.get(k))) {
                    difere.add(k);
                }
            }
            for (String k : fl.keySet()) {
                if (!fb.containsKey(k)) {
                    extras.add(k + "(" + fl.get(k).length + " B)");
                }
            }
            List<String> semHashDifere = new ArrayList<>();
            for (String k : difere) {
                if (!k.endsWith(".csv") || !semHash(fb.get(k)).equals(semHash(fl.get(k)))) {
                    semHashDifere.add(k);
                }
            }
            System.out.println(a[i] + " rc base=" + rb.rc() + " ligado=" + rl.rc() + "; arquivos da baseline iguais byte a"
                    + " byte: " + (difere.isEmpty() && faltam.isEmpty() ? "SIM" : "NAO difere=" + difere + " faltam=" + faltam
                    + " (sem colunas hash: " + (semHashDifere.isEmpty() ? "IDENTICO" : "DIVERGENTE em " + semHashDifere) + ")")
                    + "; arquivos NOVOS: " + extras);
            for (String k : difere) {
                String[] la = new String(fb.get(k), StandardCharsets.UTF_8).split("\\R");
                String[] lb = new String(fl.get(k), StandardCharsets.UTF_8).split("\\R");
                int mostradas = 0;
                for (int j = 0; j < Math.min(la.length, lb.length) && mostradas < 3; j++) {
                    if (!la[j].equals(lb[j])) {
                        System.out.println("    " + k + " linha " + (j + 1) + "\n      base  : " + la[j] + "\n      ligado: " + lb[j]);
                        mostradas++;
                    }
                }
                System.out.println("    linhas base=" + la.length + " ligado=" + lb.length);
            }
        }
    }

    /** fases OUT ATUAL: roda {@link SondaFases} em off e on e imprime a posicao de cada fase na sequencia de hash. */
    private static void fases(String[] a) throws Exception {
        Path out = Path.of(a[1]);
        Files.createDirectories(out);
        List<Integer> seq = ref(Path.of(a[2]), "-", 60000, out.resolve("ref.txt"));
        Pattern pat = Pattern.compile("FASE (\\S+) SONDA=(-?\\d+)");
        for (String modo : new String[]{"off", "on"}) {
            List<String> cmd = new ArrayList<>(List.of(java(), "-cp", cp(Path.of(a[2])) + SEP
                    + System.getProperty("java.class.path"), "jabs.example.SondaFases", modo));
            Res r = run(cmd, out.resolve("fases-" + modo + ".stdout"));
            StringBuilder sb = new StringBuilder("== " + modo + " rc=" + r.rc());
            Matcher m = pat.matcher(r.stdout());
            while (m.find()) {
                sb.append("\n   ").append(m.group(1)).append(" pos=").append(seq.indexOf(Integer.parseInt(m.group(2))));
            }
            System.out.println(sb);
        }
    }

    /**
     * posblocos REFDIR CSV...: para cada CSV, posicao (na sequencia de identityHashCode de uma JVM nova, sem flag) do
     * primeiro, do do meio e do ultimo valor da coluna BlockHashCode. Mostra, DENTRO da simulacao, quantos hashes a
     * thread principal consumiu a mais/menos entre duas corridas.
     */
    private static void posBlocos(String[] a) throws Exception {
        Path out = Path.of(a[1]);
        Files.createDirectories(out);
        List<Integer> seq = ref(Path.of(a[2]), "-", 60000, out.resolve("ref.txt"));
        for (int i = 3; i < a.length; i++) {
            List<Integer> pos = new ArrayList<>();
            int col = -1;
            for (String l : Files.readAllLines(Path.of(a[i]), StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                String[] v = l.split(",", -1);
                if (col < 0) {
                    col = Arrays.asList(v).indexOf("BlockHashCode");
                    continue;
                }
                pos.add(seq.indexOf(Integer.parseInt(v[col])));
            }
            System.out.println(a[i] + ": blocos=" + pos.size() + " posicoes=" + pos);
        }
    }

    /**
     * carga ref: roda NA JVM filha. Mede quantos identityHashCode a thread principal consome ao CARREGAR as classes
     * do registro (sem simular nada). Uso interno: {@code carga-filho PASSO} imprime {@code SONDA=valor}.
     */
    private static void carga(String[] a) throws Exception {
        if (a[1].equals("filho")) {
            String passo = a[2];
            if (passo.equals("carregar")) {
                Class.forName("jabs.network.node.LinkLoadCounters");
                Class.forName("jabs.log.LinkLoadRecorder");
            } else if (passo.equals("parse")) {
                jabs.log.LinkLoadRecorder.parseWindowStart("true", "10");
            } else if (passo.equals("construir")) {
                new jabs.log.LinkLoadRecorder("x", "P", 1L, 10.0);
            } else if (passo.equals("contador")) {
                new jabs.network.node.LinkLoadCounters().upLinkBecameBusy(1.0);
            } else if (passo.equals("classe-existente")) {
                // controle: carregar UMA classe qualquer do simulador, que o registro nao toca
                Class.forName("jabs.log.BlockFinalizationLogger");
            } else if (passo.equals("duas-existentes")) {
                Class.forName("jabs.log.BlockFinalizationLogger");
                Class.forName("jabs.log.BlockchainReorgLogger");
            } else if (passo.equals("so-contadores")) {
                Class.forName("jabs.network.node.LinkLoadCounters");
            } else if (passo.equals("parse-double")) {
                Double.parseDouble("10");
            } else if (passo.equals("pq-iter")) {
                java.util.PriorityQueue<Integer> q = new java.util.PriorityQueue<>();
                q.add(1);
                long s = 0;
                for (Integer i : q) {
                    s += i;
                }
            } else if (passo.equals("pq-toarray")) {
                java.util.PriorityQueue<Integer> q = new java.util.PriorityQueue<>();
                q.add(1);
                q.toArray();
            } else if (passo.equals("dir-existente")) {
                Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
            } else if (passo.equals("dir-checado")) {
                Path d = Path.of(System.getProperty("java.io.tmpdir"));
                if (!Files.isDirectory(d)) {
                    Files.createDirectories(d);
                }
            } else if (passo.equals("thread-vazia")) {
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { }
                });
                t.start();
                t.join();
            } else if (passo.equals("thread-carregar")) {
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Class.forName("jabs.network.node.LinkLoadCounters");
                            Class.forName("jabs.log.LinkLoadRecorder$Result");
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                });
                t.start();
                t.join();
                new jabs.network.node.LinkLoadCounters().upLinkBecameBusy(1.0);
            } else if (passo.equals("carregar-result")) {
                Class.forName("jabs.log.LinkLoadRecorder$Result");
            } else if (passo.equals("load-sem-init-contadores")) {
                Class.forName("jabs.network.node.LinkLoadCounters", false, EvidenciaM1a.class.getClassLoader());
            } else if (passo.equals("load-sem-init-recorder")) {
                Class.forName("jabs.log.LinkLoadRecorder", false, EvidenciaM1a.class.getClassLoader());
            } else if (passo.equals("init-recorder")) {
                Class.forName("jabs.log.LinkLoadRecorder");
            } else if (passo.equals("array-contadores")) {
                Object o = new jabs.network.node.LinkLoadCounters[3];
            } else if (passo.equals("array-no")) {
                Object o = new jabs.network.node.nodes.Node[3];
            } else if (passo.equals("array-networkinterface")) {
                Object o = new jabs.network.node.NetworkInterface[3];
            } else if (!passo.equals("nada")) {
                throw new IllegalArgumentException(passo);
            }
            System.out.println("SONDA=" + System.identityHashCode(new Object()));
            return;
        }
        Path out = Path.of(a[1]);
        Files.createDirectories(out);
        List<Integer> seq = ref(Path.of(a[2]), "-", 5000, out.resolve("ref.txt"));
        Pattern pat = Pattern.compile("SONDA=(-?\\d+)");
        for (String passo : new String[]{"nada", "classe-existente", "duas-existentes", "so-contadores", "carregar",
                "parse-double", "parse", "construir", "contador", "pq-iter", "pq-toarray", "dir-existente",
                "dir-checado", "thread-vazia", "thread-carregar", "carregar-result", "load-sem-init-contadores",
                "load-sem-init-recorder", "init-recorder", "array-contadores", "array-no", "array-networkinterface"}) {
            List<String> cmd = new ArrayList<>(List.of(java(), "-cp", cp(Path.of(a[2])) + SEP
                    + System.getProperty("java.class.path"), "jabs.example.EvidenciaM1a", "carga", "filho", passo));
            Res r = run(cmd, out.resolve(passo + ".stdout"));
            Matcher m = pat.matcher(r.stdout());
            System.out.println("passo=" + passo + " posicao="
                    + (m.find() ? seq.indexOf(Integer.parseInt(m.group(1))) : "ERRO rc=" + r.rc()));
        }
    }

    private static void tempo(String[] a) throws Exception {
        Path atual = Path.of(a[1]);
        Path out = Path.of(a[2]);
        int reps = Integer.parseInt(a[3]);
        String[] c = a[4].split(":");
        Files.createDirectories(out);
        Map<String, List<Double>> t = new LinkedHashMap<>();
        t.put("desligado", new ArrayList<>());
        t.put("ligado", new ArrayList<>());
        for (int i = 0; i < reps; i++) {
            for (String modo : List.of("desligado", "ligado")) {
                Path o = out.resolve(modo + "-" + i);
                Files.createDirectories(o);
                String[] extra = modo.equals("ligado") ? new String[]{"--load-record=true", "--load-record-t0=10"}
                        : new String[0];
                Res r = run(mvp(atual, "-", c[0], c[1], c[2], "1", o, extra), out.resolve(modo + "-" + i + ".stdout"));
                if (r.rc() != 0) {
                    throw new IllegalStateException(modo + " rc=" + r.rc());
                }
                t.get(modo).add(r.seg());
            }
        }
        for (Map.Entry<String, List<Double>> e : t.entrySet()) {
            List<Double> l = new ArrayList<>(e.getValue());
            Collections.sort(l);
            System.out.printf("%s %s: n=%d min=%.2fs mediana=%.2fs max=%.2fs ordem=%s%n", a[4], e.getKey(), l.size(),
                    l.get(0), l.get(l.size() / 2), l.get(l.size() - 1), e.getValue());
        }
    }
}
