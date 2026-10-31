package jabs.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auxiliar de DIAGNOSTICO (NAO e teste JUnit), N1 rodada 2. Mede, em UMA JVM nova, quantos
 * {@link System#identityHashCode} a thread principal consome em cada construcao de linguagem/biblioteca usada pelo
 * codigo pre-simulacao da N1. Cada "estagio" e executado uma vez na ordem pedida (a primeira execucao paga o
 * custo de ligacao de invokedynamic/carga de classe; era essa a hipotese a testar).
 *
 * <p>Uso: {@code java -cp <test-classes>:<classes>:<cp> jabs.example.SondaMicro REF.TXT estagio1 estagio2 ...}
 * (REF.TXT = saida de {@code SondaHash ref 300000}). Saida: {@code estagio posicao_depois delta}. Limite: a
 * posicao e relativa ao primeiro uso da sonda (a propria leitura de REF.TXT acontece no fim, depois das medidas);
 * o hash padrao do HotSpot e um xorshift POR THREAD, entao so a thread principal e medida.
 */
public final class SondaMicro {
    private SondaMicro() { }

    public static void main(String[] a) throws Exception {
        List<String> nomes = new ArrayList<>();
        List<Integer> valores = new ArrayList<>();
        nomes.add("inicio");
        valores.add(System.identityHashCode(new Object()));
        for (int i = 1; i < a.length; i++) {
            executar(a[i]);
            nomes.add(a[i]);
            valores.add(System.identityHashCode(new Object()));
        }
        List<String> ref = Files.readAllLines(Path.of(a[0]));
        int anterior = -1;
        for (int i = 0; i < nomes.size(); i++) {
            int pos = ref.indexOf(String.valueOf(valores.get(i)));
            // pos conta tambem as i sondas anteriores; o delta do estagio i e (pos - anterior - 1)
            System.out.println(String.format("%-28s pos=%d delta_estagio=%d", nomes.get(i), pos,
                    i == 0 ? 0 : pos - anterior - 1));
            anterior = pos;
        }
    }

    static volatile Object sumidouro;

    static void executar(String estagio) throws Exception {
        switch (estagio) {
            case "nada":
                break;
            case "thread-1": {
                Thread t = new Thread(() -> sumidouro = "x");
                t.start();
                t.join();
                break;
            }
            case "thread-2": {
                Thread t = new Thread(() -> sumidouro = "y");
                t.start();
                t.join();
                break;
            }
            case "thread-load-classes": {
                Thread t = new Thread(() -> {
                    try {
                        Class.forName("jabs.config.NetworkSizing");
                        Class.forName("jabs.config.NetworkSizing$Semantics");
                        Class.forName("jabs.config.NetworkSizing$Decomposition");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                });
                t.start();
                t.join();
                break;
            }
            case "forName-carregada":
                sumidouro = Class.forName("java.lang.String");
                break;
            case "new-app-class":
                sumidouro = new jabs.example.SondaMicro.Folha();
                break;
            case "new-app-class2":
                sumidouro = new jabs.example.SondaMicro.Folha2();
                break;
            case "string-switch":
                switch (a1()) {
                    case "abc":
                        sumidouro = "1";
                        break;
                    case "def":
                        sumidouro = "2";
                        break;
                    default:
                        break;
                }
                break;
            case "arraylist":
                sumidouro = new ArrayList<String>();
                break;
            case "lambda":
                sumidouro = (java.util.function.Supplier<String>) () -> a1();
                break;
            case "load-NetworkSizing":
                sumidouro = Class.forName("jabs.config.NetworkSizing");
                break;
            case "load-Semantics":
                sumidouro = Class.forName("jabs.config.NetworkSizing$Semantics");
                break;
            case "load-Decomposition":
                sumidouro = Class.forName("jabs.config.NetworkSizing$Decomposition");
                break;
            case "load-ArrayList":
                sumidouro = Class.forName("java.util.TreeSet");
                break;
            case "parseArgs-novo":
                sumidouro = MVPComparison.parseArgs(new String[] {"--protocols=MCO2", "--validators=10"});
                break;
            case "isNodesMode-novo":
                sumidouro = MVPComparison.isNodesMode(null);
                break;
            case "describeSizing-novo-MCO2":
                sumidouro = MVPComparison.describeSizing("MCO2", false, 10);
                break;
            case "describeSizing-novo-AMBIFY":
                sumidouro = MVPComparison.describeSizing("AMBIFY", false, 10);
                break;
            case "describeSizing-novo-ARQ":
                sumidouro = MVPComparison.describeSizing("ARQ", false, 7);
                break;
            case "describeSizing-novo-OFFSET":
                sumidouro = MVPComparison.describeSizing("OFFSET", false, 40);
                break;
            case "fromFlag":
                sumidouro = jabs.config.NetworkSizing.Semantics.fromFlag(null);
                break;
            case "concat-1str":
                sumidouro = "# n=" + a1() + " fim\n";
                break;
            case "concat-2str":
                sumidouro = "# n=" + a1() + " x " + a1() + " fim\n";
                break;
            case "concat-int":
                sumidouro = "# n=" + a1().length() + " fim\n";
                break;
            case "sb-1str":
                sumidouro = new StringBuilder().append("# n=").append(a1()).append(" fim\n").toString();
                break;
            case "hashmap-str":
                sumidouro = new java.util.HashMap<String, String>().put(a1(), a1());
                break;
            case "enum-values":
                sumidouro = jabs.config.NetworkSizing.Semantics.values();
                break;
            case "enum-hashmap": {
                java.util.Map<Object, String> m = new java.util.HashMap<>();
                m.put(jabs.config.NetworkSizing.Semantics.LEGACY, "x");
                sumidouro = m;
                break;
            }
            case "enum-switch":
                switch (jabs.config.NetworkSizing.Semantics.fromFlag(null)) {
                    case LEGACY:
                        sumidouro = "l";
                        break;
                    default:
                        sumidouro = "n";
                        break;
                }
                break;
            case "record-new":
                sumidouro = new jabs.config.NetworkSizing.Decomposition(jabs.config.NetworkSizing.Semantics.LEGACY,
                        1, 1, 1);
                break;
            case "describe":
                sumidouro = jabs.config.NetworkSizing.mvpLegacy(10).describe();
                break;
            default:
                throw new IllegalArgumentException("estagio: " + estagio);
        }
    }

    static final class Folha { }

    static final class Folha2 { }

    static String a1() {
        return "abc";
    }
}
