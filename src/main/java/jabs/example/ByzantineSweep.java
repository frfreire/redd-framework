package jabs.example;

import jabs.config.ByzantineConfig;
import jabs.config.NetworkSizing;
import jabs.metrics.SimulationMetrics;
import jabs.scenario.DAGsperNetworkScenario;
import jabs.scenario.HybridNetworkScenario;
import jabs.scenario.CasperEthereumNetworkScenario;
import jabs.scenario.BitcoinGlobalNetworkScenario;
import jabs.scenario.ParliaBSCNetworkScenario;
import jabs.scenario.ParliaBSCNetworkScenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Utility to empirically sweep Byzantine validator counts and record security
 * results.
 * Generates an aggregated CSV with columns:
 * protocol,n,f,trial,seed,avgPdv,avgFinalizationTime,secure
 */
public class ByzantineSweep {

    public static void main(String[] args) throws IOException {
        run(args);
    }

    /** Ataques que o DAGsper implementa (ver DAGsperNetworkScenario); qualquer outro e recusado la. */
    private static final java.util.Set<ByzantineConfig.AttackType> DAGSPER_ATTACKS = java.util.EnumSet.of(
            ByzantineConfig.AttackType.SILENT, ByzantineConfig.AttackType.WITHHOLD);

    /**
     * Resolve o ataque pedido e falha alto quando ele e invalido ou nao suportado pelo protocolo.
     * Para DAGSPER nao ha padrao: o ataque precisa ser pedido explicitamente (SILENT ou WITHHOLD), em vez de
     * escolher outro em silencio.
     *
     * @throws IllegalArgumentException      ataque ausente (DAGSPER) ou desconhecido
     * @throws UnsupportedOperationException ataque que o protocolo nao implementa
     */
    static ByzantineConfig.AttackType resolveAttack(String type, String attackStr) {
        if (attackStr == null) {
            if ("DAGSPER".equals(type)) {
                throw new IllegalArgumentException("DAGSPER exige --attack explicito (SILENT ou WITHHOLD): "
                        + "nao ha ataque padrao que ele suporte");
            }
            attackStr = "EQUIVOCATION";
        }
        ByzantineConfig.AttackType attack;
        try {
            attack = ByzantineConfig.AttackType.valueOf(attackStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ataque desconhecido: '" + attackStr + "'. Validos: "
                    + java.util.Arrays.toString(ByzantineConfig.AttackType.values()), e);
        }
        if ("DAGSPER".equals(type) && !DAGSPER_ATTACKS.contains(attack)) {
            throw new UnsupportedOperationException("DAGSPER so implementa " + DAGSPER_ATTACKS + " (recebido "
                    + attack + "): o sweep nao troca o ataque em silencio");
        }
        return attack;
    }

    /**
     * Um nivel de f sem nenhum trial executado nao tem medida: falha alto em vez de gerar NaN (0/0) ou zeros
     * que pareceriam medicao.
     */
    static void requireMeasuredTrials(int trialsExecuted, int trialsFailed, int f, int n) {
        if (trialsExecuted <= 0) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Nenhum trial executado com sucesso para f=%d (n=%d; %d falha(s) de execucao): "
                            + "NAO_MEDIDO, sweep abortado em vez de gravar NaN/zero",
                    f, n, trialsFailed));
        }
    }

    static void run(String[] args) throws IOException {
        java.util.Map<String, String> cli = parseArgs(args);
        // N1: semantica do n. Ausente = legacy (comportamento anterior); valor invalido falha alto (a unica validacao nova de flag).
        // Booleano, nao enum, e sem classe nova no modo legacy: ver "Rodada 2" no README da N1.
        final boolean nodesMode = isNodesMode(cli.get(NetworkSizing.FLAG));
        int n = Integer.parseInt(cli.getOrDefault("validators", "100"));
        int maxByz = Integer.parseInt(cli.getOrDefault("max-byz", String.valueOf(n / 2)));
        int trials = Integer.parseInt(cli.getOrDefault("trials", "30"));
        double duration = Double.parseDouble(cli.getOrDefault("duration", "600"));
        String type = cli.getOrDefault("type", "VOTING").toUpperCase(Locale.ROOT);
        ByzantineConfig.AttackType attack = resolveAttack(type, cli.get("attack"));
        // transaction generation options (used by POW scenarios)
        boolean enableTransactions = Boolean.parseBoolean(cli.getOrDefault("enable-tx", "true"));
        double txInterval = Double.parseDouble(cli.getOrDefault("tx-interval", "5.0"));
        String out = cli.getOrDefault("out", "output/byzantine-sweep.csv");

        // Decomposicao POS/POSA calculada antes de simular (n invalido em 'nodes' falha antes de gravar qualquer coisa).
        // Outros tipos tem n = numero de nos nas duas semanticas.
        final boolean temDecomposicao = type.equals("POS") || type.equals("POSA");
        final int[] sizing = temDecomposicao ? sweepSizing(nodesMode, n) : null;
        final String sizingLine = describeSizing(type, nodesMode, n, sizing);
        System.out.println(new StringBuilder("Network sizing: ").append(sizingLine).toString());

        Path outPath = Paths.get(out);
        Path outDir = outPath.getParent() == null ? Paths.get(".") : outPath.getParent();
        Files.createDirectories(outDir);

        try (BufferedWriter bw = Files.newBufferedWriter(outPath)) {
            // Modo legacy: o CSV e BYTE A BYTE o da baseline 325f4e3 (comeca direto no cabecalho de colunas, sem
            // linhas '#'); o dimensionamento legacy ja sai no stdout ("Network sizing:").
            // Modo nodes: linhas '#' (ignoradas por scripts/jabs/*.py): a coluna n e o PEDIDO; nos construidos aqui.
            if (nodesMode) {
                bw.write(new StringBuilder("# n_semantics=nodes")
                        .append(" (coluna n = valor pedido na linha de comando)\n").toString());
                bw.write(new StringBuilder("# ").append(sizingLine).append('\n').toString());
            }
            bw.write(
                    "protocol,n,f,trials,avgPdv,stdPdv,avgFinalizationTime,stdFinalizationTime,avgConfirmationLatency,stdConfirmationLatency,avgBlockCount,stdBlockCount,avgTraffic,stdTraffic,avgEmpiricalBFT_pct,stdEmpiricalBFT_pct,secureCount\n");

            int step = Math.max(1, n / 100); // step corresponds to ~1% of N
            java.util.List<Integer> fValues = new java.util.ArrayList<>();
            fValues.add(0); // include the 0-byzantine case
            for (int v = step; v <= maxByz; v += step) {
                fValues.add(v);
            }

            for (int f : fValues) {
                boolean anySecureInThisF = false;
                int trialsExecuted = 0;
                int trialsFailed = 0;
                double sumPdv = 0.0;
                double sumFinalTime = 0.0;
                double sumConfirmationLatency = 0.0;
                double sumBlocks = 0.0;
                double sumTraffic = 0.0;
                double sumEmpiricalBFT = 0.0;
                double sumPdvSq = 0.0;
                double sumFinalTimeSq = 0.0;
                double sumConfirmationLatencySq = 0.0;
                double sumBlocksSq = 0.0;
                double sumTrafficSq = 0.0;
                double sumEmpiricalBFTSq = 0.0;
                int secureCount = 0;

                for (int t = 0; t < trials; t++) {
                    long seed = Long.parseLong(cli.getOrDefault("seed", "12345")) + n * 1000L + t;

                    SimulationMetrics metrics = new SimulationMetrics();
                    metrics.setTotalValidators(n);
                    metrics.setByzantineValidators(f);

                    try {
                        switch (type) {
                            case "VOTING": {
                                double byzPct = (n == 0) ? 0.0 : (100.0 * f / n);
                                HybridNetworkScenario scenario = new HybridNetworkScenario(
                                        "sweep/arqREDD/HybridSweep-" + n + "n-" + f + "b",
                                        seed,
                                        n,
                                        duration,
                                        byzPct,
                                        attack);
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            case "POS": {
                                int numMiners = sizing[0];
                                int numStakeholders = sizing[1];
                                double byzPct = (n == 0) ? 0.0 : (100.0 * f / n);
                                CasperEthereumNetworkScenario scenario = new CasperEthereumNetworkScenario(
                                        "sweep/Ethereum/CasperSweep-" + n + "n-" + f + "b",
                                        seed,
                                        duration,
                                        15.0,
                                        14,
                                        numMiners,
                                        numStakeholders,
                                        byzPct,
                                        attack);
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            case "POW": {
                                int confirmationDepth = 6;
                                double byzPct = (n == 0) ? 0.0 : (100.0 * f / n);
                                BitcoinGlobalNetworkScenario scenario = new BitcoinGlobalNetworkScenario(
                                        "sweep/Bitcoin/BitcoinSweep-" + n + "n-" + f + "b",
                                        seed,
                                        (long) duration,
                                        600.0,
                                        n,  // numMiners (represents hashpower distribution)
                                        0,  // numNodes (non-mining nodes)
                                        confirmationDepth,
                                        enableTransactions, // whether to generate txs
                                        txInterval,
                                        byzPct,  // Byzantine percentage of hashpower
                                        attack);
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            case "POSA": {
                                double byzPct = (n == 0) ? 0.0 : (100.0 * f / n);
                                int numMiners = sizing[0];
                                int turnLength = Integer.parseInt(cli.getOrDefault("turn-length", "3"));
                                int epochLength = Integer.parseInt(cli.getOrDefault("epoch-length", "21"));

                                ParliaBSCNetworkScenario scenario = new ParliaBSCNetworkScenario(
                                    "sweep/Parlia/ParliaSweep-" + n + "n-" + f + "b",
                                    seed,
                                    duration,
                                    3.0,
                                    turnLength,
                                    epochLength,
                                    numMiners,
                                    // legacy: o proprio n (sem max(1, n) do POS), como antes da N1
                                    nodesMode ? sizing[1] : n);

                                ByzantineConfig byzConfig = new ByzantineConfig(
                                    n,
                                    byzPct,
                                    attack,
                                    seed);
                                scenario.setByzantineConfig(byzConfig);

                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                    .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                                }
                            case "DAGSPER": {
                                // ADR-0006, tarefa 5. n = total de nos (todos votam); 30% mineradores. Ataques:
                                // SILENT/WITHHOLD (o resto e recusado pelo cenario). Sem Tt: enable-tx so se pedido.
                                int numMiners = (int) Math.max(1, Math.round(n * 0.3));
                                DAGsperNetworkScenario scenario = new DAGsperNetworkScenario(
                                        "sweep/DAGsper/DAGsperSweep-" + n + "n-" + f + "b",
                                        seed,
                                        duration,
                                        15.0,
                                        14,
                                        numMiners,
                                        n - numMiners,
                                        f,
                                        attack,
                                        enableTransactions,
                                        txInterval);
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            default:
                                throw new IllegalArgumentException("Unsupported protocol type: " + type);
                        }

                        double maxPdv = Double.parseDouble(cli.getOrDefault("max-pdv-pct", "0.5"));
                        boolean secure = metrics.evaluateSecurity(maxPdv);
                        if (secure) {
                            anySecureInThisF = true;
                            secureCount++;
                        }

                        trialsExecuted++;
                        double pdvVal = metrics.getDoubleSpendSuccessProbability();
                        double finalTimeVal = metrics.getAverageBlockFinalizationTime();
                        double confirmLatencyVal = metrics.getAverageTransactionConfirmationLatency();
                        double blocksVal = metrics.getBlockCount();
                        double trafficVal = metrics.getAverageTrafficPerBlock();
                        double empiricalBFTVal = metrics.getEmpiricalByzantineFaultTolerance();
                        
                        sumPdv += pdvVal;
                        sumFinalTime += finalTimeVal;
                        sumConfirmationLatency += confirmLatencyVal;
                        sumBlocks += blocksVal;
                        sumTraffic += trafficVal;
                        sumEmpiricalBFT += empiricalBFTVal;
                        
                        sumPdvSq += pdvVal * pdvVal;
                        sumFinalTimeSq += finalTimeVal * finalTimeVal;
                        sumConfirmationLatencySq += confirmLatencyVal * confirmLatencyVal;
                        sumBlocksSq += blocksVal * blocksVal;
                        sumTrafficSq += trafficVal * trafficVal;
                        sumEmpiricalBFTSq += empiricalBFTVal * empiricalBFTVal;

                    } catch (IllegalArgumentException | UnsupportedOperationException e) {
                        // erro de CONFIGURACAO (ataque invalido/nao suportado, parametro fora do dominio):
                        // nao e falha de trial, aborta o sweep.
                        System.err.println("Configuration error for f=" + f + ", trial=" + t
                                + " (sweep aborted): " + e.getMessage());
                        throw e;
                    } catch (RuntimeException e) {
                        // falha de execucao genuina da simulacao: contada, nunca silenciada
                        trialsFailed++;
                        System.err.println("Run failed for f=" + f + ", trial=" + t + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                requireMeasuredTrials(trialsExecuted, trialsFailed, f, n);

                // compute averages across executed trials
                double avgPdv = sumPdv / trialsExecuted;
                double avgFinalTime = sumFinalTime / trialsExecuted;
                double avgConfirmationLatency = sumConfirmationLatency / trialsExecuted;
                double avgBlockCount = sumBlocks / trialsExecuted;
                double avgTraffic = sumTraffic / trialsExecuted;
                double avgEmpiricalBFT = sumEmpiricalBFT / trialsExecuted; // stored as ratio in metrics
                
                // compute standard deviations (sample std dev formula)
                int denom = Math.max(1, trialsExecuted - 1);
                double stdPdv = Math.sqrt(Math.max(0, (sumPdvSq - (sumPdv * sumPdv) / trialsExecuted) / denom));
                double stdFinalTime = Math.sqrt(Math.max(0, (sumFinalTimeSq - (sumFinalTime * sumFinalTime) / trialsExecuted) / denom));
                double stdConfirmationLatency = Math.sqrt(Math.max(0, (sumConfirmationLatencySq - (sumConfirmationLatency * sumConfirmationLatency) / trialsExecuted) / denom));
                double stdBlockCount = Math.sqrt(Math.max(0, (sumBlocksSq - (sumBlocks * sumBlocks) / trialsExecuted) / denom));
                double stdTraffic = Math.sqrt(Math.max(0, (sumTrafficSq - (sumTraffic * sumTraffic) / trialsExecuted) / denom));
                double stdEmpiricalBFT = Math.sqrt(Math.max(0, (sumEmpiricalBFTSq - (sumEmpiricalBFT * sumEmpiricalBFT) / trialsExecuted) / denom));

                // Decide security using aggregated averages
                double maxPdv = Double.parseDouble(cli.getOrDefault("max-pdv-pct", "0.5"));
                // Map protocol type to ConsensusType
                SimulationMetrics.ConsensusType consensusType;
                switch (type) {
                    case "VOTING":
                    case "DAGSPER": // quorum 2f+1: tolerancia f < n/3, como VOTING
                        consensusType = SimulationMetrics.ConsensusType.VOTING;
                        break;
                    case "POS":
                        consensusType = SimulationMetrics.ConsensusType.POS;
                        break;
                    case "POW":
                        consensusType = SimulationMetrics.ConsensusType.POW;
                        break;
                    case "POSA":
                        consensusType = SimulationMetrics.ConsensusType.POS;
                        break;
                    default:
                        consensusType = SimulationMetrics.ConsensusType.VOTING;
                        break;
                }

                boolean avgSecure = SimulationMetrics.evaluateSecurityFromAverages(
                        consensusType,
                        maxPdv,
                        avgPdv,
                        avgEmpiricalBFT,
                        n,
                        f);

                // Use averaged decision as indicator whether this f is considered secure
                anySecureInThisF = avgSecure;

                // write aggregated row: empiricalBFT reported as percentage
                bw.write(String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.0f,%.0f,%.6f,%.6f,%.6f,%.6f,%d\n",
                        type, n, f, trialsExecuted,
                        avgPdv, stdPdv,
                        avgFinalTime, stdFinalTime,
                        avgConfirmationLatency, stdConfirmationLatency,
                        avgBlockCount, stdBlockCount,
                        avgTraffic, stdTraffic,
                        avgEmpiricalBFT * 100.0, stdEmpiricalBFT * 100.0,
                        secureCount));
                bw.flush();

                // If averaged result reports insecure for this f, stop the sweep early
                if (!anySecureInThisF) {
                    System.out.println(String.format(Locale.ROOT,
                            "Averaged decision: no secure result for f=%d (n=%d). Stopping further f values to save time.",
                            f, n));
                    // break;
                }
            }
        }

        System.out.println("Sweep complete. Results in " + out);
    }

    /**
     * Leitura de {@code --chave=valor} IDENTICA a da baseline 325f4e3 (N1, rodada 3); mesma regra de
     * {@code MVPComparison.parseArgs} (um teste confere que as duas se comportam igual), reescrita aqui sem classe
     * nova pelo mesmo motivo: o modo legacy nao pode consumir hash de identidade a mais que a baseline antes da
     * simulacao. Argumento sem '=' e IGNORADO, chave desconhecida entra no mapa sem ser lida, chave repetida: a
     * ultima vence. Unica adicao: AVISO no stderr para chave desconhecida e para argumento fora de
     * {@code --chave=valor}, sem alterar o fluxo.
     */
    static java.util.Map<String, String> parseArgs(String[] args) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2) {
                    map.put(parts[0], parts[1]);
                    boolean conhecida = false;
                    for (String k : KNOWN) {
                        if (k.equals(parts[0])) {
                            conhecida = true;
                            break;
                        }
                    }
                    if (!conhecida) {
                        avisar(new StringBuilder("flag desconhecida '--").append(parts[0])
                                .append("' ignorada (nunca e lida por ByzantineSweep)"));
                    }
                }
            } else {
                avisar(new StringBuilder("argumento '").append(arg).append("' ignorado (use --chave=valor)"));
            }
        }
        return map;
    }

    /** AVISO no stderr; so e chamado com flag desconhecida, nunca no caminho padrao. Sem '+'. */
    private static void avisar(StringBuilder motivo) {
        StringBuilder sb = new StringBuilder("AVISO: ").append(motivo).append(". Flags conhecidas:");
        for (String k : KNOWN) {
            sb.append(" --").append(k);
        }
        sb.append('\n');
        System.err.print(sb.toString());
    }

    /**
     * Valor de {@code --n-semantics}; mesma regra de {@code MVPComparison.isNodesMode} (copia, para NAO carregar a
     * classe MVPComparison a partir do sweep: carregar classe consome hash de identidade).
     */
    static boolean isNodesMode(String valor) {
        if (valor == null || valor.equals("legacy")) {
            return false;
        }
        if (valor.equals("nodes")) {
            return true;
        }
        throw new IllegalArgumentException("--" + NetworkSizing.FLAG + "='" + valor + "' invalido. Validos: legacy, "
                + "nodes (sem maiusculas nem espacos). Ausente = legacy.");
    }

    /** Flags que o programa le (as demais sao ignoradas com aviso no stderr). Array: sem colecao hash nem classe nova. */
    private static final String[] KNOWN = {"validators", "max-byz", "trials", "duration", "type", "attack",
            "enable-tx", "tx-interval", "out", "seed", "turn-length", "epoch-length", "max-pdv-pct",
            NetworkSizing.FLAG};

    /** Flags aceitas, como conjunto (para testes e mensagens). */
    static java.util.Set<String> knownFlags() {
        return new java.util.TreeSet<>(java.util.Arrays.asList(KNOWN));
    }

    /**
     * {miners, stakeholders} da rede POS/POSA. Legacy: as expressoes ORIGINAIS
     * {@code max(1, round(0,3 n))} e {@code max(1, n)} (a rede tem MAIS que n nos; mesmas de
     * {@code NetworkSizing.sweepLegacy}, que um teste confere). Nodes: {@link NetworkSizing#nodes}.
     */
    static int[] sweepSizing(boolean nodesMode, int n) {
        if (!nodesMode) {
            return new int[] {(int) Math.max(1, Math.round(n * 0.3)), Math.max(1, n)};
        }
        NetworkSizing.Decomposition d = NetworkSizing.nodes(n);
        return new int[] {d.miners(), d.stakeholders()};
    }

    /**
     * Linha (sem '#') com o tamanho real da rede do tipo {@code type}. POS: votam todos os nos, S exclui os
     * mineradores. POSA (Parlia): so os mineradores sao validadores. Outros tipos: n e o numero de nos.
     * {@code sizing} = {miners, stakeholders} (POS/POSA) ou {@code null}. StringBuilder, sem concatenacao nova.
     */
    static String describeSizing(String type, boolean nodesMode, int n, int[] sizing) {
        String rot = nodesMode ? "nodes" : "legacy";
        StringBuilder sb = new StringBuilder("type=").append(type);
        if (sizing != null) {
            int miners = sizing[0];
            int stakeholders = sizing[1];
            // regra do numero de bizantinos (herdada, NAO alterada): POS sorteia ceil(stakeholders*f/n) entre os
            // stakeholders (em 'nodes' stakeholders < n, entao o numero efetivo e ~0,7 f); POSA sorteia f ids em
            // 0..n-1 (so os mineradores validam). Ver README da N1: pergunta ao humano.
            sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                    .append(miners + stakeholders).append(" miners=").append(miners).append(" stakeholders=")
                    .append(stakeholders).append(" casper_voters=").append(miners + stakeholders)
                    .append(" casper_S=").append(stakeholders).append(" parlia_validators=").append(miners);
            return sb.append(type.equals("POS") ? " voters=casper_voters byz_count=ceil(stakeholders*f/n)_among_stakeholders"
                    : " voters=parlia_validators byz_count=f_ids_drawn_from_0..n-1").toString();
        }
        return sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=").append(n)
                .append(" (n ja e o numero de nos nas duas semanticas)").toString();
    }
}
