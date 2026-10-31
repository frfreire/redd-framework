package jabs.example;

import jabs.config.NetworkSizing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Auxiliar de DIAGNOSTICO (NAO e teste JUnit), N1 rodada 2. Mede, trecho a trecho, quantos
 * {@link System#identityHashCode} a thread principal consome no PRE-SIMULACAO (as linhas de {@code MVPComparison.main}
 * antes do primeiro cenario), em tres versoes, cada uma numa JVM propria:
 * <ul>
 *   <li>{@code base}: a baseline 325f4e3 (replica do trecho original);</li>
 *   <li>{@code antigo}: o codigo da N1 ANTES da rodada 2 (replica: {@code CliArgs} com {@code Set.of}/{@code TreeSet},
 *       {@code NetworkSizing.Semantics} (enum), {@code describeSizing} com concatenacao e record);</li>
 *   <li>{@code novo}: chama as rotinas REAIS de {@code MVPComparison} (parseArgs, isNodesMode, describeSizing).</li>
 * </ul>
 * As replicas {@code base} e {@code antigo} vivem aqui porque as classes originais nao existem mais no codigo de
 * producao; a prova de ponta a ponta do codigo novo e {@link SondaLinhas} (usa a {@code MVPComparison} real).
 *
 * <p>Uso: {@code java -cp ... jabs.example.SondaPreSim REF.TXT base|antigo|novo PROTOCOLO N}. Imprime
 * {@code TRECHO delta} (hashes consumidos pelo trecho). Cada marcador consome 1 hash em todas as versoes.
 * Limite: o hash padrao do HotSpot e um xorshift POR THREAD; so a thread principal e medida; as replicas sao
 * equivalentes por construcao (mesmos tipos de instrucao), nao identicas byte a byte.
 */
public final class SondaPreSim {
    private SondaPreSim() { }

    static final List<String> rotulos = new ArrayList<>();
    static final List<Integer> valores = new ArrayList<>();

    static void marca(String r) {
        rotulos.add(r);
        valores.add(System.identityHashCode(new Object()));
    }

    public static void main(String[] a) throws Exception {
        String ref = a[0];
        String versao = a[1];
        String proto = a[2].toUpperCase();
        int n = Integer.parseInt(a[3]);
        String[] args = {"--protocols=" + proto, "--validators=" + n, "--trials=1", "--duration=60", "--seed=1",
                "--output=/tmp/opencode/n1-r2-sonda-" + versao};
        PrintStream real = System.out;
        PrintStream mudo = new PrintStream(new ByteArrayOutputStream(), true);
        // No fluxo real MVPComparison ja esta carregada (e a classe do main): carrega-la aqui, FORA da medicao, nas
        // tres versoes, evita contar 1 hash de carga de classe que o main real nao paga.
        Class.forName("jabs.example.MVPComparison");
        marca("inicio");
        Map<String, String> cli;
        boolean antigo = versao.equals("antigo");
        boolean novo = versao.equals("novo");
        if (antigo) {
            cli = CliArgsAntigo.parse(args, knownAntigo());
        } else if (novo) {
            cli = MVPComparison.parseArgs(args);
        } else {
            cli = parseArgsBase(args);
        }
        marca("1 parse dos argumentos");
        NetworkSizing.Semantics sem = null;
        boolean nodes = false;
        if (antigo) {
            sem = NetworkSizing.Semantics.fromFlag(cli.get(NetworkSizing.FLAG));
        } else if (novo) {
            nodes = MVPComparison.isNodesMode(cli.get(NetworkSizing.FLAG));
        }
        marca("2 semantica do n");
        List<Integer> validators = parseIntList(cli.getOrDefault("validators", "200"));
        List<String> protocols = parseStringList(cli.getOrDefault("protocols", "EARTH"));
        int trials = Integer.parseInt(cli.getOrDefault("trials", "5"));
        double dur = Double.parseDouble(cli.getOrDefault("duration", "600"));
        long seed = Long.parseLong(cli.getOrDefault("seed", "12345"));
        String outputRoot = cli.getOrDefault("output", "output/mvp-validation");
        String csvOut = cli.getOrDefault("csv-out", outputRoot + "/mvp-results.csv");
        boolean tx = Boolean.parseBoolean(cli.getOrDefault("enable-tx", "true"));
        double txi = Double.parseDouble(cli.getOrDefault("tx-interval", "5.0"));
        Map<String, Double> durs = new HashMap<>();
        for (String p : protocols) {
            durs.put(p, dur);
        }
        marca("3 parseXxx e durations (comum)");
        List<String> linhas = new ArrayList<>();
        if (antigo) {
            for (int v : validators) {
                for (String p : protocols) {
                    linhas.add(describeSizingAntigo(p, sem, v));
                }
            }
        } else if (novo) {
            for (int v : validators) {
                for (String p : protocols) {
                    linhas.add(MVPComparison.describeSizing(p, nodes, v));
                }
            }
        }
        marca("4 describeSizing (so atual)");
        System.setOut(mudo);
        if (antigo) {
            System.out.println("Args: n-semantics=" + sem.rotulo() + ", validators=" + validators + ", protocols="
                    + protocols + ", trials=" + trials + ", default duration=" + dur + "s, seed=" + seed + ", out="
                    + outputRoot + ", csv-out=" + csvOut);
        } else {
            System.out.println("Args: validators=" + validators + ", protocols=" + protocols + ", trials=" + trials
                    + ", default duration=" + dur + "s, seed=" + seed + ", out=" + outputRoot + ", csv-out=" + csvOut);
        }
        marca("5 println Args");
        System.out.println("Protocol-specific durations: " + durs);
        marca("6 println durations");
        for (String l : linhas) {
            if (antigo) {
                System.out.println("Network sizing: " + l);
            } else {
                System.out.println(new StringBuilder("Network sizing: ").append(l).toString());
            }
        }
        marca("7 println sizing (so atual)");
        String cab;
        if (antigo) {
            cab = "# n_semantics=" + sem.rotulo() + " (coluna n = valor pedido na linha de comando)\n";
            for (String l : linhas) {
                cab = "# " + l + "\n";
            }
        } else if (novo) {
            cab = new StringBuilder("# n_semantics=").append(nodes ? "nodes" : "legacy").append(" x\n").toString();
            for (String l : linhas) {
                cab = new StringBuilder("# ").append(l).append('\n').toString();
            }
        } else {
            cab = "";
        }
        marca("8 cabecalho '#' do CSV (so atual)");
        System.setOut(real);
        List<String> ref2 = Files.readAllLines(Path.of(ref));
        int anterior = -1;
        int total = 0;
        for (int i = 0; i < rotulos.size(); i++) {
            int pos = ref2.indexOf(String.valueOf(valores.get(i)));
            int delta = i == 0 ? 0 : pos - anterior - 1;
            total += delta;
            System.out.println(String.format("%-8s %-8s N=%-4d %-36s delta=%d", versao, proto, n, rotulos.get(i), delta));
            anterior = pos;
        }
        System.out.println(String.format("%-8s %-8s N=%-4d %-36s delta=%d", versao, proto, n, "TOTAL pre-simulacao", total));
        if (cab.length() == 12345) {
            System.out.println(cab);
        }
    }

    // ---- replica do trecho ORIGINAL (325f4e3) ------------------------------------------------------------

    static Map<String, String> parseArgsBase(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2) {
                    map.put(parts[0], parts[1]);
                }
            }
        }
        return map;
    }

    static List<Integer> parseIntList(String csv) {
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(Integer.parseInt(s.trim()));
        }
        return list;
    }

    static List<String> parseStringList(String csv) {
        List<String> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(s.trim().toUpperCase());
        }
        return list;
    }

    // ---- replica do trecho da N1 ANTES da rodada 2 -------------------------------------------------------

    static Set<String> knownAntigo() {
        return Set.of("validators", "protocols", "trials", "duration", "durations", "seed", "output", "csv-out",
                "enable-tx", "tx-interval", NetworkSizing.FLAG);
    }

    /** Copia do {@code CliArgs.parse} da N1 antes da rodada 2 (classe propria, HashMap + TreeSet na falha). */
    static final class CliArgsAntigo {
        private CliArgsAntigo() { }

        static Map<String, String> parse(String[] args, Set<String> conhecidas) {
            Map<String, String> map = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
                    throw new IllegalArgumentException("Argumento invalido '" + arg + "': use --chave=valor. Chaves "
                            + "conhecidas: " + new TreeSet<>(conhecidas));
                }
                String[] parts = arg.substring(2).split("=", 2);
                String chave = parts[0];
                if (!conhecidas.contains(chave)) {
                    throw new IllegalArgumentException("Flag desconhecida '--" + chave + "'");
                }
                if (map.containsKey(chave)) {
                    throw new IllegalArgumentException("Flag repetida '--" + chave + "'");
                }
                map.put(chave, parts[1]);
            }
            return map;
        }
    }

    /** Copia do {@code describeSizing} antigo (concatenacao com +, enum Semantics, record Decomposition). */
    static String describeSizingAntigo(String protocol, NetworkSizing.Semantics semantics, int n) {
        switch (protocol.toUpperCase()) {
            case "MCO2":
            case "TREE":
                return "protocol=" + protocol + " " + NetworkSizing.forMvp(semantics, n).describe()
                        + " voters=casper_voters";
            case "AMBIFY":
                return "protocol=" + protocol + " " + NetworkSizing.forMvp(semantics, n).describe()
                        + " voters=parlia_validators";
            case "ARQ":
                return "protocol=ARQ n_semantics=" + semantics.rotulo() + " n_requested=" + n + " nodes=" + n
                        + " validators=" + n;
            case "OFFSET":
            case "EARTH":
                return "protocol=" + protocol + " n_semantics=" + semantics.rotulo() + " n_requested=" + n
                        + " nodes=" + n + " miners=" + n + " stakeholders=0";
            default:
                return "protocol=" + protocol + " sizing=desconhecido (protocolo nao reconhecido)";
        }
    }
}
