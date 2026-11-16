import jabs.config.ByzantineConfig;
import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.log.AbstractLogger;
import jabs.network.node.nodes.Node;
import jabs.scenario.HotStuffLANScenario;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.HotStuffViewTimeoutEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * OBSERVADOR EXTERNO do piloto de timeout do HotStuff (experimentos/piloto-timeout-hotstuff). NAO faz parte do
 * simulador: nao esta em src/, nao altera nenhuma classe do JABS. Usa so a API publica ja compilada
 * (HotStuffLANScenario, HotStuffConsensusConfig, ByzantineConfig, HotStuff#getTimeoutAdvances etc.).
 *
 * Por que existe: o CSV por bloco do MVPComparison traz so o PRIMEIRO no a finalizar cada altura e nao traz o contador
 * de timeouts de visao disparados, que e o criterio de aceite do ADR-0015 Q12-A (zero timeouts sem falha). Este
 * programa monta o MESMO cenario que MVPComparison.runHotStuffScenario (mesma semente, mesmo config, mesmo logger de
 * CSV por bloco) e acrescenta um logger passivo que anota a finalizacao local de CADA no (duracao de visao por
 * replica = o que o timer da replica compara) e le os contadores ao final. Equivalencia com o MVPComparison e
 * conferida celula a celula pelo script de analise (CSV por bloco identico, coluna BlockHashCode excluida).
 *
 * Uso: --run-seed=S --validators=N --duration=D --out-dir=DIR --csv=ARQ.csv --hs-*=... [--attack=X --byzantine-count=F]
 *      --listar-byz --run-seed=S --validators=N --byzantine-count=F   (so imprime os ids sorteados e sai)
 * O logger nao usa colecao hash nem hashCode: so arrays indexados por id de no e altura.
 */
public class ObservadorVal {

    static final class Observador extends AbstractLogger {
        final int n;
        int hCap = 1024;
        double[] fMin = new double[hCap], fMax = new double[hCap], rMax = new double[hCap], rMin = new double[hCap];
        int[] rMaxNode = new int[hCap], nFinal = new int[hCap];
        final double[] lastF;
        final int[] lastH;
        int maxH = 0;
        long saltos = 0;       // finalizacoes de mais de uma altura de uma vez por um no (ancestrais)
        long finalizacoes = 0;

        Observador(int n) {
            this.n = n;
            this.lastF = new double[n];
            this.lastH = new int[n];
            Arrays.fill(fMin, Double.POSITIVE_INFINITY);
            Arrays.fill(rMin, Double.POSITIVE_INFINITY);
        }

        void grow(int h) {
            if (h < hCap) return;
            int old = hCap;
            while (hCap <= h) hCap *= 2;
            fMin = Arrays.copyOf(fMin, hCap); fMax = Arrays.copyOf(fMax, hCap);
            rMax = Arrays.copyOf(rMax, hCap); rMin = Arrays.copyOf(rMin, hCap);
            rMaxNode = Arrays.copyOf(rMaxNode, hCap); nFinal = Arrays.copyOf(nFinal, hCap);
            Arrays.fill(fMin, old, hCap, Double.POSITIVE_INFINITY);
            Arrays.fill(rMin, old, hCap, Double.POSITIVE_INFINITY);
        }

        // instante de cada troca de visao por TIMEOUT (soma dos contadores por no sobe): [tempo, incremento]
        java.util.List<double[]> trocas = new java.util.ArrayList<>();
        long somaAnterior = 0;
        java.util.List<Object> nos;

        @SuppressWarnings("rawtypes")
        long somaTimeoutAdvances() {
            if (nos == null) nos = new java.util.ArrayList<Object>(scenario.getNetwork().getAllNodes());
            long s = 0;
            for (Object o : nos) {
                s += ((HotStuff) ((jabs.network.node.nodes.PeerBlockchainNode) o).getConsensusAlgorithm()).getTimeoutAdvances();
            }
            return s;
        }

        @Override public void initialLog() { }
        @Override public void logBeforeEachEvent(Event event) { }

        @Override
        public void logAfterEachEvent(Event event) {
            if (event instanceof HotStuffViewTimeoutEvent) {
                long s = somaTimeoutAdvances();
                if (s != somaAnterior) {
                    trocas.add(new double[]{scenario.getSimulator().getSimulationTime(), s - somaAnterior});
                    somaAnterior = s;
                }
                return;
            }
            if (!(event instanceof BlockFinalizationEvent)) return;
            BlockFinalizationEvent ev = (BlockFinalizationEvent) event;
            int id = ev.getNode().nodeID;
            int h = ev.getBlock().getHeight();
            double t = scenario.getSimulator().getSimulationTime();
            grow(h);
            finalizacoes++;
            if (h != lastH[id] + 1) saltos++;
            double r = t - lastF[id];      // duracao desde a ultima finalizacao local (= entrada na visao corrente)
            lastF[id] = t;
            lastH[id] = h;
            if (t < fMin[h]) fMin[h] = t;
            if (nFinal[h] == 0 || t > fMax[h]) fMax[h] = t;
            if (nFinal[h] == 0 || r > rMax[h]) { rMax[h] = r; rMaxNode[h] = id; }
            if (r < rMin[h]) rMin[h] = r;
            nFinal[h]++;
            if (h > maxH) maxH = h;
        }

        @Override public void finalLog() { }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                int i = a.indexOf('=');
                if (i < 0) m.put(a.substring(2), "true");
                else m.put(a.substring(2, i), a.substring(i + 1));
            }
        }
        return m;
    }

    static String req(Map<String, String> m, String k) {
        String v = m.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("flag obrigatoria sem padrao: --" + k);
        return v;
    }

    /**
     * EMENDA 1b (V-A, contagem de mensagens/autenticadores): pacotes e bytes entregues pela rede simulada por altura,
     * somados sobre TODOS os nos receptores, na janela de regime de CbRedeMedida (2..hmax-2; hmax = maior altura finalizada
     * por todos os nos). So le o ledger publico (NetworkHeightLedger); nao altera a simulacao.
     */
    @SuppressWarnings({"rawtypes"})
    static void escreverPacotesPorAltura(HotStuffLANScenario scenario, Path outDir) throws IOException {
        java.util.List<jabs.metrics.NetworkHeightLedger> ledgers = new java.util.ArrayList<>();
        for (Object o : scenario.getNetwork().getAllNodes()) {
            ledgers.add(((HotStuff) ((jabs.network.node.nodes.PeerBlockchainNode) o).getConsensusAlgorithm()).getHeightLedger());
        }
        long hmax = 0;
        for (Long h : ledgers.get(0).heights().keySet()) {
            boolean todos = true;
            for (jabs.metrics.NetworkHeightLedger l : ledgers) {
                jabs.metrics.NetworkHeightLedger.Cell c = l.heights().get(h);
                if (c == null || !c.isFinalized()) { todos = false; break; }
            }
            if (todos && h > hmax) hmax = h;
        }
        long lo = jabs.metrics.CbRedeMedida.FIRST_REGIME_HEIGHT;
        long hi = hmax - jabs.metrics.CbRedeMedida.LAST_IN_FLIGHT;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(outDir.resolve("pacotes-por-altura.csv.gz"))), StandardCharsets.UTF_8))) {
            w.write("# janela de regime: alturas " + lo + ".." + hi + " (hmax=" + hmax + "); soma sobre os " + ledgers.size()
                    + " nos receptores; pacotes entregues pela rede simulada, sem autoentrega\n");
            w.write("height,pacotes,bytes_total,bytes_consenso\n");
            for (long h = lo; h <= hi; h++) {
                long p = 0, bt = 0, bc = 0;
                for (jabs.metrics.NetworkHeightLedger l : ledgers) {
                    jabs.metrics.NetworkHeightLedger.Cell c = l.heights().get(h);
                    if (c != null) { p += c.getPackets(); bt += c.getTotalBytes(); bc += c.getConsensusBytes(); }
                }
                w.write(h + "," + p + "," + bt + "," + bc + "\n");
            }
        }
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    public static void main(String[] args) throws IOException {
        Map<String, String> cli = parse(args);
        int n = Integer.parseInt(req(cli, "validators"));
        long runSeed = cli.containsKey("listar-byz-faixa") ? 0L : Long.parseLong(req(cli, "run-seed"));

        if (cli.containsKey("listar-byz-faixa")) {
            // --listar-byz-faixa=ini:fim --run-seed-base=B : para cada replicacao s em [ini,fim], runSeed = B + s
            String[] fx = cli.get("listar-byz-faixa").split(":");
            long base = Long.parseLong(req(cli, "run-seed-base"));
            int f = Integer.parseInt(req(cli, "byzantine-count"));
            for (int s = Integer.parseInt(fx[0]); s <= Integer.parseInt(fx[1]); s++) {
                ByzantineConfig b = ByzantineConfig.withExactCount(n, f, "EQUIVOCATION", base + s);
                System.out.println(s + " " + new TreeSet<>(b.getByzantineValidatorIds()));
            }
            return;
        }
        if (cli.containsKey("listar-byz")) {
            int f = Integer.parseInt(req(cli, "byzantine-count"));
            ByzantineConfig b = ByzantineConfig.withExactCount(n, f, "EQUIVOCATION", runSeed);
            System.out.println(new TreeSet<>(b.getByzantineValidatorIds()));
            return;
        }

        double duration = Double.parseDouble(req(cli, "duration"));
        Path outDir = Paths.get(req(cli, "out-dir"));
        Files.createDirectories(outDir);
        Path csv = outDir.resolve(req(cli, "csv"));

        HotStuffConsensusConfig config = HotStuffConsensusConfig.builder()
                .viewTimeoutSeconds(Double.parseDouble(req(cli, "hs-view-timeout")))
                .maxFutureViewHorizon(Long.parseLong(req(cli, "hs-max-future-view-horizon")))
                .batchSize(Integer.parseInt(req(cli, "hs-batch-size")))
                .txSizeBytes(Integer.parseInt(req(cli, "hs-tx-size")))
                .individualSignatureSizeBytes(Integer.parseInt(req(cli, "hs-individual-signature-size")))
                .aggregatedSignatureSizeBytes(Integer.parseInt(req(cli, "hs-aggregated-signature-size")))
                .aggregatedShareSizeBytes(Integer.parseInt(req(cli, "hs-aggregated-share-size")))
                .messageOverheadBytes(Integer.parseInt(req(cli, "hs-message-overhead")))
                .signatureMode(HotStuffConsensusConfig.SignatureMode.valueOf(req(cli, "hs-signature-mode")))
                .leaderPolicy(HotStuffConsensusConfig.LeaderPolicy.valueOf(req(cli, "hs-leader-policy")))
                .voterEncoding(HotStuffConsensusConfig.VoterEncoding.valueOf(req(cli, "hs-voter-encoding")))
                .build();
        config.validateFor(n);

        ByzantineConfig byz = null;
        String attack = cli.get("attack");
        if (attack != null && !attack.equalsIgnoreCase("none")) {
            int f = Integer.parseInt(req(cli, "byzantine-count"));
            byz = ByzantineConfig.withExactCount(n, f, attack.toUpperCase(), runSeed);
        }

        // Mesma construcao de MVPComparison.runHotStuffScenario (nome, ordem dos loggers, mesmo CSV por bloco).
        HotStuffLANScenario scenario = byz == null
                ? new HotStuffLANScenario("HotStuff - " + n + " validators", runSeed, n, duration, config)
                : new HotStuffLANScenario("HotStuff - " + n + " validators, " + byz.getAttackType()
                        + " f=" + byz.getByzantineCount(), runSeed, n, duration, config, java.util.Set.of(), byz);
        scenario.addMetricsLogger(csv.toString());
        Observador obs = new Observador(n);
        scenario.AddNewLogger(obs);
        long t0 = System.nanoTime();
        scenario.run();
        double parede = (System.nanoTime() - t0) / 1e9;

        // contadores finais, por no
        StringBuilder timeoutAdv = new StringBuilder("["), crashed = new StringBuilder("["), views = new StringBuilder("[");
        long somaTimeoutAdv = 0, somaStale = 0, somaDiscards = 0, somaCrashed = 0, eqProp = 0, eqVotes = 0, unEq = 0;
        long maxView = 0;
        int i = 0;
        for (Object o : scenario.getNetwork().getAllNodes()) {
            Node node = (Node) o;
            @SuppressWarnings({"rawtypes"})
            HotStuff hs = (HotStuff) ((jabs.network.node.nodes.PeerBlockchainNode) node).getConsensusAlgorithm();
            long ta = hs.getTimeoutAdvances();
            somaTimeoutAdv += ta;
            somaCrashed += hs.getCrashedTimeouts();
            somaStale += hs.getDiscardCount(DiscardReason.STALE_VIEW_TIMEOUT);
            somaDiscards += hs.getTotalDiscards();
            eqProp += hs.getEquivocatedProposals();
            eqVotes += hs.getEquivocatedVotes();
            unEq += hs.getUnequivocatedLeaderPhases();
            maxView = Math.max(maxView, hs.getCurrentView());
            if (i++ > 0) { timeoutAdv.append(","); crashed.append(","); views.append(","); }
            timeoutAdv.append(ta); crashed.append(hs.getCrashedTimeouts()); views.append(hs.getCurrentView());
        }
        timeoutAdv.append("]"); crashed.append("]"); views.append("]");

        escreverPacotesPorAltura(scenario, outDir);   // EMENDA 1b
        // por altura (CSV gz): so alturas finalizadas por pelo menos um no
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(outDir.resolve("observador-por-altura.csv.gz"))), StandardCharsets.UTF_8))) {
            w.write("height,Fmin_s,Fmax_s,Rmax_s,Rmax_node,Rmin_s,n_finalizaram\n");
            for (int h = 1; h <= obs.maxH; h++) {
                if (obs.nFinal[h] == 0) continue;
                w.write(h + "," + obs.fMin[h] + "," + obs.fMax[h] + "," + obs.rMax[h] + "," + obs.rMaxNode[h] + ","
                        + obs.rMin[h] + "," + obs.nFinal[h] + "\n");
            }
        }
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(outDir.resolve("observador-trocas-por-timeout.csv.gz"))), StandardCharsets.UTF_8))) {
            w.write("time_s,replicas_que_avancaram_por_timeout\n");
            for (double[] x : obs.trocas) w.write(x[0] + "," + (long) x[1] + "\n");
        }
        StringBuilder j = new StringBuilder("{\n");
        j.append(" \"n\": ").append(n).append(",\n");
        j.append(" \"run_seed\": ").append(runSeed).append(",\n");
        j.append(" \"duracao_s\": ").append(duration).append(",\n");
        j.append(" \"hs_view_timeout_s\": ").append(config.getViewTimeoutSeconds()).append(",\n");
        j.append(" \"leader_policy\": \"").append(config.getLeaderPolicy()).append("\",\n");
        j.append(" \"batch\": ").append(config.getBatchSize()).append(",\n");
        j.append(" \"attack\": ").append(byz == null ? "null" : "\"" + esc(byz.getAttackType()) + "\"").append(",\n");
        j.append(" \"byzantine_ids\": ").append(byz == null ? "[]" : new TreeSet<>(byz.getByzantineValidatorIds()).toString()).append(",\n");
        j.append(" \"altura_maxima_finalizada\": ").append(obs.maxH).append(",\n");
        j.append(" \"finalizacoes_locais_total\": ").append(obs.finalizacoes).append(",\n");
        j.append(" \"finalizacoes_com_salto_de_altura\": ").append(obs.saltos).append(",\n");
        j.append(" \"timeouts_disparados_total\": ").append(somaTimeoutAdv).append(",\n");
        j.append(" \"timeouts_disparados_por_no\": ").append(timeoutAdv).append(",\n");
        j.append(" \"timeouts_ignorados_no_em_crash_total\": ").append(somaCrashed).append(",\n");
        j.append(" \"timeouts_obsoletos_STALE_total\": ").append(somaStale).append(",\n");
        j.append(" \"descartes_total\": ").append(somaDiscards).append(",\n");
        j.append(" \"visao_corrente_final_por_no\": ").append(views).append(",\n");
        j.append(" \"visao_corrente_maxima\": ").append(maxView).append(",\n");
        j.append(" \"propostas_equivocadas\": ").append(eqProp).append(",\n");
        j.append(" \"votos_equivocados\": ").append(eqVotes).append(",\n");
        j.append(" \"fases_de_lider_nao_equivocadas\": ").append(unEq).append(",\n");
        j.append(" \"tempo_parede_s\": ").append(parede).append("\n}\n");
        Files.writeString(outDir.resolve("observador-resumo.json"), j.toString());
        System.out.println("observador ok: n=" + n + " seed=" + runSeed + " altura_max=" + obs.maxH
                + " timeouts_disparados=" + somaTimeoutAdv + " visao_max=" + maxView);
    }
}
