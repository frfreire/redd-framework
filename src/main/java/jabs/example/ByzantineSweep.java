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

    /**
     * {@code --trials} e {@code --seed} sao OBRIGATORIOS, sem padrao (ADR-0015 Q26-A, tarefa trials-seed-obrigatorios):
     * os antigos padroes (30 e 12345 aqui; 5 e 12345 no MVPComparison) eram distintos entre os dois {@code main}.
     */
    private static String requiredRunFlag(java.util.Map<String, String> cli, String flag) {
        String value = cli.get(flag);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + flag + " e obrigatorio e nao tem valor padrao (ADR-0015 Q26-A)");
        }
        return value;
    }

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

    /** Cabecalho de colunas de ANTES de D2 (e de hoje para os tipos que nao separam a autoentrega). */
    static final String BASE_HEADER =
            "protocol,n,f,trials,avgPdv,stdPdv,avgFinalizationTime,stdFinalizationTime,avgConfirmationLatency,stdConfirmationLatency,avgBlockCount,stdBlockCount,avgTraffic,stdTraffic,avgEmpiricalBFT_pct,stdEmpiricalBFT_pct,secureCount";

    /** D2 (ADR-0015): colunas PROPRIAS e ADITIVAS da autoentrega, no FIM do cabecalho (MB por bloco, como avgTraffic). */
    static final String SELF_DELIVERY_COLUMNS = ",avgSelfDelivery_MB,stdSelfDelivery_MB";

    /**
     * D2: tipos do sweep cujo consenso SEPARA a autoentrega do C_B (PBFT = VOTING, Casper = POS, Parlia = POSA, Nakamoto = POW:
     * {@code AbstractChainBasedConsensus#separatesSelfDelivery}). DAGSPER ja filtrava a autoentrega: sem coluna, sem linha '#',
     * arquivo identico byte a byte ao da baseline.
     */
    static boolean hasSelfDeliveryColumn(String type) {
        return "VOTING".equals(type) || "POS".equals(type) || "POSA".equals(type) || "POW".equals(type);
    }

    /** Cabecalho de colunas do tipo: o de sempre, mais as 2 colunas de autoentrega no fim quando o tipo a separa. */
    static String header(String type) {
        return hasSelfDeliveryColumn(type) ? new StringBuilder(BASE_HEADER).append(SELF_DELIVERY_COLUMNS).toString() : BASE_HEADER;
    }

    /** Politica de lideranca do PBFT (VOTING; PI-14): {@code --pbft-leader-policy=STABLE|ROTATING}, obrigatoria e sem padrao. */
    static jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy pbftLeaderPolicy(java.util.Map<String, String> cli) {
        String v = cli.get("pbft-leader-policy");
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("tipo VOTING exige --pbft-leader-policy=STABLE|ROTATING (politica de lideranca do PBFT; "
                    + "obrigatoria, sem padrao; STABLE = modo do estudo, PI-14)");
        }
        return jabs.consensus.config.PBFTConsensusConfig.LeaderPolicy.fromFlag(v);
    }

    /** Teto de retencao do PBFT (VOTING): flag {@code --pbft-max-future-horizon} obrigatoria, sem padrao, {@code > 0}. */
    static long pbftMaxFutureHorizon(java.util.Map<String, String> cli) {
        String v = cli.get("pbft-max-future-horizon");
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("tipo VOTING exige --pbft-max-future-horizon=valor (teto de retencao do PBFT; "
                    + "obrigatorio, sem padrao; valor do estudo 1000)");
        }
        return Long.parseLong(v.trim());
    }

        /** Rodada 4 do D2: trial mede a autoentrega se a coluna existe e o valor nao e a sentinela -1; zero medido CONTA. */
    static boolean contaAutoentrega(boolean comAutoentrega, double selfBytes) {
        return comAutoentrega && selfBytes >= 0;
    }

    /**
     * D2 (decisao do humano, 17/11/2026): linhas '#' que declaram a semantica, a semente, a versao do simulador e a
     * granularidade. So para os tipos que separam a autoentrega ({@link #hasSelfDeliveryColumn}); {@code seedRaw} e o valor
     * de {@code --seed} como escrito (obrigatorio, sem padrao), sem parse (nao muda quando uma semente invalida falha).
     * StringBuilder, nao '+': roda antes da simulacao (indy novo consome hash de identidade, ADR-0012);
     * {@code SIMULATOR_VERSION} e constante de compilacao (inlinada).
     */
    static String[] selfDeliveryComments(String seedRaw) {
        return new String[] {
            new StringBuilder("# D2 (ADR-0015, registro de 10/11/2026): avgTraffic e stdTraffic EXCLUEM a autoentrega ")
                    .append("(pacote que o proprio no entrega a si mesmo; nao e C_B: so conta pacote que passou pela rede simulada). ")
                    .append("A autoentrega tem coluna PROPRIA e ADITIVA no fim da linha: avgSelfDelivery_MB e stdSelfDelivery_MB ")
                    .append("(MB por bloco, mesma unidade de avgTraffic; -1 = nao medido, nunca zero). ")
                    .append("Conservacao (medias): avgTraffic antes de D2 = avgTraffic + avgSelfDelivery_MB; o desvio nao e aditivo. ")
                    .append("A media da autoentrega e sobre os trials em que ela foi medida; -1 se nenhum.").toString(),
            new StringBuilder("# Seed=").append(seedRaw)
                    .append(" (semente base; semente do trial = Seed + n*1000 + indice do trial); SimulatorVersion=")
                    .append(jabs.log.LinkLoadRecorder.SIMULATOR_VERSION)
                    .append("; Granularity=por linha (protocolo, n, f): media e desvio amostral entre trials do valor por trial, ")
                    .append("que e a media por bloco no no que finalizou primeiro cada altura; MB (1 MB = 1048576 bytes)").toString()
        };
    }

    /** {@code -1} (nao medido) sai como inteiro {@code -1}, nunca como zero; senao {@code %.6f} (como avgTraffic). */
    static String selfFmt(double mb) {
        return mb < 0 ? "-1" : String.format(Locale.ROOT, "%.6f", mb);
    }

    /**
     * Media e desvio amostral (mesma formula das demais colunas) da autoentrega entre os {@code measured} trials medidos;
     * {-1, -1} se nenhum trial a mediu (nunca zero).
     */
    static double[] selfDeliveryStats(double sum, double sumSq, int measured) {
        if (measured <= 0) {
            return new double[] {-1.0, -1.0};
        }
        int denom = Math.max(1, measured - 1);
        return new double[] {sum / measured, Math.sqrt(Math.max(0, (sumSq - (sum * sum) / measured) / denom))};
    }

    static void run(String[] args) throws IOException {
        java.util.Map<String, String> cli = parseArgs(args);
        // N1: semantica do n. Ausente = legacy (comportamento anterior); valor invalido falha alto (a unica validacao nova de flag).
        // Booleano, nao enum, e sem classe nova no modo legacy: ver "Rodada 2" no README da N1.
        final boolean nodesMode = isNodesMode(cli.get(NetworkSizing.FLAG));
        int n = Integer.parseInt(cli.getOrDefault("validators", "100"));
        int maxByz = Integer.parseInt(cli.getOrDefault("max-byz", String.valueOf(n / 2)));
        int trials = Integer.parseInt(requiredRunFlag(cli, "trials"));
        final String seedRaw = requiredRunFlag(cli, "seed");
        final long baseSeed = Long.parseLong(seedRaw);
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
        if (temDecomposicao && cli.get(NetworkSizing.FLAG) == null) {
            throw new IllegalArgumentException("--" + NetworkSizing.FLAG + " e obrigatoria para o tipo " + type
                    + " (n significa coisas diferentes em legacy e nodes; ADR-0015 Q32 A). Use --" + NetworkSizing.FLAG
                    + "=nodes (estudo) ou --" + NetworkSizing.FLAG + "=legacy (so nao regressao).");
        }
        final int[] sizing = temDecomposicao ? sweepSizing(nodesMode, n) : null;
        final String sizingLine = describeSizing(type, nodesMode, n, sizing);
        System.out.println(new StringBuilder("Network sizing: ").append(sizingLine).toString());
        // BYZ-UNICA: regra de contagem e universo declarados no stdout (o CSV legacy segue byte a byte o de antes)
        System.out.println(new StringBuilder("Byzantine count: f inteiro direto; universo_sorteio=")
                .append(universoRotulo(type)).append(" universo_n=").append(universeSize(type, n, sizing))
                .append(" gerador=ByzantineConfig.selectNested(semente,universo,f) aninhado_entre_f=sim limite_bft=")
                .append(bftApplies(type) ? String.valueOf(ByzantineConfig.bftLimit(universeSize(type, n, sizing))) : "nao_aplicavel")
                .toString());

        // BYZ-UNICA (ADR-0015): f e o INTEIRO da linha de comando, sem ida e volta por percentual. Universo do sorteio = so
        // validadores/votantes do protocolo (Q30 A, Q32 A). Faixa BFT: f > floor(|universo|-1)/3) falha ALTO antes de gravar
        // qualquer coisa, salvo --allow-beyond-bft-threshold=true (modo explicito, rotulado no CSV).
        final int step = Math.max(1, n / 100); // step corresponds to ~1% of N
        final boolean allowBeyond = ByzantineConfig.parseAllowBeyondBftThreshold(cli.get(ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD));
        final int universeSize = universeSize(type, n, sizing);
        // Divida aceita (ADR-0015, BYZ-UNICA parte 3 -> D2): o teto do laco e ANTES de montar fValues. max-byz > universo falha
        // ALTO aqui, com mensagem clara, e nunca chega a iterar: sem `v += step` perto de Integer.MAX_VALUE (overflow para
        // negativo = laco sem fim e memoria esgotada). A flag --allow-beyond-bft-threshold NAO libera f > universo (so f >
        // floor((n-1)/3), mais abaixo): nao ha sorteio possivel de mais bizantinos que validadores. Para max-byz <= universo o
        // laco e o mesmo de antes (mesmos f, mesma ordem, mesma saida).
        if (maxByz < 0) { // antes: laco nao rodava e o sweep seguia so com f=0 em silencio
            throw new IllegalArgumentException("contagem bizantina f=" + maxByz + " (--max-byz) negativa: use 0 ou mais");
        }
        if (maxByz > universeSize) {
            throw new IllegalArgumentException("contagem bizantina f=" + maxByz + " (--max-byz) maior que o universo de sorteio ("
                    + universoRotulo(type) + ", " + universeSize + " no(s)) no tipo " + type
                    + ": so validadores/votantes sao sorteados (ADR-0015 Q30/Q32). Reduza --max-byz; a flag --"
                    + ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD + " nao libera f > universo");
        }
        final java.util.List<Integer> fValues = new java.util.ArrayList<>();
        fValues.add(0); // include the 0-byzantine case
        for (long v = step; v <= maxByz; v += step) { // long: v + step nao estoura int mesmo com n proximo de Integer.MAX_VALUE
            fValues.add((int) v); // v <= maxByz <= universo <= Integer.MAX_VALUE
        }
        final java.util.List<String> beyondLines = new java.util.ArrayList<>();
        final StringBuilder fora = new StringBuilder();
        if (bftApplies(type)) {
            for (int f : fValues) {
                if (ByzantineConfig.beyondBftThreshold(universeSize, f)) {
                    if (!allowBeyond) {
                        fora.append(fora.length() == 0 ? "" : "; ").append("n=").append(universeSize).append(" f=").append(f)
                                .append(" limite floor((n-1)/3)=").append(ByzantineConfig.bftLimit(universeSize));
                    } else {
                        beyondLines.add(ByzantineConfig.beyondThresholdLine(universeSize, f));
                    }
                }
            }
            if (fora.length() > 0) {
                throw new IllegalArgumentException("contagem bizantina fora da faixa BFT (f > floor((n-1)/3)) no tipo " + type
                        + " (n = universo de validadores/votantes): " + fora + ". Reduza --max-byz ou use --"
                        + ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD
                        + "=true (modo explicito e rotulado, ADR-0015 decisao 3 e D3(a)); nada e arredondado em silencio");
            }
        } else if (allowBeyond) {
            throw new IllegalArgumentException("--" + ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD + "=true nao se aplica ao tipo " + type
                    + " (limiar de Nakamoto/PoW nao e floor((n-1)/3); a definir com o humano): seria ignorada em silencio");
        }

        Path outPath = Paths.get(out);
        Path outDir = outPath.getParent() == null ? Paths.get(".") : outPath.getParent();
        Files.createDirectories(outDir);

        try (BufferedWriter bw = Files.newBufferedWriter(outPath)) {
            // Modo legacy: sem as linhas '#' de dimensionamento (o legacy ja sai no stdout, "Network sizing:"); o CSV de
            // DAGSPER e BYTE A BYTE o da baseline 325f4e3. Os tipos que separam a autoentrega (D2) ganham, abaixo, as linhas
            // '#' de semantica e as 2 colunas aditivas no fim.
            // Modo nodes: linhas '#' (ignoradas por scripts/jabs/*.py): a coluna n e o PEDIDO; nos construidos aqui.
            if (nodesMode) {
                bw.write(new StringBuilder("# n_semantics=nodes")
                        .append(" (coluna n = valor pedido na linha de comando)\n").toString());
                bw.write(new StringBuilder("# ").append(sizingLine).append('\n').toString());
            }
            for (String line : beyondLines) {
                bw.write(line);
                bw.write('\n');
            }
            // D2: so os tipos que separam a autoentrega (VOTING, POS, POSA, POW) ganham as linhas '#' de semantica/semente/versao/
            // granularidade e as 2 colunas aditivas no fim; DAGSPER segue byte a byte o da baseline.
            final boolean comAutoentrega = hasSelfDeliveryColumn(type);
            if (comAutoentrega) {
                for (String c : selfDeliveryComments(seedRaw)) {
                    bw.write(c);
                    bw.write('\n');
                }
            }
            bw.write(header(type));
            bw.write('\n');

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
                double sumSelfMb = 0.0; // D2: autoentrega (MB/bloco), so dos trials em que foi medida
                double sumSelfMbSq = 0.0;
                int selfMeasured = 0;
                int secureCount = 0;

                for (int t = 0; t < trials; t++) {
                    long seed = baseSeed + n * 1000L + t;

                    SimulationMetrics metrics = new SimulationMetrics();
                    metrics.setTotalValidators(n);
                    metrics.setByzantineValidators(f);

                    try {
                        switch (type) {
                            case "VOTING": {
                                HybridNetworkScenario scenario = new HybridNetworkScenario(
                                        "sweep/arqREDD/HybridSweep-" + n + "n-" + f + "b",
                                        seed,
                                        n,
                                        duration,
                                        configFor(type, n, f, seed, attack, sizing));
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                scenario.setMaxFutureHorizon(pbftMaxFutureHorizon(cli));
                                scenario.setLeaderPolicy(pbftLeaderPolicy(cli));
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            case "POS": {
                                int numMiners = sizing[0];
                                int numStakeholders = sizing[1];
                                CasperEthereumNetworkScenario scenario = new CasperEthereumNetworkScenario(
                                        "sweep/Ethereum/CasperSweep-" + n + "n-" + f + "b",
                                        seed,
                                        duration,
                                        15.0,
                                        14,
                                        numMiners,
                                        numStakeholders);
                                if (f > 0) { // f=0: sem config (como antes: pct 0 nao injetava nada)
                                    scenario.setByzantineConfig(configFor(type, n, f, seed, attack, sizing));
                                }
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            case "POW": {
                                int confirmationDepth = 6;
                                BitcoinGlobalNetworkScenario scenario = new BitcoinGlobalNetworkScenario(
                                        "sweep/Bitcoin/BitcoinSweep-" + n + "n-" + f + "b",
                                        seed,
                                        (long) duration,
                                        600.0,
                                        n,  // numMiners (represents hashpower distribution)
                                        0,  // numNodes (non-mining nodes)
                                        confirmationDepth,
                                        enableTransactions, // whether to generate txs
                                        txInterval);
                                if (f > 0) {
                                    scenario.setByzantineConfig(configFor(type, n, f, seed, attack, sizing));
                                }
                                String tmp = outDir.resolve("tmp/" + type + "/" + n + "n-" + f + "-" + t + ".csv")
                                        .toString();
                                Files.createDirectories(Paths.get(tmp).getParent());
                                scenario.addMetricsLogger(tmp, metrics);
                                scenario.run();
                                break;
                            }
                            case "POSA": {
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

                                // universo = so os mineradores, unicos validadores do Parlia (Q32 A); antes sorteava em [0, n),
                                // incluindo stakeholders que nao validam (ver configFor)
                                scenario.setByzantineConfig(configFor(type, n, f, seed, attack, sizing));

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
                        double selfBytes = metrics.getAverageSelfDeliveryBytesPerBlock(); // D2: -1 = nao medido
                        if (contaAutoentrega(comAutoentrega, selfBytes)) {
                            double selfMb = selfBytes / (1024.0 * 1024.0); // mesma conversao de getAverageTrafficPerBlock
                            sumSelfMb += selfMb;
                            sumSelfMbSq += selfMb * selfMb;
                            selfMeasured++;
                        }
                        
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
                String row = String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.0f,%.0f,%.6f,%.6f,%.6f,%.6f,%d",
                        type, n, f, trialsExecuted,
                        avgPdv, stdPdv,
                        avgFinalTime, stdFinalTime,
                        avgConfirmationLatency, stdConfirmationLatency,
                        avgBlockCount, stdBlockCount,
                        avgTraffic, stdTraffic,
                        avgEmpiricalBFT * 100.0, stdEmpiricalBFT * 100.0,
                        secureCount);
                if (comAutoentrega) { // D2: 2 colunas aditivas no FIM; -1 se nenhum trial mediu a autoentrega
                    double[] self = selfDeliveryStats(sumSelfMb, sumSelfMbSq, selfMeasured);
                    row = new StringBuilder(row).append(',').append(selfFmt(self[0])).append(',').append(selfFmt(self[1])).toString();
                }
                bw.write(row);
                bw.write('\n');
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
            "enable-tx", "tx-interval", "out", "seed", "turn-length", "epoch-length", "max-pdv-pct", "pbft-max-future-horizon", "pbft-leader-policy",
            NetworkSizing.FLAG, ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD};

    /** Flags aceitas, como conjunto (para testes e mensagens). */
    static java.util.Set<String> knownFlags() {
        return new java.util.TreeSet<>(java.util.Arrays.asList(KNOWN));
    }

    /**
     * Config bizantina de UM nivel {@code f} (inteiro, direto da linha de comando) para o tipo {@code type}: e o unico
     * lugar onde o sweep decide universo e contagem (BYZ-UNICA, ADR-0015). {@code sizing} = {miners, stakeholders}
     * (POS/POSA) ou {@code null}. VOTING: universo [0,n). POS (Casper): todos os nos, ids [0, miners+stakeholders). POSA
     * (Parlia): so os mineradores, ids [0, miners); {@code totalValidators} = nos construidos (mineradores + stakeholders
     * da rede, que no legacy e {@code miners + n}). POW: os n mineradores. DAGSPER: {@code null} (o proprio cenario sorteia
     * pelo mesmo gerador sobre todos os nos).
     */
    static ByzantineConfig configFor(String type, int n, int f, long seed, ByzantineConfig.AttackType attack, int[] sizing) {
        switch (type) {
            case "VOTING":
            case "POW":
                return ByzantineConfig.forUniverse(n, ByzantineConfig.range(0, n), f, attack.name(), seed);
            case "POS": {
                int total = sizing[0] + sizing[1];
                return ByzantineConfig.forUniverse(total, ByzantineConfig.range(0, total), f, attack.name(), seed);
            }
            case "POSA": {
                int total = sizing[0] + sizing[1];
                return ByzantineConfig.forUniverse(total, ByzantineConfig.range(0, sizing[0]), f, attack.name(), seed);
            }
            case "DAGSPER":
                return null;
            default:
                throw new IllegalArgumentException("Unsupported protocol type: " + type);
        }
    }

    /**
     * Universo do sorteio bizantino (so validadores/votantes; ADR-0015 Q30 A, Q32 A): VOTING, DAGSPER e POW (n mineradores):
     * {@code n}; POS (Casper: todos os nos votam): mineradores + stakeholders; POSA (Parlia: so os mineradores validam):
     * mineradores. Tipo desconhecido: {@code n} (a execucao recusa o tipo depois).
     */
    static int universeSize(String type, int n, int[] sizing) {
        switch (type) {
            case "POS":
                return sizing[0] + sizing[1];
            case "POSA":
                return sizing[0];
            default:
                return n;
        }
    }

    /** Rotulo do universo, para o cabecalho '#' do CSV e mensagens. */
    static String universoRotulo(String type) {
        switch (type) {
            case "POS":
                return "casper_voters(todos_os_nos)";
            case "POSA":
                return "parlia_validators(mineradores)";
            case "POW":
                return "mineradores";
            default:
                return "n_validadores";
        }
    }

    /**
     * A faixa BFT {@code f <= floor((n-1)/3)} vale para os protocolos de votacao (VOTING, DAGSPER, POS, POSA). O POW usa
     * limiar de poder de hash (Nakamoto), nao 1/3: o valor do limiar e a definir com o humano; o guarda nao se aplica.
     */
    static boolean bftApplies(String type) {
        return !type.equals("POW");
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
            // BYZ-UNICA (ADR-0015): f inteiro direto; POS sorteia entre TODOS os nos (todos votam), POSA entre os
            // mineradores (unicos validadores do Parlia). Antes: POS ceil(stakeholders*f/n) entre stakeholders e POSA f ids em 0..n-1.
            sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                    .append(miners + stakeholders).append(" miners=").append(miners).append(" stakeholders=")
                    .append(stakeholders).append(" casper_voters=").append(miners + stakeholders)
                    .append(" casper_S=").append(stakeholders).append(" parlia_validators=").append(miners);
            return sb.append(type.equals("POS") ? " voters=casper_voters byz_universe=casper_voters(all_nodes) byz_count=f"
                    : " voters=parlia_validators byz_universe=parlia_validators(miners) byz_count=f").toString();
        }
        return sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=").append(n)
                .append(" (n ja e o numero de nos nas duas semanticas)").toString();
    }
}
