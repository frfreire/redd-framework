package jabs.example;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auxiliar de DIAGNOSTICO (NAO e teste JUnit), N1 rodada 2. Mede a posicao da thread principal na sequencia de
 * {@link System#identityHashCode} em PONTOS COMUNS a baseline (325f4e3) e ao atual, para atribuir o deslocamento
 * a um trecho de codigo.
 *
 * <p>Instala um {@link PrintStream} em {@code System.out} que, nas linhas que as DUAS versoes imprimem (banner,
 * "Args:", "Protocol-specific durations", "Testing with", "Protocol:", "Trial", "Running"), le
 * {@code identityHashCode(new Object())} e o guarda (cada marca consome 1 hash, igual nos dois lados porque as
 * marcas caem nas mesmas linhas; as linhas que so o atual imprime - "Network sizing:" - NAO sao marcadas). Na
 * primeira linha "Running" (imediatamente antes de {@code scenario.run()}, depois da construcao do cenario)
 * imprime as posicoes na sequencia de referencia e termina a JVM (Runtime.halt). Nao ha simulacao completa.
 *
 * <p>Uso: {@code java -cp <classes>:<cp>:<test-classes> jabs.example.SondaLinhas REF.TXT args-da-MVPComparison...}.
 * Limite: o hash padrao do HotSpot e um xorshift POR THREAD; so a thread principal e medida. A posicao e relativa
 * ao inicio desta classe (a primeira marca e a posicao 0 + custo do carregamento desta classe, igual nos lados).
 */
public final class SondaLinhas {
    private SondaLinhas() { }

    static final List<String> rotulos = new ArrayList<>();
    static final List<Integer> valores = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        String ref = a[0];
        String[] resto = java.util.Arrays.copyOfRange(a, 1, a.length);
        PrintStream original = System.out;
        System.setOut(new PrintStream(original, true) {
            @Override
            public void println(String x) {
                super.println(x);
                if (x == null) {
                    return;
                }
                String t = x.stripLeading();
                String rotulo = null;
                if (t.startsWith("╔") || t.startsWith("║") || t.startsWith("╚")) {
                    rotulo = "caixa";
                } else if (t.startsWith("Args:")) {
                    rotulo = "Args";
                } else if (t.startsWith("Protocol-specific")) {
                    rotulo = "Protocol-specific durations";
                } else if (t.startsWith("■ Protocol")) {
                    rotulo = "Protocol";
                } else if (t.startsWith("Trial")) {
                    rotulo = "Trial";
                } else if (t.startsWith("Running")) {
                    rotulo = "Running";
                }
                if (rotulo == null) {
                    return;
                }
                rotulos.add(rotulo);
                valores.add(System.identityHashCode(new Object()));
                if (rotulo.equals("Running")) {
                    relatar(ref, original);
                    Runtime.getRuntime().halt(0);
                }
            }
        });
        rotulos.add("main");
        valores.add(System.identityHashCode(new Object()));
        MVPComparison.main(resto);
    }

    static void relatar(String ref, PrintStream out) {
        try {
            List<String> seq = Files.readAllLines(Path.of(ref));
            int anterior = -1;
            for (int i = 0; i < rotulos.size(); i++) {
                int pos = seq.indexOf(String.valueOf(valores.get(i)));
                out.println(String.format("MARCA %-30s pos=%d delta=%d", rotulos.get(i), pos,
                        anterior < 0 ? 0 : pos - anterior - 1));
                anterior = pos;
            }
            out.flush();
        } catch (Exception e) {
            out.println("erro: " + e);
        }
    }
}
