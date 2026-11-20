import jabs.consensus.algorithm.PBFT;
import jabs.consensus.config.PBFTConsensusConfig;
import jabs.log.AbstractLogger;
import jabs.metrics.CbRedeMedida;
import jabs.metrics.NetworkHeightLedger;
import jabs.scenario.PBFTLANScenario;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * OBSERVADOR EXTERNO do V-ABC do PBFT puro (experimentos/validacao/pbft). RASCUNHO, derivado de
 * experimentos/validacao/tendermint/harness/ObservadorTm.java. NAO faz parte do simulador: nao esta em src/ e nao altera nenhuma
 * classe do JABS; usa so a API publica ja compilada.
 *
 * POR QUE EXISTE: o MVPComparison exporta so a soma da rede em BYTES (mvp-results-cb.csv); nao exporta PACOTES por altura nem a
 * soma por no. Este programa le o NetworkHeightLedger publico do PBFT (a mesma fonte do CbRedeMedida) e grava, na mesma janela de
 * regime (alturas FIRST_REGIME_HEIGHT..hmax-LAST_IN_FLIGHT; hmax = maior altura finalizada por TODOS os nos):
 *   pacotes-por-altura.csv.gz   height,pacotes,bytes_total,bytes_consenso   (soma sobre os n nos receptores)
 *   por-no-por-altura.csv.gz    height,node,lider,pacotes,bytes_total,bytes_consenso
 *   observador-resumo.json      contadores de integridade (uma decisao por altura, PRE_PREPARE fora do turno, descartes, sem-altura)
 * Replica MVPComparison.runPbftScenario: mesmo nome de cenario, mesma semente, mesmo teto e politica, mesmo logger de CSV por
 * bloco. A equivalencia e conferida celula a celula pelo MD5 do CSV por bloco (coluna BlockHashCode excluida) contra a JVM do
 * MVPComparison. O logger passivo usa so arrays indexados por id de no e altura (sem colecao hash).
 *
 * Uso: --run-seed=S --validators=N --duration=D --out-dir=DIR --csv=ARQ.csv --pbft-max-future-horizon=H --pbft-leader-policy=STABLE|ROTATING
 */
public class ObservadorPbft {

    static final class Observador extends AbstractLogger {
        final int n;
        int hCap = 1024;
        int[][] porNoPorAltura;     // [no][altura] = quantas finalizacoes ESTE no registrou nesta altura (esperado: 0 ou 1)
        int maxH = 0;
        long finalizacoes = 0;
        int maxFinalizacoesPorNoPorAltura = 0;

        Observador(int n) {
            this.n = n;
            this.porNoPorAltura = new int[n][hCap];
        }

        void grow(int h) {
            if (h < hCap) return;
            while (hCap <= h) hCap *= 2;
            for (int i = 0; i < n; i++) porNoPorAltura[i] = java.util.Arrays.copyOf(porNoPorAltura[i], hCap);
        }

        @Override public void initialLog() { }
        @Override public void logBeforeEachEvent(Event event) { }

        @Override
        public void logAfterEachEvent(Event event) {
            if (!(event instanceof BlockFinalizationEvent)) return;
            BlockFinalizationEvent ev = (BlockFinalizationEvent) event;
            int id = ev.getNode().nodeID;
            int h = ev.getBlock().getHeight();
            grow(h);
            finalizacoes++;
            int c = ++porNoPorAltura[id][h];
            if (c > maxFinalizacoesPorNoPorAltura) maxFinalizacoesPorNoPorAltura = c;
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

    static Writer gz(Path p) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(p)), StandardCharsets.UTF_8));
    }

    @SuppressWarnings({"rawtypes"})
    static PBFT alg(Object node) {
        return (PBFT) ((jabs.network.node.nodes.PeerBlockchainNode) node).getConsensusAlgorithm();
    }

    @SuppressWarnings({"rawtypes"})
    static void escreverPorAltura(PBFTLANScenario scenario, Path outDir, int n, int lider) throws IOException {
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
                    + " nos receptores; pacotes entregues pela rede simulada, sem autoentrega; trafego alem do teto de retencao vai ao contador sem altura\n");
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
            w.write("# mesma janela; lider = 1 no no que PROPOE a altura (STABLE: o no 0; ROTATING: (height-1) mod n); por no receptor\n");
            w.write("height,node,lider,pacotes,bytes_total,bytes_consenso\n");
            for (long h = lo; h <= hi; h++) {
                for (int id = 0; id < ledgers.size(); id++) {
                    NetworkHeightLedger.Cell c = ledgers.get(id).heights().get(h);
                    long p = c == null ? 0 : c.getPackets(), bt = c == null ? 0 : c.getTotalBytes(), bc = c == null ? 0 : c.getConsensusBytes();
                    int ehLider = id == lider ? 1 : 0;
                    w.write(h + "," + id + "," + ehLider + "," + p + "," + bt + "," + bc + "\n");
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> cli = parse(args);
        int n = Integer.parseInt(req(cli, "validators"));
        long runSeed = Long.parseLong(req(cli, "run-seed"));
        double duration = Double.parseDouble(req(cli, "duration"));
        Path outDir = Paths.get(req(cli, "out-dir"));
        Files.createDirectories(outDir);
        Path csv = outDir.resolve(req(cli, "csv"));
        long horizonte = Long.parseLong(req(cli, "pbft-max-future-horizon"));
        PBFTConsensusConfig.LeaderPolicy politica = PBFTConsensusConfig.LeaderPolicy.fromFlag(req(cli, "pbft-leader-policy"));
        if (n < 4) throw new IllegalArgumentException("PBFT puro exige n >= 4: n=" + n);

        PBFTLANScenario scenario = new PBFTLANScenario("PBFT - " + n + " validators", runSeed, n, duration);
        scenario.setMaxFutureHorizon(horizonte);
        scenario.setLeaderPolicy(politica);
        scenario.addMetricsLogger(csv.toString());
        Observador obs = new Observador(n);
        scenario.AddNewLogger(obs);
        long t0 = System.nanoTime();
        scenario.run();
        double parede = (System.nanoTime() - t0) / 1e9;

        long rejeitados = 0, alemTeto = 0, semAlturaB = 0, semAlturaP = 0;
        for (Object o : scenario.getNetwork().getAllNodes()) {
            PBFT p = alg(o);
            rejeitados += p.getRejectedPrePrepareNotPrimary();
            alemTeto += p.getDiscardedBeyondHorizon();
            semAlturaB += p.getHeightLedger().getUnattributedBytes();
            semAlturaP += p.getHeightLedger().getUnattributedPackets();
        }
        int lider = politica == PBFTConsensusConfig.LeaderPolicy.STABLE ? PBFTConsensusConfig.STABLE_LEADER_ID : -1;
        escreverPorAltura(scenario, outDir, n, lider);

        StringBuilder j = new StringBuilder("{\n");
        j.append(" \"n\": ").append(n).append(",\n");
        j.append(" \"run_seed\": ").append(runSeed).append(",\n");
        j.append(" \"duracao_s\": ").append(duration).append(",\n");
        j.append(" \"pbft_max_future_horizon\": ").append(horizonte).append(",\n");
        j.append(" \"pbft_leader_policy\": \"").append(politica.name()).append("\",\n");
        j.append(" \"altura_maxima_finalizada\": ").append(obs.maxH).append(",\n");
        j.append(" \"finalizacoes_locais_total\": ").append(obs.finalizacoes).append(",\n");
        j.append(" \"max_finalizacoes_do_mesmo_no_na_mesma_altura\": ").append(obs.maxFinalizacoesPorNoPorAltura).append(",\n");
        j.append(" \"pre_prepare_rejeitados_nao_primario_total\": ").append(rejeitados).append(",\n");
        j.append(" \"votos_descartados_alem_do_teto_total\": ").append(alemTeto).append(",\n");
        j.append(" \"sem_altura_bytes_total\": ").append(semAlturaB).append(",\n");
        j.append(" \"sem_altura_pacotes_total\": ").append(semAlturaP).append(",\n");
        j.append(" \"tempo_parede_s\": ").append(parede).append("\n}\n");
        Files.writeString(outDir.resolve("observador-resumo.json"), j.toString());
        System.out.println("observador ok: n=" + n + " seed=" + runSeed + " altura_max=" + obs.maxH);
    }
}
