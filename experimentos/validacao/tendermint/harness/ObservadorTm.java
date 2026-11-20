import jabs.config.ByzantineConfig;
import jabs.consensus.algorithm.Tendermint;
import jabs.consensus.algorithm.tendermint.TendermintDiscardReason;
import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.log.AbstractLogger;
import jabs.metrics.CbRedeMedida;
import jabs.metrics.NetworkHeightLedger;
import jabs.scenario.TendermintLANScenario;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.TendermintTimeoutEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * OBSERVADOR EXTERNO do V-ABC do Tendermint (experimentos/validacao/tendermint). RASCUNHO: derivado de
 * experimentos/validacao/hotstuff/harness/ObservadorVal.java (SHA-256 21aa3f9e...), adaptado ao Tendermint. NAO faz parte do
 * simulador: nao esta em src/, nao altera nenhuma classe do JABS. Usa so API publica ja compilada.
 *
 * POR QUE EXISTE (tres motivos):
 *  1. Contar timeouts DISPARADOS (criterio A1': zero sem falha). O Tendermint nao expoe esse contador: o observador conta os
 *     TendermintTimeoutEvent executados e subtrai os STALE_TIMEOUT que o nucleo descarta (TendermintCore#discardCount). O que sobra
 *     e timeout que AGIU (prevote nil, precommit nil ou troca de round). Num round sem falha todo timeout e obsoleto.
 *  2. Pacotes e bytes POR ALTURA e POR NO (NetworkHeightLedger, API publica): V-A agregado e V-A por no (proponente x validador)
 *     saem das MESMAS celulas, sem o LinkLoadRecorder.
 *  3. V-B: o MVPComparison NAO le --attack para TENDERMINT (so HOTSTUFF; ver MVPComparison.attackForMain: "ignorada (so o HOTSTUFF a
 *     le)") e o ByzantineSweep nao tem Tendermint. O EQUIVOCATION do Tendermint so e alcancavel pela API Java
 *     (TendermintLANScenario com ByzantineConfig, como em TendermintEquivocationTest). Por isso, em V-B, ESTE programa e o UNICO
 *     instrumento (nao ha JVM do MVPComparison para conferir): limite declarado no PREREGISTRO.
 *
 * Replica MVPComparison.runTendermintScenario: mesmo nome de cenario, mesma semente, mesmo config, mesmo logger de CSV por bloco.
 * Em V-A a equivalencia e conferida celula a celula pelo MD5 do CSV por bloco (coluna BlockHashCode excluida) contra a JVM do
 * MVPComparison. O logger passivo nao usa colecao hash nem hashCode: so arrays indexados por id de no e altura.
 *
 * Uso: --run-seed=S --validators=N --duration=D --out-dir=DIR --csv=ARQ.csv --tm-*=... [--attack=EQUIVOCATION --byzantine-count=F
 *      [--allow-beyond-bft-threshold=true]]
 *      --listar-byz-faixa=ini:fim --run-seed-base=B --byzantine-count=F --validators=N   (so sorteia e imprime; nao constroi cenario)
 */
public class ObservadorTm {

    static final class Observador extends AbstractLogger {
        final int n;
        int hCap = 1024;
        double[] fMin = new double[hCap], fMax = new double[hCap];
        int[] nFinal = new int[hCap];
        final double[] lastF;
        final int[] lastH;
        int maxH = 0;
        long saltos = 0;
        long finalizacoes = 0;
        long timeoutEvents = 0;
        long[] timeoutPorTipo = new long[3];
        int maxLead = 0;   // max (maior altura finalizada por alguem - altura que ESTE no acaba de finalizar), no instante da finalizacao

        Observador(int n) {
            this.n = n;
            this.lastF = new double[n];
            this.lastH = new int[n];
            Arrays.fill(fMin, Double.POSITIVE_INFINITY);
        }

        void grow(int h) {
            if (h < hCap) return;
            int old = hCap;
            while (hCap <= h) hCap *= 2;
            fMin = Arrays.copyOf(fMin, hCap);
            fMax = Arrays.copyOf(fMax, hCap);
            nFinal = Arrays.copyOf(nFinal, hCap);
            Arrays.fill(fMin, old, hCap, Double.POSITIVE_INFINITY);
        }

        @Override public void initialLog() { }
        @Override public void logBeforeEachEvent(Event event) { }

        @Override
        public void logAfterEachEvent(Event event) {
            if (event instanceof TendermintTimeoutEvent) {
                timeoutEvents++;
                timeoutPorTipo[((TendermintTimeoutEvent) event).getKind().ordinal()]++;
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
            lastF[id] = t;
            lastH[id] = h;
            if (t < fMin[h]) fMin[h] = t;
            if (nFinal[h] == 0 || t > fMax[h]) fMax[h] = t;
            nFinal[h]++;
            if (h > maxH) maxH = h;
            if (maxH - h > maxLead) maxLead = maxH - h;
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

    static Writer gz(Path p) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(p)), StandardCharsets.UTF_8));
    }

    @SuppressWarnings({"rawtypes"})
    static Tendermint alg(Object node) {
        return (Tendermint) ((jabs.network.node.nodes.PeerBlockchainNode) node).getConsensusAlgorithm();
    }

    /**
     * Pacotes e bytes entregues pela rede simulada por altura (agregado) e por no, na janela de regime de CbRedeMedida
     * (2..hmax-2; hmax = maior altura finalizada por TODOS os nos). So le o ledger publico; nao altera a simulacao.
     * Altura = block.getHeight() (1-based). O proponente do round 0 da altura de bloco h e o no (h-1) mod n (TendermintCore.roundRobin).
     */
    @SuppressWarnings({"rawtypes"})
    static void escreverPorAltura(TendermintLANScenario scenario, Path outDir, int n) throws IOException {
        List<NetworkHeightLedger> ledgers = new ArrayList<>();
        for (Object o : scenario.getNetwork().getAllNodes()) ledgers.add(alg(o).getHeightLedger());
        long hmax = 0;
        for (Long h : ledgers.get(0).heights().keySet()) {
            boolean todos = true;
            for (NetworkHeightLedger l : ledgers) {
                NetworkHeightLedger.Cell c = l.heights().get(h);
                if (c == null || !c.isFinalized()) { todos = false; break; }
            }
            if (todos && h > hmax) hmax = h;
        }
        long lo = CbRedeMedida.FIRST_REGIME_HEIGHT;
        long hi = hmax - CbRedeMedida.LAST_IN_FLIGHT;
        try (Writer w = gz(outDir.resolve("pacotes-por-altura.csv.gz"))) {
            w.write("# janela de regime: alturas " + lo + ".." + hi + " (hmax=" + hmax + "); soma sobre os " + ledgers.size()
                    + " nos receptores; pacotes entregues pela rede simulada, sem autoentrega; inclui voto nil? NAO (nil vai ao contador sem altura)\n");
            w.write("height,pacotes,bytes_total,bytes_consenso\n");
            for (long h = lo; h <= hi; h++) {
                long p = 0, bt = 0, bc = 0;
                for (NetworkHeightLedger l : ledgers) {
                    NetworkHeightLedger.Cell c = l.heights().get(h);
                    if (c != null) { p += c.getPackets(); bt += c.getTotalBytes(); bc += c.getConsensusBytes(); }
                }
                w.write(h + "," + p + "," + bt + "," + bc + "\n");
            }
        }
        try (Writer w = gz(outDir.resolve("por-no-por-altura.csv.gz"))) {
            w.write("# mesma janela; proponente_round0 = (height-1) mod n; por no receptor\n");
            w.write("height,node,proponente_round0,pacotes,bytes_total,bytes_consenso\n");
            for (long h = lo; h <= hi; h++) {
                int prop = (int) Math.floorMod(h - 1, (long) n);
                for (int id = 0; id < ledgers.size(); id++) {
                    NetworkHeightLedger.Cell c = ledgers.get(id).heights().get(h);
                    long p = c == null ? 0 : c.getPackets(), bt = c == null ? 0 : c.getTotalBytes(), bc = c == null ? 0 : c.getConsensusBytes();
                    w.write(h + "," + id + "," + (id == prop ? 1 : 0) + "," + p + "," + bt + "," + bc + "\n");
                }
            }
        }
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    public static void main(String[] args) throws IOException {
        Map<String, String> cli = parse(args);
        int n = Integer.parseInt(req(cli, "validators"));

        if (cli.containsKey("listar-byz-faixa")) {
            String[] fx = cli.get("listar-byz-faixa").split(":");
            long base = Long.parseLong(req(cli, "run-seed-base"));
            int f = Integer.parseInt(req(cli, "byzantine-count"));
            for (int s = Integer.parseInt(fx[0]); s <= Integer.parseInt(fx[1]); s++) {
                ByzantineConfig b = ByzantineConfig.withExactCount(n, f, "EQUIVOCATION", base + s);
                System.out.println(s + " " + new TreeSet<>(b.getByzantineValidatorIds()));
            }
            return;
        }
        long runSeed = Long.parseLong(req(cli, "run-seed"));
        double duration = Double.parseDouble(req(cli, "duration"));
        Path outDir = Paths.get(req(cli, "out-dir"));
        Files.createDirectories(outDir);
        Path csv = outDir.resolve(req(cli, "csv"));

        // MESMA montagem de MVPComparison.parseTendermintConfigIfRequested (12 flags --tm-*, todas obrigatorias e sem padrao).
        TendermintTimeouts timeouts = new TendermintTimeouts(
                Double.parseDouble(req(cli, "tm-propose-timeout")), Double.parseDouble(req(cli, "tm-propose-delta")),
                Double.parseDouble(req(cli, "tm-prevote-timeout")), Double.parseDouble(req(cli, "tm-prevote-delta")),
                Double.parseDouble(req(cli, "tm-precommit-timeout")), Double.parseDouble(req(cli, "tm-precommit-delta")));
        TendermintConsensusConfig config = TendermintConsensusConfig.builder()
                .timeouts(timeouts)
                .batchSize(Integer.parseInt(req(cli, "tm-batch-size")))
                .txSizeBytes(Integer.parseInt(req(cli, "tm-tx-size")))
                .messageOverheadBytes(Integer.parseInt(req(cli, "tm-message-overhead")))
                .signatureSizeBytes(Integer.parseInt(req(cli, "tm-signature-size")))
                .proposerSignatureSizeBytes(Integer.parseInt(req(cli, "tm-proposer-signature-size")))
                .maxFutureHorizon(Long.parseLong(req(cli, "tm-max-future-horizon"))).build();
        config.validateFor(n);

        ByzantineConfig byz = null;
        String attack = cli.get("attack");
        if (attack != null && !attack.equalsIgnoreCase("none")) {
            if (!attack.equalsIgnoreCase("EQUIVOCATION")) {
                throw new IllegalArgumentException("o Tendermint so implementa EQUIVOCATION (recebido " + attack + ")");
            }
            int f = Integer.parseInt(req(cli, "byzantine-count"));
            if (f < 1 || f > n) throw new IllegalArgumentException("--byzantine-count fora de [1, n]: " + f);
            boolean allow = "true".equals(cli.get("allow-beyond-bft-threshold"));
            // Mesma regra de MVPComparison.resolveByzantineCount (o MVPComparison NAO a aplica ao Tendermint): f > floor((n-1)/3) so
            // com a flag explicita. Aritmetica inteira LOCAL: ByzantineConfig.beyondBftThreshold/bftLimit NAO existem em bc07355
            // (so na arvore de trabalho, BYZ-UNICA) e o observador compila contra o commit pinado, nao contra a arvore suja.
            if (f > Math.floorDiv(n - 1, 3) && !allow) {
                throw new IllegalArgumentException("contagem bizantina fora da faixa BFT (f > floor((n-1)/3)): n=" + n + " f=" + f
                        + ": exige --allow-beyond-bft-threshold=true");
            }
            byz = ByzantineConfig.withExactCount(n, f, "EQUIVOCATION", runSeed);
        } else if (cli.containsKey("byzantine-count") && !"0".equals(cli.get("byzantine-count"))) {
            throw new IllegalArgumentException("--byzantine-count sem --attack seria ignorado em silencio");
        }

        TendermintLANScenario scenario = byz == null
                ? new TendermintLANScenario("Tendermint - " + n + " validators", runSeed, n, duration, config)
                : new TendermintLANScenario("Tendermint - " + n + " validators", runSeed, n, duration, config, byz);
        scenario.addMetricsLogger(csv.toString());
        Observador obs = new Observador(n);
        scenario.AddNewLogger(obs);
        long t0 = System.nanoTime();
        scenario.run();
        double parede = (System.nanoTime() - t0) / 1e9;

        long stale = 0, discards = 0, invalid = 0, beyond = 0, eq = 0, unattrB = 0, unattrP = 0;
        int peakFuture = 0, maxRoundFinal = 0;
        StringBuilder rounds = new StringBuilder("["), heights = new StringBuilder("[");
        int i = 0;
        for (Object o : scenario.getNetwork().getAllNodes()) {
            Tendermint t = alg(o);
            stale += t.getCore().discardCount(TendermintDiscardReason.STALE_TIMEOUT);
            invalid += t.getCore().discardCount(TendermintDiscardReason.INVALID_VALUE);
            beyond += t.getCore().discardCount(TendermintDiscardReason.FUTURE_BEYOND_HORIZON);
            discards += t.getCore().totalDiscards();
            eq += t.getCore().equivocationsObserved();
            peakFuture = Math.max(peakFuture, t.getCore().peakFutureMessages());
            maxRoundFinal = Math.max(maxRoundFinal, t.getCore().round());
            unattrB += t.getHeightLedger().getUnattributedBytes();
            unattrP += t.getHeightLedger().getUnattributedPackets();
            if (i++ > 0) { rounds.append(","); heights.append(","); }
            rounds.append(t.getCore().round());
            heights.append(t.getCore().height());
        }
        rounds.append("]"); heights.append("]");
        long disparados = obs.timeoutEvents - stale;

        escreverPorAltura(scenario, outDir, n);
        try (Writer w = gz(outDir.resolve("observador-por-altura.csv.gz"))) {
            w.write("height,Fmin_s,Fmax_s,n_finalizaram\n");
            for (int h = 1; h <= obs.maxH; h++) {
                if (obs.nFinal[h] == 0) continue;
                w.write(h + "," + obs.fMin[h] + "," + obs.fMax[h] + "," + obs.nFinal[h] + "\n");
            }
        }
        StringBuilder j = new StringBuilder("{\n");
        j.append(" \"n\": ").append(n).append(",\n");
        j.append(" \"run_seed\": ").append(runSeed).append(",\n");
        j.append(" \"duracao_s\": ").append(duration).append(",\n");
        j.append(" \"tm_timeouts\": [").append(timeouts.proposeBaseS()).append(",").append(timeouts.proposeDeltaS()).append(",")
                .append(timeouts.prevoteBaseS()).append(",").append(timeouts.prevoteDeltaS()).append(",")
                .append(timeouts.precommitBaseS()).append(",").append(timeouts.precommitDeltaS()).append("],\n");
        j.append(" \"batch\": ").append(config.getBatchSize()).append(",\n");
        j.append(" \"attack\": ").append(byz == null ? "null" : "\"" + esc(byz.getAttackType()) + "\"").append(",\n");
        j.append(" \"byzantine_ids\": ").append(byz == null ? "[]" : new TreeSet<>(byz.getByzantineValidatorIds()).toString()).append(",\n");
        j.append(" \"altura_maxima_finalizada\": ").append(obs.maxH).append(",\n");
        j.append(" \"finalizacoes_locais_total\": ").append(obs.finalizacoes).append(",\n");
        j.append(" \"finalizacoes_com_salto_de_altura\": ").append(obs.saltos).append(",\n");
        j.append(" \"timeout_events_executados_total\": ").append(obs.timeoutEvents).append(",\n");
        j.append(" \"timeout_events_por_tipo_PROPOSE_PREVOTE_PRECOMMIT\": [").append(obs.timeoutPorTipo[0]).append(",")
                .append(obs.timeoutPorTipo[1]).append(",").append(obs.timeoutPorTipo[2]).append("],\n");
        j.append(" \"timeouts_obsoletos_STALE_total\": ").append(stale).append(",\n");
        j.append(" \"timeouts_disparados_total\": ").append(disparados).append(",\n");
        j.append(" \"round_final_por_no\": ").append(rounds).append(",\n");
        j.append(" \"round_final_maximo\": ").append(maxRoundFinal).append(",\n");
        j.append(" \"altura_do_nucleo_final_por_no\": ").append(heights).append(",\n");
        j.append(" \"pico_mensagens_de_altura_futura_max_por_no\": ").append(peakFuture).append(",\n");
        j.append(" \"max_lead_de_alturas\": ").append(obs.maxLead).append(",\n");
        j.append(" \"equivocacoes_observadas_total\": ").append(eq).append(",\n");
        j.append(" \"descartes_total\": ").append(discards).append(",\n");
        j.append(" \"descartes_INVALID_VALUE_total\": ").append(invalid).append(",\n");
        j.append(" \"descartes_FUTURE_BEYOND_HORIZON_total\": ").append(beyond).append(",\n");
        j.append(" \"sem_altura_bytes_total\": ").append(unattrB).append(",\n");
        j.append(" \"sem_altura_pacotes_total\": ").append(unattrP).append(",\n");
        j.append(" \"tempo_parede_s\": ").append(parede).append("\n}\n");
        Files.writeString(outDir.resolve("observador-resumo.json"), j.toString());
        System.out.println("observador ok: n=" + n + " seed=" + runSeed + " altura_max=" + obs.maxH
                + " timeouts_disparados=" + disparados + " round_final_max=" + maxRoundFinal);
    }
}
