package jabs.scenario;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Orquestrador da matriz de nao-regressao (nao e teste JUnit): roda {@link ByzantineSignatureMain} uma JVM por
 * celula (EthereumMinerNode.memPool e um HashSet de identidade: o identity hash avanca entre execucoes na mesma
 * JVM; ver ParliaRunMain), em paralelo, e grava as linhas ordenadas em ARQUIVO (cada celula tambem e anexada a
 * ARQUIVO.parcial ao terminar, com o tempo gasto, para acompanhar a execucao).
 * Matriz: PROTOCOLOS x n x semente {1, 42} x ataque {NONE, SILENT, WITHHOLD, EQUIVOCATION, DOUBLE_SIGN}
 * (n = 10 e 16 para MCO2/AMBIFY; 24 e 40 para OFFSET/EARTH, ver NS_NAKAMOTO).
 * Uso: java -cp target/test-classes:target/classes:$(cat target/cp.txt) jabs.scenario.SignatureMatrixMain
 *      ARQUIVO [PROTOCOLOS separados por virgula; padrao MCO2,AMBIFY,OFFSET,EARTH]
 */
public final class SignatureMatrixMain {
    private static final int[] NS = {10, 16};
    /**
     * Nakamoto (BitcoinCoreP2P: 8 conexoes de saida DISTINTAS por no) so completa o setup quando n*8 cabe nos
     * n(n-1)/2 pares: n=10 (80 > 45) e n=16 (128 > 120) ficam em laco infinito em connectToNetwork (defeito da
     * base, fora do hook; mesma familia do "Parlia/Casper exigem n >= 10"). Por isso OFFSET/EARTH usam 24 e 40.
     */
    private static final int[] NS_NAKAMOTO = {24, 40};
    private static final long[] SEEDS = {1, 42};
    private static final String[] ATAQUES = {"NONE", "SILENT", "WITHHOLD", "EQUIVOCATION", "DOUBLE_SIGN"};

    private static String celula(String p, long s, int n, String a, Path parcial) throws Exception {
        long t0 = System.nanoTime();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "jabs.scenario.ByzantineSignatureMain", p, Long.toString(s), Integer.toString(n), a);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        String sig = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(),
                StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("SIG ")) sig = l.substring(4);
            }
        }
        if (!proc.waitFor(30, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            sig = p + " seed=" + s + " n=" + n + " " + a + " TIMEOUT";
        }
        if (sig == null) sig = p + " seed=" + s + " n=" + n + " " + a + " SEM_SAIDA exit=" + proc.exitValue();
        long seg = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0);
        synchronized (SignatureMatrixMain.class) {
            Files.writeString(parcial, sig + "   [" + seg + "s]\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return sig;
    }

    public static void main(String[] args) throws Exception {
        String[] protos = (args.length > 1 ? args[1] : "MCO2,AMBIFY,OFFSET,EARTH").split(",");
        Path parcial = Path.of(args[0] + ".parcial");
        Files.deleteIfExists(parcial);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> fs = new ArrayList<>();
        for (String p : protos) {
            for (int n : (p.equals("OFFSET") || p.equals("EARTH")) ? NS_NAKAMOTO : NS) {
                for (long s : SEEDS) {
                    for (String a : ATAQUES) {
                        fs.add(pool.submit(() -> celula(p, s, n, a, parcial)));
                    }
                }
            }
        }
        List<String> linhas = new ArrayList<>();
        for (Future<String> f : fs) linhas.add(f.get());
        pool.shutdown();
        Collections.sort(linhas);
        Files.write(Path.of(args[0]), linhas, StandardCharsets.UTF_8);
        System.out.println(linhas.size() + " linhas em " + args[0]);
    }

    private SignatureMatrixMain() {}
}
