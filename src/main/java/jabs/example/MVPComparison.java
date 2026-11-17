package jabs.example;

import jabs.config.ByzantineConfig;
import jabs.config.NetworkSizing;
import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.DoubleSpendTracker;
import jabs.metrics.CbRedeMedida;
import jabs.metrics.SimulationMetrics;
import jabs.scenario.AbstractScenario;
import jabs.scenario.DAGsperNetworkScenario;
import jabs.scenario.ArqReddVotingScenario;
import jabs.scenario.HotStuffLANScenario;
import jabs.scenario.HybridNetworkScenario;
import jabs.scenario.PBFTLANScenario;
import jabs.scenario.TendermintLANScenario;
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
        // ADR-0012 (M1a-1, rodada 4): com o registro ligado, a verificacao de hashCode=2 vem ANTES de qualquer escrita
        // (banner, aviso de flag, arquivo): na falha a unica saida e a excecao GARANTIA INVALIDA.
        exigirGarantiaAntesDeQualquerSaida(args);
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
        TendermintConsensusConfig tendermintConfig = parseTendermintConfigIfRequested(protocolList, cli);
        if (tendermintConfig != null) {
            for (int validators : validatorsList) {
                tendermintConfig.validateFor(validators);
            }
        }
        // HotStuff (D3a-2a-2c-4): todas as flags --hs-* sao obrigatorias e sem padrao; n < 4 e ataque invalido falham
        // ANTES de criar saida. D3a-2a-2d-2: so EQUIVOCATION e aceito, com --byzantine-count explicito.
        // null = nenhum ataque pedido E o enum AttackType NAO foi tocado: tocar o enum aqui, na thread principal e antes
        // da simulacao, desloca a sequencia de identityHashCode e muda a saida de Parlia/Casper/Nakamoto/MCO2/AMBIFY/
        // OFFSET/EARTH sem -XX:hashCode=2 (medido em D3a-2a-2d-2; ADR-0012/0013). Ver attackForMain.
        ByzantineConfig.AttackType hotStuffAttack = attackForMain(protocolList, cli);
        HotStuffConsensusConfig hotStuffConfig = parseHotStuffConfigIfRequested(protocolList, cli);
        if (hotStuffConfig != null) {
            for (int validators : validatorsList) {
                hotStuffConfig.validateFor(validators);
            }
        }
        int hotStuffByzantineCount = resolveByzantineCount(protocolList, hotStuffAttack, cli, validatorsList);
        // ADR-0015 decisao 3 / D3(a): f > floor((n-1)/3) so chega aqui com --allow-beyond-bft-threshold=true (senao a linha
        // acima ja falhou). Rotulo '#' por n fora da faixa; vazio (nada alocado) sem ataque ou com todo f dentro da faixa.
        java.util.List<String> beyondLines = beyondThresholdLines(hotStuffAttack, hotStuffByzantineCount, validatorsList);
        for (String line : beyondLines) {
            System.out.println("AVISO: " + line.substring(2));
        }
        // HS-CB-fase2 (B2, decisao D4): HOTSTUFF e TENDERMINT na MESMA execucao exigem lote e tx-size iguais; senao falha
        // alto (ou, com --allow-fixture-mismatch=true, segue deixando rastro no stdout e no cabecalho '#' dos CSV).
        // Vazio (e nada novo alocado) quando so um dos dois protocolos roda ou quando os valores coincidem.
        java.util.List<String> fixtureLines = fixtureGuard(hotStuffConfig, tendermintConfig,
                cli.get(ALLOW_FIXTURE_MISMATCH));
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
        java.util.List<String> cbHeaderLines = joinLines(beyondLines, fixtureLines);
        initializeCSV(csvOut, nodesMode, sizingLines, joinLines(attackLines(hotStuffAttack, hotStuffByzantineCount),
                cbHeaderLines));
        // HS-CB-fase2 (parte A): colunas ADITIVAS de C_B (soma da rede e estatisticas) so para HOTSTUFF/TENDERMINT, num
        // SEGUNDO arquivo (mvp-results-cb.csv, ao lado do mvp-results.csv). Sem esses protocolos o arquivo nem e criado:
        // a saida dos demais e byte a byte a de antes (mvp-results.csv nunca muda).
        String cbCsv = cbCsvPath(csvOut);
        boolean cbCsvStarted = false;
        // D2 (ADR-0015): coluna PROPRIA de autoentrega, num terceiro arquivo ADITIVO (mvp-results-autoentrega.csv), so para
        // os protocolos que a contavam (ARQ, MCO2, TREE, AMBIFY, OFFSET, EARTH). mvp-results.csv nao ganha coluna: a saida
        // de DAGSPER/HOTSTUFF/TENDERMINT segue byte a byte a de antes. Sem esses protocolos o arquivo nem e criado.
        String selfCsv = selfDeliveryCsvPath(csvOut);
        boolean selfCsvStarted = false;

        for (int validators : validatorsList) {
            System.out.println("\n╔═══════════════════════════════════════╗");
            System.out.println("║ Testing with " + validators + " validators ║");
            System.out.println("╚═══════════════════════════════════════╝");

            for (String protocol : protocolList) {
                System.out.println("\n  ■ Protocol: " + protocol);
                
                // Create accumulator for this protocol/validators combination
                MetricsAccumulator acc = new MetricsAccumulator();
                // So HOTSTUFF/TENDERMINT: os demais nao carregam classe nova (nao desloca identityHashCode, ADR-0012)
                CbRedeMedida.Pool cbPool = hasCbRede(protocol) ? new CbRedeMedida.Pool() : null;
                double protocolDuration = protocolDurations.get(protocol);
                
                // Run all trials for this protocol/validators combination
                for (int trial = 0; trial < trials; trial++) {
                    long runSeed = baseSeed + validators * 1000L + trial;
                    System.out.println("    Trial " + (trial + 1) + "/" + trials + " (seed=" + runSeed + ")");

                    try {
                        // so com ataque ativo (que so passa na validacao com TODOS os protocolos HOTSTUFF): sorteio por
                        // ByzantineConfig.withExactCount com a semente do trial, em fluxo RandomnessEngine DEDICADO (ADR-0015 B-37/B-38)
                        ByzantineConfig hotStuffByzantine = noAttack(hotStuffAttack) ? null
                                : ByzantineConfig.withExactCount(validators, hotStuffByzantineCount,
                                        hotStuffAttack.name(), runSeed);
                        SimulationMetrics metrics = runProtocol(protocol, validators, runSeed, protocolDuration,
                            outputRoot + "/" + protocol.toLowerCase() + "-" + validators + "v-t" + trial + ".csv",
                            enableTransactions, txInterval, nodesMode, tendermintConfig, hotStuffConfig,
                            hotStuffByzantine);
                        
                        // Accumulate metrics
                        acc.addTrial(
                            metrics.getAverageBlockFinalizationTime(),
                            metrics.getAverageTrafficPerBlock(),
                            metrics.getAverageTransactionConfirmationLatency()
                        );
                        acc.addSelfDelivery(metrics.getAverageSelfDeliveryBytesPerBlock(),
                            metrics.getAverageSelfDeliveryConsensusBytesPerBlock());
                        
                        if (cbPool != null && metrics.getCbRede() != null) {
                            cbPool.add(metrics.getCbRede());
                        }

                        System.out.println("      ✓ completed");
                    } catch (Exception e) {
                        System.out.println("      ✗ failed: " + e.getMessage());
                        rethrowIfHotStuff(protocol, validators, runSeed, e);
                    }
                }
                
                // After all trials for this protocol/validators, write to CSV
                System.out.println("    → Writing results for " + protocol + " (n=" + validators + ") to CSV");
                writeSingleResultToCSV(csvOut, protocol, validators, trials, acc);
                if (hasSelfDeliveryColumn(protocol)) {
                    if (!selfCsvStarted) {
                        initializeSelfDeliveryCSV(selfCsv);
                        selfCsvStarted = true;
                    }
                    appendSelfDeliveryCSV(selfCsv, protocol, validators, trials, acc);
                }
                if (hasCbRede(protocol)) {
                    if (!cbCsvStarted) {
                        initializeCbCSV(cbCsv, cbHeaderLines);
                        cbCsvStarted = true;
                    }
                    appendCbCSV(cbCsv, protocol, validators, trials, cbPool);
                }
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
            "output", "csv-out", "enable-tx", "tx-interval", NetworkSizing.FLAG, "load-record", "load-record-t0",
            "tm-propose-timeout", "tm-propose-delta", "tm-prevote-timeout", "tm-prevote-delta",
            "tm-precommit-timeout", "tm-precommit-delta", "tm-batch-size", "tm-tx-size", "tm-message-overhead",
            "tm-signature-size", "tm-proposer-signature-size",
            "hs-view-timeout", "hs-max-future-view-horizon", "hs-batch-size", "hs-tx-size",
            "hs-individual-signature-size", "hs-aggregated-signature-size", "hs-aggregated-share-size",
            "hs-message-overhead", "hs-signature-mode", "hs-leader-policy", "hs-voter-encoding",
            "attack", "byzantine-count", "allow-fixture-mismatch", "allow-beyond-bft-threshold"};

    /** ADR-0012: registro de carga do enlace ligado? Primitivos: o caminho desligado nao carrega classe nova. */
    private static boolean loadRecordOn = false;
    private static double loadRecordT0 = 0.0;

    /**
     * Pre-varredura de {@code args} (sem escrever nada e sem {@code parseArgs}, que pode avisar no stderr): se o valor
     * final de {@code --load-record} (mesma regra de {@code parseArgs}: a ultima ocorrencia vence) for exatamente
     * {@code true}, chama {@code GarantiaHashCode.exigir()}. Qualquer outro caso (ausente, {@code false}, invalido)
     * nao toca em nada: o desligado nem carrega {@code GarantiaHashCode}, e o valor invalido segue falhando em
     * {@link #configureLoadRecord}.
     */
    static void exigirGarantiaAntesDeQualquerSaida(String[] args) {
        String valor = null;
        for (String arg : args) {
            if (arg.startsWith("--load-record=")) {
                valor = arg.substring("--load-record=".length());
            }
        }
        if ("true".equals(valor)) {
            jabs.log.GarantiaHashCode.exigir();
        }
    }

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
            // Garantia de nao regressao so vale com -XX:hashCode=2: falha alto ANTES de qualquer simulacao (sem opt-out).
            // So no caminho ligado; desligado nao carrega GarantiaHashCode nem consulta a JVM.
            jabs.log.GarantiaHashCode.exigir();
            System.out.println("[reprodutibilidade] registro de carga ligado: " + jabs.log.GarantiaHashCode.linhaReprodutibilidade());
            loadRecordOn = true;
            loadRecordT0 = t0;
        }
    }

    /**
     * Config do Tendermint so e exigida quando {@code TENDERMINT} aparece em {@code --protocols}. Sem valores de
     * pesquisa padrao: timeouts, lote, tamanho de transacao, overhead de mensagem e tamanho da
     * assinatura do voto e da proposta (ADR-0010 PI-08/PI-16: a definir com o humano) precisam vir das flags {@code --tm-*}.
     */
    static TendermintConsensusConfig parseTendermintConfigIfRequested(java.util.List<String> protocols,
            java.util.Map<String, String> cli) {
        boolean requested = false;
        for (String protocol : protocols) {
            if ("TENDERMINT".equalsIgnoreCase(protocol)) {
                requested = true;
                break;
            }
        }
        if (!requested) {
            return null;
        }
        TendermintTimeouts timeouts = new TendermintTimeouts(
                requiredDouble(cli, "tm-propose-timeout"),
                requiredDouble(cli, "tm-propose-delta"),
                requiredDouble(cli, "tm-prevote-timeout"),
                requiredDouble(cli, "tm-prevote-delta"),
                requiredDouble(cli, "tm-precommit-timeout"),
                requiredDouble(cli, "tm-precommit-delta"));
        TendermintConsensusConfig.Builder builder = TendermintConsensusConfig.builder()
                .timeouts(timeouts)
                .batchSize(requiredInt(cli, "tm-batch-size"))
                .txSizeBytes(requiredInt(cli, "tm-tx-size"))
                .messageOverheadBytes(requiredInt(cli, "tm-message-overhead"))
                .signatureSizeBytes(requiredInt(cli, "tm-signature-size"))
                .proposerSignatureSizeBytes(requiredInt(cli, "tm-proposer-signature-size"));
        return builder.build();
    }

    private static String requiredCliValue(java.util.Map<String, String> cli, String flag) {
        return requiredCliValue(cli, flag, "TENDERMINT", "");
    }

    private static String requiredCliValue(java.util.Map<String, String> cli, String flag, String protocolo,
            String sufixo) {
        String value = cli.get(flag);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("protocolo " + protocolo + " exige --" + flag + "=valor" + sufixo);
        }
        return value;
    }

    private static double requiredDouble(java.util.Map<String, String> cli, String flag) {
        try {
            return Double.parseDouble(requiredCliValue(cli, flag));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + flag + " invalido: " + cli.get(flag), e);
        }
    }

    private static int requiredInt(java.util.Map<String, String> cli, String flag) {
        try {
            return Integer.parseInt(requiredCliValue(cli, flag));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + flag + " invalido: " + cli.get(flag), e);
        }
    }

    /** Sufixo das mensagens de flag HotStuff ausente: nenhum valor de pesquisa tem padrao (regra do projeto). */
    private static final String HS_SEM_PADRAO = " (sem valor padrao; a definir com o humano; ADR-0015)";

    private static double requiredDouble(java.util.Map<String, String> cli, String flag, String protocolo) {
        try {
            return Double.parseDouble(requiredCliValue(cli, flag, protocolo, HS_SEM_PADRAO));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + flag + " invalido: " + cli.get(flag), e);
        }
    }

    private static int requiredInt(java.util.Map<String, String> cli, String flag, String protocolo) {
        try {
            return Integer.parseInt(requiredCliValue(cli, flag, protocolo, HS_SEM_PADRAO));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + flag + " invalido: " + cli.get(flag), e);
        }
    }

    private static long requiredLong(java.util.Map<String, String> cli, String flag, String protocolo) {
        try {
            return Long.parseLong(requiredCliValue(cli, flag, protocolo, HS_SEM_PADRAO));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + flag + " invalido: " + cli.get(flag), e);
        }
    }

    /** Enum pelo nome EXATO (maiusculas): outro texto falha alto listando os validos; nunca cai em padrao. */
    private static <E extends Enum<E>> E requiredEnum(java.util.Map<String, String> cli, String flag,
            String protocolo, Class<E> tipo) {
        String value = requiredCliValue(cli, flag, protocolo, HS_SEM_PADRAO);
        for (E candidato : tipo.getEnumConstants()) {
            if (candidato.name().equals(value)) {
                return candidato;
            }
        }
        throw new IllegalArgumentException("--" + flag + " invalido: '" + value + "'. Validos: "
                + java.util.Arrays.toString(tipo.getEnumConstants()) + " (nomes exatos, em maiusculas)");
    }

    /**
     * Config do HotStuff so e exigida quando {@code HOTSTUFF} aparece em {@code --protocols}. TODAS as 11 flags
     * {@code --hs-*} sao obrigatorias e sem padrao, inclusive as tres que o {@link HotStuffConsensusConfig} ainda
     * preenche em silencio (politica de lider, modo de assinatura e overhead de mensagem: ADR-0015 A-08, A-09, A-10):
     * aqui o padrao do config NUNCA decide, porque o builder recebe sempre o valor da linha de comando. O horizonte de
     * visoes futuras, o timeout de visao, o lote, os tamanhos e a codificacao dos votantes: a definir com o humano
     * (ADR-0015 A-24, A-25 etc.).
     */
    static HotStuffConsensusConfig parseHotStuffConfigIfRequested(java.util.List<String> protocols,
            java.util.Map<String, String> cli) {
        boolean requested = false;
        for (String protocol : protocols) {
            if (isHotStuff(protocol)) {
                requested = true;
                break;
            }
        }
        if (!requested) {
            return null;
        }
        final String p = "HOTSTUFF";
        return HotStuffConsensusConfig.builder()
                .viewTimeoutSeconds(requiredDouble(cli, "hs-view-timeout", p))
                .maxFutureViewHorizon(requiredLong(cli, "hs-max-future-view-horizon", p))
                .batchSize(requiredInt(cli, "hs-batch-size", p))
                .txSizeBytes(requiredInt(cli, "hs-tx-size", p))
                .individualSignatureSizeBytes(requiredInt(cli, "hs-individual-signature-size", p))
                .aggregatedSignatureSizeBytes(requiredInt(cli, "hs-aggregated-signature-size", p))
                .aggregatedShareSizeBytes(requiredInt(cli, "hs-aggregated-share-size", p))
                .messageOverheadBytes(requiredInt(cli, "hs-message-overhead", p))
                .signatureMode(requiredEnum(cli, "hs-signature-mode", p, HotStuffConsensusConfig.SignatureMode.class))
                .leaderPolicy(requiredEnum(cli, "hs-leader-policy", p, HotStuffConsensusConfig.LeaderPolicy.class))
                .voterEncoding(requiredEnum(cli, "hs-voter-encoding", p, HotStuffConsensusConfig.VoterEncoding.class))
                .build();
    }

    /**
     * Ataque pedido por {@code --attack} (D3a-2a-2d-2). Ausente, {@code none} ou {@code nenhum} (sem distincao de caixa)
     * = {@code NONE}. Com HOTSTUFF so {@code EQUIVOCATION} (em qualquer caixa) e aceito; {@code SILENT}, {@code WITHHOLD},
     * {@code DOUBLE_SIGN} e nome desconhecido falham alto, ANTES de criar saida. Nao ha ataque padrao.
     *
     * <p>Sem HOTSTUFF em {@code --protocols} a flag segue ignorada, como na baseline (o {@code MVPComparison} nunca a
     * leu). Com HOTSTUFF MISTURADO a outro protocolo e ataque ativo, falha alto: os demais protocolos nao leem
     * {@code --attack} aqui e rodariam honestos sob o mesmo rotulo (ataque ignorado em silencio mudaria o resultado).
     */
    /**
     * Leitura de {@code --attack} para o {@code main}: devolve {@code null} (sem tocar o enum {@code AttackType}) quando
     * a flag esta ausente ou nenhum protocolo e HOTSTUFF; so com a flag E HOTSTUFF delega a
     * {@link #resolveHotStuffAttack}. Motivo: a primeira execucao de {@code ByzantineConfig.AttackType.NONE} inicializa o
     * enum e consome identityHashCode na thread principal; antes da D3a-2a-2d-2 isso so acontecia dentro do cenario,
     * e a saida dos protocolos sensiveis a hash de identidade (sem {@code -XX:hashCode=2}) dependia dessa posicao.
     */
    static ByzantineConfig.AttackType attackForMain(java.util.List<String> protocols, java.util.Map<String, String> cli) {
        String attack = cli.get("attack");
        if (attack == null) {
            return null;
        }
        boolean temHotStuff = false;
        for (String protocol : protocols) {
            temHotStuff |= isHotStuff(protocol);
        }
        if (!temHotStuff) {
            avisar(new StringBuilder("--attack=").append(attack).append(" ignorada (so o HOTSTUFF a le no MVPComparison)"));
            return null;
        }
        return resolveHotStuffAttack(protocols, cli);
    }

    /** {@code true} para {@code null} (nao pedido) e para {@code NONE}; a comparacao com o enum so roda se nao for null. */
    static boolean noAttack(ByzantineConfig.AttackType attack) {
        return attack == null || attack == ByzantineConfig.AttackType.NONE;
    }

    static ByzantineConfig.AttackType resolveHotStuffAttack(java.util.List<String> protocols,
            java.util.Map<String, String> cli) {
        String attack = cli.get("attack");
        if (attack == null) {
            return ByzantineConfig.AttackType.NONE;
        }
        boolean temHotStuff = false;
        boolean temOutro = false;
        for (String protocol : protocols) {
            if (isHotStuff(protocol)) {
                temHotStuff = true;
            } else {
                temOutro = true;
            }
        }
        if (!temHotStuff) {
            avisar(new StringBuilder("--attack=").append(attack).append(" ignorada (so o HOTSTUFF a le no MVPComparison)"));
            return ByzantineConfig.AttackType.NONE;
        }
        String nome = attack.trim();
        if (nome.equalsIgnoreCase("none") || nome.equalsIgnoreCase("nenhum")) {
            return ByzantineConfig.AttackType.NONE;
        }
        ByzantineConfig.AttackType tipo;
        try {
            tipo = ByzantineConfig.AttackType.valueOf(nome.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ataque desconhecido '" + attack + "' para HOTSTUFF. Validos: "
                    + ByzantineConfig.validAttackNames() + " (so EQUIVOCATION implementado; none/nenhum = sem ataque)", e);
        }
        if (tipo == ByzantineConfig.AttackType.NONE) {
            return tipo;
        }
        if (tipo != ByzantineConfig.AttackType.EQUIVOCATION) {
            throw new IllegalArgumentException("ataque " + tipo + " nao implementado para HOTSTUFF (so EQUIVOCATION)");
        }
        if (temOutro) {
            throw new IllegalArgumentException("--attack=" + tipo + " so e aceito com --protocols contendo apenas HOTSTUFF: "
                    + "os demais protocolos nao leem --attack no MVPComparison e rodariam sem ataque (use ByzantineSweep)");
        }
        return tipo;
    }

    /**
     * Quantidade de bizantinos de {@code --byzantine-count} (inteiro, sem padrao): obrigatoria e {@code >= 1} com ataque
     * ativo; sem ataque, a flag so e aceita se for {@code 0} (contagem sem ataque seria ignorada em silencio). Cada
     * {@code n} de {@code validators} precisa ter {@code count <= n}. Fracao NAO e aceita: a regra de arredondamento de
     * fracao para contagem e o universo do sorteio sao {@code a definir com o humano} (ADR-0015 B-33/Q34, B-37/Q30).
     * Falha antes de criar saida. Devolve 0 sem ataque.
     *
     * <p>Faixa BFT (ADR-0015, decisao 3 de 10/11/2026 e D3(a)): {@code f > floor((n-1)/3)} para algum {@code n} e "fora da
     * faixa" e FALHA ALTO (a mensagem cita n, f e o limite), a menos que {@code --allow-beyond-bft-threshold=true} esteja
     * presente; com a flag o modo roda e e ROTULADO ({@link #beyondThresholdLines}). A flag so tem sentido com ataque
     * ativo e HOTSTUFF: com HOTSTUFF e sem ataque, {@code =true} falha alto (seria ignorada em silencio, como a contagem);
     * sem HOTSTUFF segue ignorada com aviso no stderr, como {@code --attack}. Aritmetica inteira.
     */
    static int resolveByzantineCount(java.util.List<String> protocols, ByzantineConfig.AttackType attack,
            java.util.Map<String, String> cli, java.util.List<Integer> validators) {
        String bruto = cli.get("byzantine-count");
        String beyondBruto = cli.get(ALLOW_BEYOND_BFT_THRESHOLD);
        boolean temHotStuffGeral = false;
        for (String protocol : protocols) {
            temHotStuffGeral |= isHotStuff(protocol);
        }
        boolean allowBeyond = false;
        if (beyondBruto != null) {
            if (!temHotStuffGeral) {
                avisar(new StringBuilder("--").append(ALLOW_BEYOND_BFT_THRESHOLD).append("=").append(beyondBruto)
                        .append(" ignorada (so o HOTSTUFF a le no MVPComparison)"));
            } else {
                allowBeyond = parseAllowBeyondBftThreshold(beyondBruto);
                if (allowBeyond && noAttack(attack)) {
                    throw new IllegalArgumentException("--" + ALLOW_BEYOND_BFT_THRESHOLD + "=true sem ataque ativo (--attack="
                            + cli.get("attack") + "): a flag seria ignorada em silencio");
                }
            }
        }
        if (noAttack(attack)) {
            boolean temHotStuff = false;
            for (String protocol : protocols) {
                temHotStuff |= isHotStuff(protocol);
            }
            // sem HOTSTUFF o MVPComparison nunca leu esta flag: ignorada como na baseline
            if (temHotStuff && bruto != null && !"0".equals(bruto.trim())) {
                throw new IllegalArgumentException("--byzantine-count=" + bruto + " sem ataque ativo (--attack="
                        + cli.get("attack") + "): a contagem seria ignorada em silencio");
            }
            return 0;
        }
        if (bruto == null || bruto.isBlank()) {
            throw new IllegalArgumentException("--attack=" + attack + " exige --byzantine-count=f (inteiro >= 1, sem valor "
                    + "padrao; a definir com o humano: ADR-0015 B-33/Q34)");
        }
        int count;
        try {
            count = Integer.parseInt(bruto.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--byzantine-count invalido (inteiro esperado, fracao nao aceita): " + bruto, e);
        }
        if (count < 1) {
            throw new IllegalArgumentException("--byzantine-count deve ser >= 1 com ataque ativo: " + count);
        }
        for (int n : validators) {
            if (count > n) {
                throw new IllegalArgumentException("--byzantine-count=" + count + " maior que n=" + n);
            }
        }
        if (!allowBeyond) {
            StringBuilder fora = null;
            for (int n : validators) {
                if (count > bftLimit(n)) {
                    fora = (fora == null ? new StringBuilder() : fora.append("; "));
                    fora.append("n=").append(n).append(" f=").append(count).append(" limite floor((n-1)/3)=")
                            .append(bftLimit(n));
                }
            }
            if (fora != null) {
                throw new IllegalArgumentException("--byzantine-count fora da faixa BFT (f > floor((n-1)/3)): " + fora
                        + ". Modo explicito e rotulado so com --" + ALLOW_BEYOND_BFT_THRESHOLD
                        + "=true (ADR-0015 decisao 3 e D3(a)); nada e arredondado em silencio");
            }
        }
        return count;
    }

    /** Flag que libera (rotulado) {@code f > floor((n-1)/3)} no HOTSTUFF (ADR-0015 D3(a)). Valor aceito: {@code true} ou {@code false}. */
    static final String ALLOW_BEYOND_BFT_THRESHOLD = ByzantineConfig.ALLOW_BEYOND_BFT_THRESHOLD;

    /** {@code floor((n-1)/3)}: maior f tolerado com n = 3f+1. Inteiro; {@code floorDiv} para n &lt; 1 nao truncar para 0. */
    static int bftLimit(int n) {
        return Math.floorDiv(n - 1, 3);
    }

    /** {@code true}/{@code false} exatos; qualquer outro valor falha alto (mesmo padrao de {@code --allow-fixture-mismatch}). */
    static boolean parseAllowBeyondBftThreshold(String valor) {
        if (valor == null || valor.equals("false")) {
            return false;
        }
        if (valor.equals("true")) {
            return true;
        }
        throw new IllegalArgumentException("--" + ALLOW_BEYOND_BFT_THRESHOLD + " aceita 'true' ou 'false' (recebido '" + valor + "')");
    }

    /**
     * Linhas '#' (uma por {@code n} com {@code count > floor((n-1)/3)}) que rotulam a saida como fora da faixa BFT.
     * Vazia, sem alocar, sem ataque ou quando todo f esta dentro da faixa: com ou sem a flag a saida dentro da faixa e
     * byte a byte a de antes (a flag NAO rotula o que esta dentro). Chamada so depois de {@link #resolveByzantineCount}.
     */
    static java.util.List<String> beyondThresholdLines(ByzantineConfig.AttackType attack, int count,
            java.util.List<Integer> validators) {
        if (noAttack(attack)) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> linhas = null;
        for (int n : validators) {
            if (count > bftLimit(n)) {
                if (linhas == null) {
                    linhas = new java.util.ArrayList<>();
                }
                linhas.add(new StringBuilder("# fora_da_faixa=true n=").append(n).append(" byzantine_count=").append(count)
                        .append(" limite_bft=").append(bftLimit(n))
                        .append(" (limite = floor((n-1)/3); modo explicito --").append(ALLOW_BEYOND_BFT_THRESHOLD)
                        .append("=true; ADR-0015 decisao 3 e D3(a); resultado fora da garantia BFT do protocolo)").toString());
            }
        }
        return linhas == null ? java.util.Collections.emptyList() : linhas;
    }

    /** Concatena linhas '#' sem alocar quando uma das listas e vazia (o caminho sem trava devolve a lista original). */
    static java.util.List<String> joinLines(java.util.List<String> a, java.util.List<String> b) {
        if (b.isEmpty()) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        java.util.List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Flag de override da trava de fixture (HS-CB-fase2, B2). Valor aceito: {@code true} ou {@code false}. */
    static final String ALLOW_FIXTURE_MISMATCH = "allow-fixture-mismatch";

    /**
     * Trava de fixture (HS-CB-fase2, B2; decisao D4). HOTSTUFF e TENDERMINT rodando na MESMA execucao com lote
     * ({@code *-batch-size}) ou tamanho de transacao ({@code *-tx-size}) diferentes comparam cargas diferentes sob o
     * mesmo rotulo de C_B: falha alto. Com {@code --allow-fixture-mismatch=true} segue, mas o rastro vai para o stdout
     * e para o cabecalho '#' de {@code mvp-results.csv} e {@code mvp-results-cb.csv} (devolvido em {@code fixtureLines}).
     *
     * <p>Devolve lista vazia, sem alocar nada, quando so um dos dois protocolos roda (config nula) ou quando os valores
     * coincidem: a saida desses casos e byte a byte a de antes. A trava compara; NAO escolhe valor (a fixture
     * canonica vive em {@code src/test/resources/fixture/fixture-canonica.properties}, fora do codigo de producao).
     * Lote e tx-size igual NAO e calibracao: a definicao de lote/tx-size da pesquisa segue a definir com o humano.
     */
    static java.util.List<String> fixtureGuard(HotStuffConsensusConfig hs, TendermintConsensusConfig tm,
            String allowFlag) {
        if (hs == null || tm == null) {
            return java.util.Collections.emptyList();
        }
        boolean allow = parseAllowFixtureMismatch(allowFlag);
        if (hs.getBatchSize() == tm.getBatchSize() && hs.getTxSizeBytes() == tm.getTxSizeBytes()) {
            return java.util.Collections.emptyList();
        }
        String diff = new StringBuilder("HOTSTUFF batch=").append(hs.getBatchSize()).append(" tx-size=")
                .append(hs.getTxSizeBytes()).append(" x TENDERMINT batch=").append(tm.getBatchSize())
                .append(" tx-size=").append(tm.getTxSizeBytes()).toString();
        if (!allow) {
            throw new IllegalArgumentException("trava de fixture: HOTSTUFF e TENDERMINT na mesma execucao exigem o mesmo lote e "
                    + "tx-size (" + diff + "). O valor canonico de lote/tx-size da pesquisa e a definir com o humano; use "
                    + "iguais (fixture canonica: src/test/resources/fixture/fixture-canonica.properties) ou, SO para "
                    + "diagnostico, --" + ALLOW_FIXTURE_MISMATCH + "=true (fica registrado no stdout e no cabecalho '#' dos CSV)");
        }
        String linha = new StringBuilder("fixture-mismatch AUTORIZADO por --").append(ALLOW_FIXTURE_MISMATCH)
                .append("=true: ").append(diff).append(" (C_B de HOTSTUFF e TENDERMINT NAO comparaveis entre si)").toString();
        System.out.println("AVISO: " + linha);
        return java.util.List.of("# " + linha);
    }

    /** {@code null} = false; {@code true}/{@code false} exatos; qualquer outro valor falha alto. */
    static boolean parseAllowFixtureMismatch(String valor) {
        if (valor == null || valor.equals("false")) {
            return false;
        }
        if (valor.equals("true")) {
            return true;
        }
        throw new IllegalArgumentException("--" + ALLOW_FIXTURE_MISMATCH + " aceita 'true' ou 'false' (recebido '" + valor + "')");
    }

    /** Linhas '#' do CSV agregado que declaram o ataque; vazio sem ataque (o CSV honesto fica byte a byte o de sempre). */
    static java.util.List<String> attackLines(ByzantineConfig.AttackType attack, int count) {
        if (noAttack(attack)) {
            return java.util.Collections.emptyList();
        }
        return java.util.List.of(new StringBuilder("# attack=").append(attack).append(" byzantine_count=").append(count)
                .append(" (HOTSTUFF; sorteio RandomnessEngine dedicado (semente do trial ^ sal) em [0,n), nao o laco do ARQ; coluna n = validadores;")
                .append(" particao, abrangencia e coordenacao PROVISORIAS: ADR-0015 Q28)").toString());
    }

    /** Liga o registro de carga neste cenario, se ligado por linha de comando. Desligado: retorna sem fazer nada. */
    private static void attachLoadRecorder(AbstractScenario scenario, String protocol, long seed, String outputPath) {
        if (!loadRecordOn) {
            return;
        }
        String prefix = outputPath.endsWith(".csv") ? outputPath.substring(0, outputPath.length() - 4) : outputPath;
        scenario.AddNewLogger(new jabs.log.LinkLoadRecorder(prefix, protocol, seed, loadRecordT0));
    }

    /** Nome canonico do protocolo (unica regra de caixa e de espacos): vale para parse, rejeicao, execucao e falha. */
    static String normalizeProtocol(String protocol) {
        return protocol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** O nome (em qualquer caixa, com espacos ao redor) designa o HotStuff? */
    static boolean isHotStuff(String protocol) {
        return protocol != null && "HOTSTUFF".equals(normalizeProtocol(protocol));
    }

    /**
     * Falha de trial: HotStuff NAO herda o "engole trial" (uma linha de zeros no CSV agregado seria numero inventado),
     * entao a falha e relancada; os demais protocolos seguem como antes (a mensagem ja foi impressa, o fluxo continua).
     */
    static void rethrowIfHotStuff(String protocol, int validators, long seed, Exception e) {
        if (isHotStuff(protocol)) {
            throw new IllegalStateException("trial HOTSTUFF falhou (n=" + validators + ", seed=" + seed
                    + "): " + e.getMessage(), e);
        }
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
        switch (normalizeProtocol(protocol)) {
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
            case "HOTSTUFF":
            case "TENDERMINT":
                return sb.append(" n_semantics=").append(rot).append(" n_requested=").append(n).append(" nodes=")
                        .append(n).append(" validators=").append(n).toString();
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
                        String protocol = normalizeProtocol(parts[0]);
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
            java.util.List<String> sizingLines, java.util.List<String> attackLines) throws IOException {
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
            for (String line : attackLines) {
                bw.write(line);
                bw.write('\n');
            }
            bw.write("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std\n");
        }
    }

    /** {@code .../mvp-results.csv} -&gt; {@code .../mvp-results-cb.csv} (sufixo antes da extensao; sem extensao, anexa). */
    static String cbCsvPath(String csvOut) {
        return csvOut.endsWith(".csv") ? csvOut.substring(0, csvOut.length() - 4) + "-cb.csv" : csvOut + "-cb.csv";
    }

    /** {@code .../mvp-results.csv} -&gt; {@code .../mvp-results-autoentrega.csv} (D2; mesma regra de {@link #cbCsvPath}). */
    static String selfDeliveryCsvPath(String csvOut) {
        // StringBuilder, nao '+': roda ANTES da simulacao e uma concatenacao nova (invokedynamic) consome hash de identidade
        return csvOut.endsWith(".csv")
                ? new StringBuilder(csvOut.substring(0, csvOut.length() - 4)).append("-autoentrega.csv").toString()
                : new StringBuilder(csvOut).append("-autoentrega.csv").toString();
    }

    /**
     * D2: protocolos que CONTAVAM a autoentrega e agora a separam (PBFT = ARQ; Casper = MCO2/TREE; Parlia = AMBIFY;
     * Nakamoto = OFFSET/EARTH). DAGSPER, HOTSTUFF e TENDERMINT ja a filtravam: sem coluna, saida inalterada.
     */
    static boolean hasSelfDeliveryColumn(String protocol) {
        String p = normalizeProtocol(protocol);
        return "ARQ".equals(p) || "MCO2".equals(p) || "TREE".equals(p) || "AMBIFY".equals(p)
                || "OFFSET".equals(p) || "EARTH".equals(p);
    }

    static final String[] SELF_DELIVERY_COMMENTS = {
        "# mvp-results-autoentrega.csv: coluna PROPRIA e ADITIVA da AUTOENTREGA (D2, ADR-0015 registro de 10/11/2026) so para ARQ, MCO2, TREE, AMBIFY, OFFSET e EARTH; os demais protocolos NAO tem linha aqui (ja filtravam a autoentrega; mvp-results.csv segue como era). Separador ';'. -1 = nao medido (nunca zero).",
        "# Autoentrega = pacote que o proprio no entrega a si mesmo (Packet.getFrom() == receptor). NAO e C_B (regra 6 do AGENTS.md: so conta pacote que passou pela rede simulada) e por isso esta FORA de Cb_avg/Cb_std do mvp-results.csv. Unidade: bytes (Cb_avg e em MB).",
        "# Cb_autoentrega_avg_B / _std_B: media e desvio (amostral, entre trials) da media por bloco da autoentrega do no que finalizou primeiro cada altura (a MESMA populacao do Cb_avg). Cb_autoentrega_consenso_avg_B: parte so-consenso; -1 para Casper/Parlia/Nakamoto (nao decompoem consenso) e para o PBFT quando nada foi medido.",
        "# Contabilidade apenas: o pacote de autoentrega continua percorrendo o simulador como antes (PBFT: fila de envio e latencia; blocos: chamada direta); so o destino do byte contado muda."
    };
    static final String SELF_DELIVERY_HEADER =
            "project;n;trials;Cb_autoentrega_avg_B;Cb_autoentrega_std_B;Cb_autoentrega_consenso_avg_B";

    private static void initializeSelfDeliveryCSV(String path) throws IOException {
        try (java.io.BufferedWriter bw = Files.newBufferedWriter(java.nio.file.Paths.get(path))) {
            for (String c : SELF_DELIVERY_COMMENTS) {
                bw.write(c);
                bw.write('\n');
            }
            bw.write(SELF_DELIVERY_HEADER);
            bw.write('\n');
        }
    }

    /** Uma linha por (protocolo, n); sem trial medido, tudo -1 (nao medido), nunca zero. */
    private static void appendSelfDeliveryCSV(String path, String protocol, int n, int trials, MetricsAccumulator acc)
            throws IOException {
        try (java.io.BufferedWriter bw = Files.newBufferedWriter(java.nio.file.Paths.get(path),
                java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(selfDeliveryLine(protocol, n, trials, acc.selfAvg(), acc.selfStd(), acc.selfConsensusAvg()));
            bw.write('\n');
        }
    }

    /** Formata a linha de autoentrega; {@code -1} (sentinela) sai como inteiro {@code -1}. */
    static String selfDeliveryLine(String protocol, int n, int trials, double avg, double std, double consensusAvg) {
        return String.format(java.util.Locale.ROOT, "%s;%d;%d;%s;%s;%s", protocol, n, trials,
                selfFmt(avg), selfFmt(std), selfFmt(consensusAvg));
    }

    private static String selfFmt(double v) {
        return v < 0 ? "-1" : String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** Protocolos com SOMA DA REDE medida (HS-CB-fase2): so HOTSTUFF e TENDERMINT. Os demais: nao medido. */
    static boolean hasCbRede(String protocol) {
        String p = normalizeProtocol(protocol);
        return "HOTSTUFF".equals(p) || "TENDERMINT".equals(p);
    }

    private static void initializeCbCSV(String path, java.util.List<String> fixtureLines) throws IOException {
        try (java.io.BufferedWriter bw = Files.newBufferedWriter(java.nio.file.Paths.get(path))) {
            for (String c : CbRedeMedida.CSV_COMMENTS) {
                bw.write(c);
                bw.write('\n');
            }
            for (String c : fixtureLines) {
                bw.write(c);
                bw.write('\n');
            }
            bw.write(CbRedeMedida.CSV_HEADER);
            bw.write('\n');
        }
    }

    /** Uma linha por (protocolo, n); sem trial medido, tudo -1 (nao medido), nunca zero. */
    private static void appendCbCSV(String path, String protocol, int n, int trials, CbRedeMedida.Pool pool)
            throws IOException {
        try (java.io.BufferedWriter bw = Files.newBufferedWriter(java.nio.file.Paths.get(path),
                java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(pool.trials() == 0 ? CbRedeMedida.csvLineNotMeasured(protocol, n, trials)
                    : CbRedeMedida.csvLine(protocol, n, trials, pool));
            bw.write('\n');
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
    static SimulationMetrics runProtocol(String protocol, int validators, long seed,
            double duration, String outputPath, boolean enableTx, double txInterval,
            boolean nodesMode, TendermintConsensusConfig tendermintConfig,
            HotStuffConsensusConfig hotStuffConfig) throws Exception {
        return runProtocol(protocol, validators, seed, duration, outputPath, enableTx, txInterval, nodesMode,
                tendermintConfig, hotStuffConfig, null);
    }

    /** {@code hotStuffByzantine}: so o HOTSTUFF o le ({@code null} = sem ataque, o caminho de sempre). */
    static SimulationMetrics runProtocol(String protocol, int validators, long seed,
            double duration, String outputPath, boolean enableTx, double txInterval,
            boolean nodesMode, TendermintConsensusConfig tendermintConfig,
            HotStuffConsensusConfig hotStuffConfig, ByzantineConfig hotStuffByzantine) throws Exception {
        
        switch (normalizeProtocol(protocol)) {
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
            case "TENDERMINT":
                return runTendermintScenario(validators, seed, duration, outputPath, tendermintConfig);
            case "HOTSTUFF":
                return runHotStuffScenario(validators, seed, duration, outputPath, hotStuffConfig, hotStuffByzantine);
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
        // D2: autoentrega (bytes por bloco, media do trial); trial sem medida (sentinela <0) nao entra
        private double sumSelf = 0.0, sumSelfSq = 0.0, sumSelfCons = 0.0;
        private int selfCount = 0, selfConsCount = 0;

        void addSelfDelivery(double selfAvgBytes, double selfConsensusAvgBytes) {
            if (selfAvgBytes >= 0) {
                sumSelf += selfAvgBytes;
                sumSelfSq += selfAvgBytes * selfAvgBytes;
                selfCount++;
            }
            if (selfConsensusAvgBytes >= 0) {
                sumSelfCons += selfConsensusAvgBytes;
                selfConsCount++;
            }
        }

        double selfAvg() { return selfCount > 0 ? sumSelf / selfCount : -1.0; }
        double selfConsensusAvg() { return selfConsCount > 0 ? sumSelfCons / selfConsCount : -1.0; }
        double selfStd() {
            if (selfCount == 0) return -1.0;
            if (selfCount == 1) return 0.0;
            double var = (sumSelfSq - (sumSelf * sumSelf) / selfCount) / (selfCount - 1);
            return Math.sqrt(Math.max(0, var));
        }

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
            // BYZ-UNICA (ADR-0015, decisao 1): f=0 e inteiro, entra por contagem (nao por pct=0.0). Mesma configuracao
            // de antes (30% privadas, 20/60/20, conjunto vazio, sem consumir o fluxo): saida sem ataque identica.
            HybridNetworkScenario scenario = new HybridNetworkScenario(
                    "Arq-REDD+ Hybrid (Honest Nodes)",
                    seed,
                    totalValidators,
                    duration,
                    ByzantineConfig.withExactCount(totalValidators, 0, ByzantineConfig.AttackType.NONE.name(), seed));
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

    /**
     * Tendermint honesto (ADR-0006): {@code numNodes} e o numero de validadores (n >= 4). A config vem das flags
     * {@code --tm-*}; sem configuracao o protocolo falha alto antes da simulacao deixar rastros.
     */
    private static SimulationMetrics runTendermintScenario(int numNodes, long seed, double duration,
            String outputPath, TendermintConsensusConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("protocolo TENDERMINT exige flags --tm-* (timeouts, lote e tx)");
        }
        TendermintLANScenario scenario = new TendermintLANScenario(
                "Tendermint - " + numNodes + " validators", seed, numNodes, duration, config);
        scenario.addMetricsLogger(outputPath);
        attachLoadRecorder(scenario, "TENDERMINT", seed, outputPath);
        System.out.println("Running Tendermint scenario (" + numNodes + " validators, seed=" + seed + ") ...");
        scenario.run();
        SimulationMetrics metrics = scenario.getMetrics();
        metrics.setCbRede(CbRedeMedida.medir(scenario.getNetwork().getAllNodes(), metrics)); // HS-CB-fase2
        printScenarioFinilizedMetrics(metrics);
        return metrics;
    }

    /**
     * HotStuff honesto (D3a-2a-2c-4): {@code numNodes} validadores (n >= 4) na LAN de malha completa, config das flags
     * {@code --hs-*} (sem padrao). Mesmas metricas e CSV por bloco dos demais (Tb, C_B recebido pelo primeiro no a
     * finalizar cada altura, so de pacotes entregues pela rede simulada). Falha alto, sem inventar zero, se a corrida
     * nao finalizou bloco ou se nenhuma transacao foi confirmada (Tt nao medido).
     */
    private static SimulationMetrics runHotStuffScenario(int numNodes, long seed, double duration,
            String outputPath, HotStuffConsensusConfig config, ByzantineConfig byzantine) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("protocolo HOTSTUFF exige flags --hs-* (sem valor padrao)");
        }
        // Sem ataque: nome e construtor de sempre (saida identica). Com ataque: o nome declara ataque e contagem.
        HotStuffLANScenario scenario = byzantine == null
                ? new HotStuffLANScenario("HotStuff - " + numNodes + " validators", seed, numNodes, duration, config)
                : new HotStuffLANScenario("HotStuff - " + numNodes + " validators, " + byzantine.getAttackType()
                        + " f=" + byzantine.getByzantineCount(), seed, numNodes, duration, config,
                        java.util.Set.of(), byzantine);
        scenario.addMetricsLogger(outputPath);
        attachLoadRecorder(scenario, "HOTSTUFF", seed, outputPath);
        System.out.println("Running HotStuff scenario (" + numNodes + " validators, seed=" + seed + ") ...");
        scenario.run();
        SimulationMetrics metrics = scenario.getMetrics();
        metrics.setCbRede(CbRedeMedida.medir(scenario.getNetwork().getAllNodes(), metrics)); // HS-CB-fase2
        if (metrics.getBlockCount() == 0) {
            throw new IllegalStateException("HOTSTUFF finalizou 0 blocos em " + duration
                    + " s: Tb e C_B nao medidos (nao serao registrados como zero)");
        }
        if (metrics.getConfirmationCount() == 0) {
            throw new IllegalStateException("HOTSTUFF sem transacao confirmada: coluna Tt (Tt_avg) nao medida"
                    + " (nao sera registrada como zero)");
        }
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
            list.add(normalizeProtocol(s));
        }
        return list;
    }
}
