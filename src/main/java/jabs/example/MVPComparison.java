package jabs.example;

import jabs.config.ByzantineConfig;
import jabs.config.NetworkSizing;
import jabs.ledgerdata.DoubleSpendTracker;
import jabs.metrics.SimulationMetrics;
import jabs.scenario.AbstractScenario;
import jabs.scenario.DAGsperNetworkScenario;
import jabs.scenario.ArqReddVotingScenario;
import jabs.scenario.HybridNetworkScenario;
import jabs.scenario.PBFTLANScenario;
import jabs.log.BlockFinalizationLogger;
import jabs.log.EnhancedBlockFinalizationLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * MVP Example: Arq-REDD+ vs pBFT with REAL Simulation
 * 
 * This example demonstrates:
 * 1. Running REAL blockchain consensus simulations
 * 2. Injecting 33% Byzantine validators
 * 3. Collecting all 5 metrics in real-time
 * 4. Comparing Byzantine fault tolerance
 * 
 * Date: January 23, 2026
 * Status: Ready for execution with REAL scenarios
 */
public class MVPComparison {

    public static void main(String[] args) throws IOException {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║   MVP: Arq-REDD+ vs others - Multiple Trials Harness   ║");
        System.out.println("║   Date: 2026-03-05                                     ║");
        System.out.println("╚════════════════════════════════════════════════════════╝\n");

        // CLI args:
        // --validators=200 --protocols=EARTH,MCO2,TREE --trials=10 --duration=600
        // --durations=EARTH:600,MCO2:300,TREE:450
        // --seed=12345 --output=output/mvp-validation --csv-out=output/mvp-results.csv
        java.util.Map<String, String> cli = parseArgs(args);
        // N1: semantica do n. Ausente = legacy (comportamento anterior); valor invalido falha alto (a unica validacao nova de flag).
        // Booleano, nao enum: ver "Rodada 2" no README da N1 (o modo legacy nao pode carregar classes novas).
        boolean nodesMode = isNodesMode(cli.get(NetworkSizing.FLAG));
        // ADR-0012 (M1a-1): registro de carga do enlace. DESLIGADO por padrao; ligado sem t0 explicito FALHA ALTO aqui,
        // antes de qualquer simulacao. Caminho desligado: so duas leituras de mapa, sem carregar classe nova.
        configureLoadRecord(cli.get("load-record"), cli.get("load-record-t0"));
        java.util.List<Integer> validatorsList = parseIntList(cli.getOrDefault("validators", "200"));
        java.util.List<String> protocolList = parseStringList(cli.getOrDefault("protocols", "EARTH"));
        int trials = Integer.parseInt(cli.getOrDefault("trials", "5"));
        double defaultDuration = Double.parseDouble(cli.getOrDefault("duration", "600"));
        long baseSeed = Long.parseLong(cli.getOrDefault("seed", "12345"));
        String outputRoot = cli.getOrDefault("output", "output/mvp-validation");
        String csvOut = cli.getOrDefault("csv-out", outputRoot + "/mvp-results.csv");
        boolean enableTransactions = Boolean.parseBoolean(cli.getOrDefault("enable-tx", "true"));
        double txInterval = Double.parseDouble(cli.getOrDefault("tx-interval", "5.0"));
        
        // Parse per-protocol durations: EARTH:600,MCO2:300,TREE:450
        java.util.Map<String, Double> protocolDurations = parseProtocolDurations(
            cli.getOrDefault("durations", ""), protocolList, defaultDuration);
        if (loadRecordOn) {
            for (String protocol : protocolList) {
                if (!(loadRecordT0 < protocolDurations.get(protocol))) {
                    throw new IllegalArgumentException("--load-record-t0=" + loadRecordT0 + " is not inside the run of "
                            + protocol + " (duration " + protocolDurations.get(protocol) + " s): empty window");
                }
            }
        }
        
        // Decomposicao de cada (protocolo, n) calculada ANTES de simular: n invalido (nodes, n < 2) falha aqui.
        // So StringBuilder e classes ja carregadas pela baseline (sem record/enum/indy novos): zero hash de
        // identidade a mais na thread principal no modo legacy.
        java.util.List<String> sizingLines = new java.util.ArrayList<>();
        for (int validators : validatorsList) {
            for (String protocol : protocolList) {
                sizingLines.add(describeSizing(protocol, nodesMode, validators));
            }
        }

        Files.createDirectories(Paths.get(outputRoot));

        System.out.println("Args: validators=" + validatorsList +
                ", protocols=" + protocolList +
                ", trials=" + trials +
                ", default duration=" + defaultDuration + "s, seed=" + baseSeed +
                ", out=" + outputRoot +
                ", csv-out=" + csvOut);
        System.out.println("Protocol-specific durations: " + protocolDurations);
        System.out.println(new StringBuilder("n-semantics=").append(rotulo(nodesMode)).toString());
        for (String line : sizingLines) {
            System.out.println(new StringBuilder("Network sizing: ").append(line).toString());
        }

        // Initialize CSV file with header
        initializeCSV(csvOut, nodesMode, sizingLines);

        for (int validators : validatorsList) {
            System.out.println("\n╔═══════════════════════════════════════╗");
            System.out.println("║ Testing with " + validators + " validators ║");
            System.out.println("╚═══════════════════════════════════════╝");

            for (String protocol : protocolList) {
                System.out.println("\n  ■ Protocol: " + protocol);
                
                // Create accumulator for this protocol/validators combination
                MetricsAccumulator acc = new MetricsAccumulator();
                double protocolDuration = protocolDurations.get(protocol);
                
                // Run all trials for this protocol/validators combination
                for (int trial = 0; trial < trials; trial++) {
                    long runSeed = baseSeed + validators * 1000L + trial;
                    System.out.println("    Trial " + (trial + 1) + "/" + trials + " (seed=" + runSeed + ")");

                    try {
                        SimulationMetrics metrics = runProtocol(protocol, validators, runSeed, protocolDuration, 
                            outputRoot + "/" + protocol.toLowerCase() + "-" + validators + "v-t" + trial + ".csv",
                            enableTransactions, txInterval, nodesMode);
                        
                        // Accumulate metrics
                        acc.addTrial(
                            metrics.getAverageBlockFinalizationTime(),
                            metrics.getAverageTrafficPerBlock(),
                            metrics.getAverageTransactionConfirmationLatency()
                        );
                        
                        System.out.println("      ✓ completed");
                    } catch (Exception e) {
                        System.out.println("      ✗ failed: " + e.getMessage());
                    }
                }
                
                // After all trials for this protocol/validators, write to CSV
                System.out.println("    → Writing results for " + protocol + " (n=" + validators + ") to CSV");
                writeSingleResultToCSV(csvOut, protocol, validators, trials, acc);
            }
        }

        // Write CSV results
        System.out.println("\n╔═══════════════════════════════════╗");
        System.out.println("║    All simulations completed!      ║");
        System.out.println("╚═══════════════════════════════════╝\n");
        
        System.out.println("✓ Results written to: " + csvOut);
    }

    /** Flags que o programa le (as demais sao ignoradas com aviso no stderr). Array: sem colecao hash nem classe nova. */
    private static final String[] KNOWN = {"validators", "protocols", "trials", "duration", "durations", "seed",
            "output", "csv-out", "enable-tx", "tx-interval", NetworkSizing.FLAG, "load-record", "load-record-t0"};

    /** ADR-0012: registro de carga do enlace ligado? Primitivos: o caminho desligado nao carrega classe nova. */
    private static boolean loadRecordOn = false;
    private static double loadRecordT0 = 0.0;

    /**
     * Interpreta {@code --load-record} e {@code --load-record-t0}. Ambos ausentes = desligado (nada carregado).
     * Qualquer um presente delega a {@code LinkLoadRecorder.parseWindowStart}, que FALHA ALTO se o registro for
     * ligado sem t0, se t0 vier sem o registro ligado ou se algum valor for invalido.
     */
    static void configureLoadRecord(String recordFlag, String t0Flag) {
        loadRecordOn = false;
        if (recordFlag == null && t0Flag == null) {
            return;
        }
        Double t0 = jabs.log.LinkLoadRecorder.parseWindowStart(recordFlag, t0Flag);
        if (t0 != null) {
            loadRecordOn = true;
            loadRecordT0 = t0;
        }
    }

    /** Liga o registro de carga neste cenario, se ligado por linha de comando. Desligado: retorna sem fazer nada. */
    private static void attachLoadRecorder(AbstractScenario scenario, String protocol, long seed, String outputPath) {
        if (!loadRecordOn) {
            return;
        }
        String prefix = outputPath.endsWith(".csv") ? outputPath.substring(0, outputPath.length() - 4) : outputPath;
        scenario.AddNewLogger(new jabs.log.LinkLoadRecorder(prefix, protocol, seed, loadRecordT0));
    }

    /** Flags aceitas, como conjunto (so para testes; o fluxo principal usa {@link #KNOWN}). */
    static java.util.Set<String> knownFlags() {
        return new java.util.TreeSet<>(java.util.Arrays.asList(KNOWN));
    }

    /**
     * Leitura de {@code --chave=valor} IDENTICA a da baseline 325f4e3 (N1, rodada 3): argumento sem '=' ou sem o
     * prefixo '--' e IGNORADO; chave desconhecida ENTRA no mapa (nunca e lida); chave repetida: a ULTIMA ocorrencia
     * vence. Assim invocacoes do repositorio que terminavam antes (ex.: {@code validate-metrics.sh/.ps1} passam
     * {@code --byzantine=0,33}, flag que nunca foi lida) continuam terminando.
     *
     * <p>Unica adicao: AVISO no stderr (nunca no stdout nem no CSV) para chave desconhecida e para argumento fora
     * de {@code --chave=valor}, sem alterar o fluxo. Montado com {@code StringBuilder} e {@code System.err.print}
     * (sem concatenacao '+' nem classe nova) e so no ramo do aviso: o caminho SEM flag desconhecida executa as
     * mesmas operacoes da baseline mais um laco sobre um {@code String[]} e consome o mesmo numero de
     * {@code identityHashCode} (15 pre-simulacao; ver o README da N1, rodadas 2 e 3). A unica validacao nova de
     * flag e a de {@code --n-semantics} ({@link #isNodesMode}).
     *
     * <p>{@code ByzantineSweep} tem copia desta rotina (para nao carregar esta classe a partir do sweep); um teste
     * confere que as duas se comportam igual.
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
                                .append("' ignorada (nunca e lida por MVPComparison)"));
                    }
                }
            } else {
                avisar(new StringBuilder("argumento '").append(arg).append("' ignorado (use --chave=valor)"));
            }
        }
        return map;
    }

    /** AVISO no stderr; so e chamado com flag desconhecida, nunca no caminho padrao. Sem '+' (ver parseArgs). */
    private static void avisar(StringBuilder motivo) {
        StringBuilder sb = new StringBuilder("AVISO: ").append(motivo).append(". Flags conhecidas:");
        for (String k : KNOWN) {
            sb.append(" --").append(k);
        }
        sb.append('\n');
        System.err.print(sb.toString());
    }

    /**
     * Valor de {@code --n-semantics}: ausente = legacy ({@code false}); {@code legacy} = {@code false};
     * {@code nodes} = {@code true}. Outro valor (vazio, maiusculas, espacos) FALHA ALTO. Equivalente a
     * {@link NetworkSizing.Semantics#fromFlag}, sem carregar o enum no modo legacy.
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

    private static String rotulo(boolean nodesMode) {
        return nodesMode ? "nodes" : "legacy";
    }

    /**
     * {miners, stakeholders} que o MCO2/TREE/AMBIFY constroem para {@code n}. Legacy: as expressoes ORIGINAIS
     * {@code (int)(n*0.3)} e {@code (int)(n*0.7)} (NAO "corrigir"; mesmas de {@code NetworkSizing.mvpLegacy}, que
     * um teste confere). Nodes: {@link NetworkSizing#nodes} (so la a classe e carregada).
     */
    static int[] mvpSizing(boolean nodesMode, int n) {
        if (!nodesMode) {
            return new int[] {(int) (n * 0.3), (int) (n * 0.7)};
        }
        NetworkSizing.Decomposition d = NetworkSizing.nodes(n);
        return new int[] {d.miners(), d.stakeholders()};
    }

    /**
     * Uma linha (sem '#') descrevendo o tamanho REAL da rede de {@code protocol} para o {@code n} pedido: nos
     * construidos, mineradores, stakeholders e quem vota. Protocolos cujo {@code n} ja e o numero de nos
     * (ARQ, EARTH, OFFSET, DAGSPER) saem iguais nas duas semanticas. So le a formula; nao constroi rede
     * (a construcao e provada em {@code NetworkSizingTest}). Montada com {@code StringBuilder} (nao ha
     * concatenacao por invokedynamic): uma concatenacao nova, no primeiro uso, consome hash de identidade.
     */
    static String describeSizing(String protocol, boolean nodesMode, int n) {
        String rot = rotulo(nodesMode);
        StringBuilder sb = new StringBuilder("protocol=").append(protocol);
        switch (protocol.toUpperCase()) {
            case "MCO2":
            case "TREE": {
                int[] d = mvpSizing(nodesMode, n);
                sb.append(' ');
                appendDecomposition(sb, rot, n, d[0], d[1]);
                return sb.append(" voters=casper_voters").toString();
            }
            case "AMBIFY": {
                int[] d = mvpSizing(nodesMode, n);
                sb.append(' ');
                appendDecomposition(sb, rot, n, d[0], d[1]);
                return sb.append(" voters=parlia_validators").toString();
            }
            case "ARQ":
                return sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                        .append(n).append(" validators=").append(n).toString();
            case "OFFSET":
            case "EARTH":
                return sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                        .append(n).append(" miners=").append(n).append(" stakeholders=0").toString();
            case "DAGSPER": {
                int miners = Math.max(1, (int) Math.round(n * 0.3));
                return sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                        .append(n).append(" miners=").append(miners).append(" stakeholders=").append(n - miners)
                        .append(" voters=all").toString();
            }
            default:
                // protocolo desconhecido: runProtocol o recusa por trial, como antes (nao muda o fluxo legado)
                return sb.append(" sizing=desconhecido (protocolo nao reconhecido)").toString();
        }
    }

    /** Mesmo texto de {@code NetworkSizing.Decomposition.describe()} (um teste confere), sem record nem enum. */
    private static void appendDecomposition(StringBuilder sb, String rot, int n, int miners, int stakeholders) {
        sb.append("n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                .append(miners + stakeholders).append(" miners=").append(miners).append(" stakeholders=")
                .append(stakeholders).append(" casper_voters=").append(miners + stakeholders).append(" casper_S=")
                .append(stakeholders).append(" parlia_validators=").append(miners);
    }

    /**
     * Parse protocol-specific durations from format: EARTH:600,MCO2:300,TREE:450
     * Initialize all protocols with default duration, then override with specified values
     */
    private static java.util.Map<String, Double> parseProtocolDurations(String durationStr, 
            java.util.List<String> protocolList, double defaultDuration) {
        java.util.Map<String, Double> map = new java.util.HashMap<>();
        
        // Initialize all protocols with default duration
        for (String protocol : protocolList) {
            map.put(protocol, defaultDuration);
        }
        
        // Override with specified durations
        if (durationStr != null && !durationStr.isEmpty()) {
            for (String pair : durationStr.split(",")) {
                String[] parts = pair.trim().split(":");
                if (parts.length == 2) {
                    try {
                        String protocol = parts[0].trim().toUpperCase();
                        double duration = Double.parseDouble(parts[1].trim());
                        map.put(protocol, duration);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid duration format: " + pair);
                    }
                }
            }
        }
        return map;
    }

    /**
     * Initialize CSV file with header
     */
    private static void initializeCSV(String csvPath, boolean nodesMode,
            java.util.List<String> sizingLines) throws IOException {
        try (java.io.BufferedWriter bw = Files.newBufferedWriter(java.nio.file.Paths.get(csvPath))) {
            // Modo legacy: o CSV e BYTE A BYTE o da baseline 325f4e3 (so o cabecalho, sem linhas '#'); o
            // dimensionamento do modo legacy ja sai no stdout ("n-semantics=", "Network sizing:").
            // Modo nodes: linhas '#' antes do cabecalho (o n da coluna 'n' e o PEDIDO; os nos construidos e a
            // decomposicao estao aqui). Leitores externos do modo nodes precisam pular linhas '#'.
            if (nodesMode) {
                bw.write(new StringBuilder("# n_semantics=").append(rotulo(nodesMode))
                        .append(" (coluna n = valor pedido na linha de comando)\n").toString());
                for (String line : sizingLines) {
                    bw.write(new StringBuilder("# ").append(line).append('\n').toString());
                }
            }
            bw.write("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std\n");
        }
    }

    /**
     * Write a single result line to CSV (append mode)
     */
    private static void writeSingleResultToCSV(String csvPath, String protocol, int n, int trials,
            MetricsAccumulator acc) throws IOException {
        try (java.io.BufferedWriter bw = Files.newBufferedWriter(
                java.nio.file.Paths.get(csvPath),
                java.nio.file.StandardOpenOption.APPEND)) {
            
            bw.write(String.format(java.util.Locale.ROOT,
                    "%s;%d;%d;%.6f;%.6f;%.6f;%.6f;%.6f;%.6f\n",
                    protocol, n, trials,
                    acc.getAvgTb(), acc.getStdTb(),
                    acc.getAvgCb(), acc.getStdCb(),
                    acc.getAvgTt(), acc.getStdTt()
            ));
        }
    }

    /**
     * Run a single protocol simulation
     */
    private static SimulationMetrics runProtocol(String protocol, int validators, long seed, 
            double duration, String outputPath, boolean enableTx, double txInterval,
            boolean nodesMode) throws Exception {
        
        switch (protocol.toUpperCase()) {
            case "EARTH":
                return runEarthDollarScenario(validators, seed, duration, outputPath, enableTx, txInterval);
            case "MCO2":
                return runMCO2Scenario(validators, seed, duration, outputPath, mvpSizing(nodesMode, validators), nodesMode);
            case "TREE":
                return runTreeCycleScenario(validators, seed, duration, outputPath, mvpSizing(nodesMode, validators), nodesMode);
            case "AMBIFY":
                return runAmbifyScenario(validators, seed, duration, outputPath, mvpSizing(nodesMode, validators), nodesMode);
            case "OFFSET":
                return runOffsetBitcoinScenario(validators, seed, duration, outputPath, enableTx, txInterval);
            case "ARQ":
                return runArqREDDScenario(validators, duration, seed, outputPath);
            case "DAGSPER":
                return runDAGsperScenario(validators, seed, duration, outputPath, enableTx, txInterval);
            default:
                throw new IllegalArgumentException("Unknown protocol: " + protocol);
        }
    }

    /**
     * Helper class to accumulate metrics across trials
     */
    private static class MetricsAccumulator {
        private double sumTb = 0.0, sumTbSq = 0.0;
        private double sumCb = 0.0, sumCbSq = 0.0;
        private double sumTt = 0.0, sumTtSq = 0.0;
        private int count = 0;

        void addTrial(double tb, double cb, double tt) {
            sumTb += tb;
            sumTbSq += tb * tb;
            sumCb += cb;
            sumCbSq += cb * cb;
            sumTt += tt;
            sumTtSq += tt * tt;
            count++;
        }

        double getAvgTb() { return count > 0 ? sumTb / count : 0.0; }
        double getAvgCb() { return count > 0 ? sumCb / count : 0.0; }
        double getAvgTt() { return count > 0 ? sumTt / count : 0.0; }

        double getStdTb() {
            if (count <= 1) return 0.0;
            double var = (sumTbSq - (sumTb * sumTb) / count) / (count - 1);
            return Math.sqrt(Math.max(0, var));
        }

        double getStdCb() {
            if (count <= 1) return 0.0;
            double var = (sumCbSq - (sumCb * sumCb) / count) / (count - 1);
            return Math.sqrt(Math.max(0, var));
        }

        double getStdTt() {
            if (count <= 1) return 0.0;
            double var = (sumTtSq - (sumTt * sumTt) / count) / (count - 1);
            return Math.sqrt(Math.max(0, var));
        }
    }

    private static SimulationMetrics runArqREDDScenario(int totalValidators, double duration,
            long seed, String outputPath) {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(totalValidators);
        metrics.setByzantineValidators(0);
        metrics.setConsensusType(SimulationMetrics.ConsensusType.VOTING);

        try {
            HybridNetworkScenario scenario = new HybridNetworkScenario(
                    "Arq-REDD+ Hybrid (Honest Nodes)",
                    seed,
                    totalValidators,
                    duration,
                    30.0,
                    new double[] { 20.0, 60.0, 20.0 },
                    0.0,
                    ByzantineConfig.AttackType.NONE);
            scenario.addMetricsLogger(outputPath);
            attachLoadRecorder(scenario, "ARQ", seed, outputPath);
            System.out.println(
                    "Running Arq-REDD+ HYBRID (" + totalValidators + " honest nodes) ...");
            scenario.run();
            metrics = scenario.getMetrics();
            printScenarioFinilizedMetrics(metrics);
        } catch (Exception e) {
            System.out.println("⚠️  Arq-REDD+ simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
        return metrics;
    }

    /**
     * DAGsper (ADR-0006, tarefa 5). {@code numNodes} e o total de nos, todos participantes do DAGsper (o mesmo
     * que vota); 30% sao mineradores, como no MCO2. Exige numNodes >= {@link DAGsperNetworkScenario#MIN_NODES}.
     * Falha de cenario NAO e engolida: propaga para o chamador (que a imprime como trial falho).
     */
    private static SimulationMetrics runDAGsperScenario(int numNodes, long seed, double duration,
            String outputPath, boolean enableTx, double txInterval) throws IOException {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(numNodes);
        // Quorum 2f+1 (f = floor((n-1)/3)): tolerancia f < n/3, a do tipo VOTING. ConsensusType.DAG usa um limiar
        // "aproximado" (n/10+1) que nao descreve este protocolo.
        metrics.setConsensusType(SimulationMetrics.ConsensusType.VOTING);
        int miners = Math.max(1, (int) Math.round(numNodes * 0.3));
        DAGsperNetworkScenario scenario = new DAGsperNetworkScenario(
                "DAGsper - " + numNodes + " nodes",
                seed,
                duration,
                15.0,
                14,
                miners,
                numNodes - miners,
                0,
                ByzantineConfig.AttackType.NONE,
                enableTx,
                txInterval);
        scenario.addMetricsLogger(outputPath, metrics);
        attachLoadRecorder(scenario, "DAGSPER", seed, outputPath);
        System.out.println("Running DAGsper scenario (" + numNodes + " nodes, " + miners + " miners, seed=" + seed + ") ...");
        scenario.run();
        metrics = scenario.getMetrics();
        printScenarioFinilizedMetrics(metrics);
        return metrics;
    }

    private static SimulationMetrics runMCO2Scenario(int numStakeholders, long seed, double duration,
            String outputPath, int[] sizing, boolean nodesMode) {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(numStakeholders);
        metrics.setConsensusType(SimulationMetrics.ConsensusType.POS);
        try {
            jabs.scenario.CasperEthereumNetworkScenario scenario = new jabs.scenario.CasperEthereumNetworkScenario(
                    "MCO2 - " + numStakeholders + " stakeholders",
                    (int) seed,
                    duration,
                    15.0,
                    14,
                    sizing[0],
                    sizing[1]);
            scenario.addMetricsLogger(outputPath, metrics);
            attachLoadRecorder(scenario, "MCO2", seed, outputPath);
            System.out.println("Running MCO2 scenario (" + numStakeholders + " nodes) ...");
            scenario.run();
            printBuiltNetwork(scenario, sizing, nodesMode, numStakeholders);
            metrics = scenario.getMetrics();
            printScenarioFinilizedMetrics(metrics);
        } catch (Exception e) {
            System.out.println("⚠️  MCO2 simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
        return metrics;
    }

    private static SimulationMetrics runTreeCycleScenario(int numStakeholders, long seed, double duration,
            String outputPath, int[] sizing, boolean nodesMode) {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(numStakeholders);
        metrics.setConsensusType(SimulationMetrics.ConsensusType.POS);
        try {
            jabs.scenario.CasperEthereumNetworkScenario scenario = new jabs.scenario.CasperEthereumNetworkScenario(
                    "TreeCycle - " + numStakeholders + " stakeholders",
                    (int) seed,
                    duration,
                    60.0,
                    14,
                    sizing[0],
                    sizing[1]);
            scenario.addMetricsLogger(outputPath, metrics);
            scenario.AddNewLogger(new BlockFinalizationLogger(Paths.get(outputPath.replace(".csv", "-basic-log.csv"))));
            attachLoadRecorder(scenario, "TREE", seed, outputPath);
            System.out.println("Running TreeCycle scenario (" + numStakeholders + " nodes) ...");
            scenario.run();
            printBuiltNetwork(scenario, sizing, nodesMode, numStakeholders);
            metrics = scenario.getMetrics();
            printScenarioFinilizedMetrics(metrics);
        } catch (Exception e) {
            System.out.println("⚠️  TreeCycle simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
        return metrics;
    }

    private static SimulationMetrics runAmbifyScenario(int numStakeholders, long seed, double duration,
            String outputPath, int[] sizing, boolean nodesMode) {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(numStakeholders);
        metrics.setConsensusType(SimulationMetrics.ConsensusType.POS);
        try {
            jabs.scenario.ParliaBSCNetworkScenario scenario = new jabs.scenario.ParliaBSCNetworkScenario(
                    "Ambify - " + numStakeholders + " stakeholders",
                    (int) seed,
                    duration,
                    3.0,
                    3,
                    21,
                    sizing[0],
                    sizing[1]);
            scenario.addMetricsLogger(outputPath, metrics);
            attachLoadRecorder(scenario, "AMBIFY", seed, outputPath);
            System.out.println("Running Ambify (Parlia) scenario (" + numStakeholders + " nodes) ...");
            scenario.run();
            printBuiltNetwork(scenario, sizing, nodesMode, numStakeholders);
            metrics = scenario.getMetrics();
            printScenarioFinilizedMetrics(metrics);
        } catch (Exception e) {
            System.out.println("⚠️  Ambify simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
        return metrics;
    }

    private static SimulationMetrics runOffsetBitcoinScenario(int numStakeholders, long seed, double duration,
            String outputPath, boolean enableTx, double txInterval) {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(numStakeholders);
        metrics.setConsensusType(SimulationMetrics.ConsensusType.POW);
        try {
            jabs.scenario.BitcoinGlobalNetworkScenario scenario = new jabs.scenario.BitcoinGlobalNetworkScenario(
                    "Offset Bitcoin - " + numStakeholders + " stakeholders",
                    seed,
                    (long) duration,
                    600.0,
                    numStakeholders,
                    0,
                    14,
                    enableTx,          // enable transaction generation
                    txInterval);       // average interval between txs
            scenario.addMetricsLogger(outputPath, metrics);
            attachLoadRecorder(scenario, "OFFSET", seed, outputPath);
            System.out.println("Running Offset Bitcoin scenario (" + numStakeholders + " nodes) ...");
            scenario.run();
            metrics = scenario.getMetrics();
            printScenarioFinilizedMetrics(metrics);
        } catch (Exception e) {
            System.out.println("⚠️  Offset Bitcoin simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
        return metrics;
    }

    private static SimulationMetrics runEarthDollarScenario(int numStakeholders, long seed, double duration,
            String outputPath, boolean enableTx, double txInterval) {
        SimulationMetrics metrics = new SimulationMetrics();
        metrics.setTotalValidators(numStakeholders);
        metrics.setConsensusType(SimulationMetrics.ConsensusType.POS);
        try {
            jabs.scenario.BitcoinGlobalNetworkScenario scenario = new jabs.scenario.BitcoinGlobalNetworkScenario(
                    "Earth Dollar - " + numStakeholders + " stakeholders",
                    seed,
                    (long) duration,
                    450.0,
                    numStakeholders,
                    0,
                    6,
                    enableTx,
                    txInterval);
            scenario.addMetricsLogger(outputPath, metrics);
            attachLoadRecorder(scenario, "EARTH", seed, outputPath);
            System.out.println("Running Earth Dollar scenario (" + numStakeholders + " nodes) ...");
            scenario.run();
            metrics = scenario.getMetrics();
            printScenarioFinilizedMetrics(metrics);
        } catch (Exception e) {
            System.out.println("⚠️  Earth Dollar simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
        return metrics;
    }

    /**
     * Nos REALMENTE construidos (medido em getAllNodes()), ao lado do que a decomposicao previa. StringBuilder, sem
     * concatenacao nova: roda entre trials e nao pode consumir hash de identidade a mais que a baseline.
     */
    private static void printBuiltNetwork(AbstractScenario scenario, int[] sizing, boolean nodesMode, int requestedN) {
        int built = scenario.getNetwork().getAllNodes().size();
        int expected = sizing[0] + sizing[1];
        StringBuilder sb = new StringBuilder("  Network built: nodes=").append(built).append(" (expected ")
                .append(expected).append("; n_requested=").append(requestedN).append(", n_semantics=")
                .append(rotulo(nodesMode)).append(')');
        if (built != expected) {
            sb.append("  *** MISMATCH ***");
        }
        System.out.println(sb.toString());
    }

    /**
     * Pad a string to a specific width (Java-compatible)
     */
    private static String padEnd(String str, int width) {
        if (str.length() >= width) {
            return str.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < width) {
            sb.append(" ");
        }
        return sb.toString();
    }

    private static void printScenarioFinilizedMetrics(SimulationMetrics metrics) {
        System.out.println(String.format("  Blocks: %d generated, %d finalized",
                metrics.getTotalBlocksGenerated(), metrics.getBlockCount()));
        System.out.println(String.format("  Double-spend Pdv: %.3f%% (%d/%d attempts successful)",
                metrics.getDoubleSpendSuccessProbability(),
                metrics.getDoubleSpendSuccesses(),
                metrics.getDoubleSpendAttempts()));
    }

    /**
     * Print formatted metrics report and save to file
     */
    private static void printMetricsReport(String protocolName, SimulationMetrics metrics, String reportFile) {
        StringBuilder sb = new StringBuilder();
        
        String line1 = "╔" + "═".repeat(58) + "╗";
        String line2 = "║ " + protocolName.toUpperCase() + " METRICS REPORT" +
                " ".repeat(Math.max(0, 43 - protocolName.length())) + "║";
        String line3 = "╠" + "═".repeat(58) + "╣";
        String lineEnd = "╚" + "═".repeat(58) + "╝";
        
        sb.append(line1).append("\n");
        sb.append(line2).append("\n");
        sb.append(line3).append("\n");
        
        System.out.println(line1);
        System.out.println(line2);
        System.out.println(line3);

        if (metrics.getBlockCount() == 0) {
            String noBlocksMsg = padEnd("║ No blocks generated in simulation", 59) + "║";
            sb.append(noBlocksMsg).append("\n");
            sb.append(lineEnd).append("\n");
            System.out.println(noBlocksMsg);
            System.out.println(lineEnd);
            saveReportToFile(reportFile, sb.toString());
            return;
        }

        String[] lines = new String[15];
        lines[0] = padEnd("║ Metric 1 - Tb (Block Finalization Time):", 59) + "║";
        lines[1] = padEnd(String.format("║   Average: %.3f seconds/block", metrics.getAverageBlockFinalizationTime()), 59) + "║";
        lines[2] = padEnd(String.format("║   StdDev: %.3f seconds", metrics.getStandardDeviationFinalizationTime()), 59) + "║";
        lines[3] = padEnd(String.format("║   p95: %.3f seconds", metrics.getPercentileFinalizationTime(95)), 59) + "║";
        
        lines[4] = padEnd("║ Metric 2 - Cb (Network Traffic):", 59) + "║";
        lines[5] = padEnd(String.format("║   Average: %.6f MB/block", metrics.getAverageTrafficPerBlock()), 59) + "║";
        lines[6] = padEnd(String.format("║   StdDev: %.6f MB", metrics.getStandardDeviationTraffic()), 59) + "║";
        lines[7] = padEnd(String.format("║   p95: %.6f MB", metrics.getPercentileTraffic(95)), 59) + "║";
        
        lines[8] = padEnd("║ Metric 3 - Tt (Transaction Confirmation Latency):", 59) + "║";
        lines[9] = padEnd(String.format("║   Average: %.3f seconds/tx", metrics.getAverageTransactionConfirmationLatency()), 59) + "║";
        lines[10] = padEnd(String.format("║   StdDev: %.3f seconds", metrics.getStandardDeviationConfirmationLatency()), 59) + "║";
        lines[11] = padEnd(String.format("║   p95: %.3f seconds", metrics.getPercentileConfirmationLatency(95)), 59) + "║";
        
        lines[12] = padEnd("║ Metric 4 - BFT (Byzantine Fault Tolerance):", 59) + "║";
        double attackThreshold = metrics.getByzantineFaultTolerance();
        lines[13] = padEnd(String.format("║   Attack threshold: %.2f%% (%d nodes)", attackThreshold,
                metrics.getBFTAttackThreshold()), 59) + "║";
        
        lines[14] = padEnd("║ Metric 5 - Pdv (Double-spending):", 59) + "║";
        
        for (String l : lines) {
            System.out.println(l);
            sb.append(l).append("\n");
        }
        
        String pdvLine = padEnd(String.format("║   Probability: %.3f%%", metrics.getDoubleSpendSuccessProbability()), 59) + "║";
        System.out.println(pdvLine);
        sb.append(pdvLine).append("\n");
        
        System.out.println(lineEnd);
        sb.append(lineEnd).append("\n");
        
        saveReportToFile(reportFile, sb.toString());
    }
    
    /**
     * Save metrics report to file (append mode)
     */
    private static void saveReportToFile(String filePath, String content) {
        try {
            Files.write(Paths.get(filePath), content.getBytes(), 
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("  ⚠️  Failed to save report to " + filePath + ": " + e.getMessage());
        }
    }

    private static java.util.List<Integer> parseIntList(String csv) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(Integer.parseInt(s.trim()));
        }
        return list;
    }

    private static java.util.List<Double> parseDoubleList(String csv) {
        java.util.List<Double> list = new java.util.ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(Double.parseDouble(s.trim()));
        }
        return list;
    }

    private static java.util.List<String> parseStringList(String csv) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(s.trim().toUpperCase());
        }
        return list;
    }
}
