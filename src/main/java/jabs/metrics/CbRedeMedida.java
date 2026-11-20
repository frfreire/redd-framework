package jabs.metrics;

import jabs.consensus.algorithm.AbstractChainBasedConsensus;
import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.algorithm.PBFT;
import jabs.consensus.algorithm.Tendermint;
import jabs.network.node.nodes.PeerBlockchainNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * Medida SOMA DA REDE por bloco (HS-CB-fase2, PARTE A; ADR-0015 D1) e estatisticas dela e do C_B do primeiro no a
 * finalizar, para HOTSTUFF e TENDERMINT.
 *
 * <p><b>Definicao (granularidade).</b> Para cada altura h: soma, sobre TODOS os nos receptores, dos bytes
 * ({@code packet.getSize()}) de cada pacote ENTREGUE pela rede simulada que nomeia h; sem corte local (inclui o que
 * chegou depois de o destino finalizar h); nunca autoentrega. Unidade: bytes. Granularidade: agregado de rede por
 * bloco. Duas versoes: <i>total</i> (inclui a carga do bloco) e <i>so-consenso</i> (sem a carga do bloco).
 * Coluna secundaria rotulada: C_B do primeiro no a finalizar a altura (a coluna historica {@code Cb_avg}), bytes
 * recebidos por esse no ate finalizar (com corte local), total.
 *
 * <p><b>Janela de regime.</b> A soma da rede so e completa para alturas que todos os n nos finalizaram e cujos
 * pacotes ja chegaram. {@code hmax} = maior altura finalizada por TODOS os nos; a janela e
 * {@code [FIRST_REGIME_HEIGHT, hmax - LAST_IN_FLIGHT]} (exclui a altura 1, que tem o arranque, e as
 * {@value #LAST_IN_FLIGHT} ultimas, ainda em voo no fim da corrida). A soma da rede e SEMPRE sobre a janela. O C_B
 * do primeiro no sai sobre TODAS as alturas finalizadas (a populacao do {@code Cb_avg} historico) e tambem sobre a
 * janela (coluna extra, para comparar a mesma populacao).
 *
 * <p><b>Percentil.</b> nearest-rank: ordena crescente e devolve o elemento de posicao {@code ceil(p/100 * N)}
 * (base 1). A mediana (p50) e o elemento nearest-rank, nao a media dos dois centrais.
 *
 * <p><b>Sentinela.</b> {@link #NOT_MEASURED} ({@code -1}, "nao medido") nunca vira zero: janela vazia, vetor vazio
 * ou protocolo que nao mede. Zero medido continua zero.
 *
 * <p><b>Convencoes PROVISORIAS (a definir com o humano), medidas a parte:</b> (i) bytes que chegaram depois de o
 * destino finalizar a altura ({@link Result#postFinalBytesPerHeight}), JA incluidos na soma principal; (ii) trafego
 * sem altura (NEW_VIEW sem QC, voto nil), fora das alturas ({@link Result#unattributedBytes}).
 */
public final class CbRedeMedida {
    /** "Nao medido": nunca e zero. */
    public static final double NOT_MEASURED = -1.0;
    public static final long FIRST_REGIME_HEIGHT = 2L;
    public static final long LAST_IN_FLIGHT = 2L;

    private CbRedeMedida() { }

    /** Medidas de uma corrida (um trial). Vetores por altura da janela, em ordem crescente de altura. */
    public static final class Result {
        public final int nodes;
        public final long hmax;
        public final double[] networkTotalPerHeight;
        public final double[] networkConsensusPerHeight;
        public final double[] postFinalBytesPerHeight;
        public final double[] firstNodeAllHeights;
        public final double[] firstNodeRegimeHeights;
        public final long unattributedBytes;
        public final long unattributedPackets;

        public Result(int nodes, long hmax, double[] networkTotalPerHeight, double[] networkConsensusPerHeight,
                      double[] postFinalBytesPerHeight, double[] firstNodeAllHeights,
                      double[] firstNodeRegimeHeights, long unattributedBytes, long unattributedPackets) {
            this.nodes = nodes;
            this.hmax = hmax;
            this.networkTotalPerHeight = networkTotalPerHeight;
            this.networkConsensusPerHeight = networkConsensusPerHeight;
            this.postFinalBytesPerHeight = postFinalBytesPerHeight;
            this.firstNodeAllHeights = firstNodeAllHeights;
            this.firstNodeRegimeHeights = firstNodeRegimeHeights;
            this.unattributedBytes = unattributedBytes;
            this.unattributedPackets = unattributedPackets;
        }

        public int regimeHeights() {
            return networkTotalPerHeight.length;
        }
    }

    /** Acumula varios trials: concatena os vetores por altura (populacao agrupada) e soma os contadores (ii). */
    public static final class Pool {
        private final List<double[]> total = new ArrayList<>();
        private final List<double[]> consensus = new ArrayList<>();
        private final List<double[]> postFinal = new ArrayList<>();
        private final List<double[]> firstAll = new ArrayList<>();
        private final List<double[]> firstRegime = new ArrayList<>();
        private long unattributedBytes;
        private long unattributedPackets;
        private int trials;

        public void add(Result r) {
            total.add(r.networkTotalPerHeight);
            consensus.add(r.networkConsensusPerHeight);
            postFinal.add(r.postFinalBytesPerHeight);
            firstAll.add(r.firstNodeAllHeights);
            firstRegime.add(r.firstNodeRegimeHeights);
            unattributedBytes += r.unattributedBytes;
            unattributedPackets += r.unattributedPackets;
            trials++;
        }

        public int trials() { return trials; }
        public double[] networkTotal() { return concat(total); }
        public double[] networkConsensus() { return concat(consensus); }
        public double[] postFinal() { return concat(postFinal); }
        public double[] firstNodeAll() { return concat(firstAll); }
        public double[] firstNodeRegime() { return concat(firstRegime); }
        public long unattributedBytes() { return unattributedBytes; }
        public long unattributedPackets() { return unattributedPackets; }
    }

    private static double[] concat(List<double[]> parts) {
        int len = 0;
        for (double[] p : parts) {
            len += p.length;
        }
        double[] out = new double[len];
        int at = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /**
     * Mede uma corrida de HOTSTUFF ou TENDERMINT a partir dos nos da rede e das metricas do logger.
     *
     * @return {@code null} se nenhum no roda HotStuff/Tendermint (nao medido)
     */
    public static Result medir(List<?> allNodes, SimulationMetrics metrics) {
        List<NetworkHeightLedger> ledgers = new ArrayList<>();
        for (Object o : allNodes) {
            if (!(o instanceof PeerBlockchainNode)) {
                continue;
            }
            AbstractChainBasedConsensus<?, ?> ca = ((PeerBlockchainNode<?, ?>) o).getConsensusAlgorithm();
            if (ca instanceof HotStuff) {
                ledgers.add(((HotStuff<?, ?>) ca).getHeightLedger());
            } else if (ca instanceof Tendermint) {
                ledgers.add(((Tendermint<?, ?>) ca).getHeightLedger());
            } else if (ca instanceof PBFT) {
                ledgers.add(((PBFT<?, ?>) ca).getHeightLedger());
            }
        }
        if (ledgers.isEmpty()) {
            return null;
        }
        return medir(ledgers, metrics.getFirstNodeCbByHeight());
    }

    /** Nucleo puro (testavel sem simulacao): um acumulador por no e o C_B do primeiro no, por altura. */
    public static Result medir(List<NetworkHeightLedger> ledgers, Map<Integer, Long> firstNodeCbByHeight) {
        int n = ledgers.size();
        long hmax = 0L;
        for (Long h : ledgers.get(0).heights().keySet()) {
            boolean all = true;
            for (NetworkHeightLedger l : ledgers) {
                NetworkHeightLedger.Cell c = l.heights().get(h);
                if (c == null || !c.isFinalized()) {
                    all = false;
                    break;
                }
            }
            if (all && h > hmax) {
                hmax = h;
            }
        }
        long lo = FIRST_REGIME_HEIGHT;
        long hi = hmax - LAST_IN_FLIGHT;
        int len = hi >= lo ? (int) (hi - lo + 1) : 0;
        double[] total = new double[len];
        double[] cons = new double[len];
        double[] post = new double[len];
        double[] firstRegime = new double[len];
        int firstRegimeCount = 0;
        for (int i = 0; i < len; i++) {
            long h = lo + i;
            for (NetworkHeightLedger l : ledgers) {
                NetworkHeightLedger.Cell c = l.heights().get(h);
                if (c != null) {
                    total[i] += c.getTotalBytes();
                    cons[i] += c.getConsensusBytes();
                    post[i] += c.getPostFinalBytes();
                }
            }
            Long f = firstNodeCbByHeight.get((int) h);
            if (f != null) {
                firstRegime[firstRegimeCount++] = f;
            }
        }
        double[] all = new double[firstNodeCbByHeight.size()];
        int k = 0;
        for (Long v : firstNodeCbByHeight.values()) {
            all[k++] = v;
        }
        long ub = 0L;
        long up = 0L;
        for (NetworkHeightLedger l : ledgers) {
            ub += l.getUnattributedBytes();
            up += l.getUnattributedPackets();
        }
        return new Result(n, hmax, total, cons, post, all, Arrays.copyOf(firstRegime, firstRegimeCount), ub, up);
    }

    /** Media; {@link #NOT_MEASURED} se o vetor for vazio (nunca 0). */
    public static double mean(double[] v) {
        if (v.length == 0) {
            return NOT_MEASURED;
        }
        double s = 0.0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    /** Percentil nearest-rank (p em (0,100]); {@link #NOT_MEASURED} se o vetor for vazio (nunca 0). */
    public static double percentile(double[] v, double p) {
        if (!(p > 0.0 && p <= 100.0)) {
            throw new IllegalArgumentException("p deve estar em (0,100]: " + p);
        }
        if (v.length == 0) {
            return NOT_MEASURED;
        }
        double[] s = v.clone();
        Arrays.sort(s);
        int rank = (int) Math.ceil(p / 100.0 * s.length);
        return s[Math.max(1, Math.min(rank, s.length)) - 1];
    }

    /** Formata para o CSV: ponto decimal, 3 casas; a sentinela sai como {@code -1} inteiro. */
    public static String fmt(double v) {
        return v == NOT_MEASURED ? "-1" : String.format(Locale.ROOT, "%.3f", v);
    }

    /** Linhas '#' que declaram unidade, granularidade, total x so-consenso, janela e percentil de cada coluna. */
    public static final String[] CSV_COMMENTS = {
        "# mvp-results-cb.csv: colunas ADITIVAS de C_B (HS-CB-fase2, parte A) so para HOTSTUFF e TENDERMINT; os demais protocolos NAO tem linha aqui (nao medido; mvp-results.csv segue como era). Separador ';'. -1 = nao medido (nunca zero).",
        "# unidade de toda coluna *_B: bytes. Fonte: packet.getSize() de pacotes entregues pela rede simulada, nunca autoentrega.",
        "# CbRede_*: granularidade = AGREGADO DE REDE POR BLOCO (soma, por altura, sobre todos os nos receptores, SEM corte local); TOTAL (inclui a carga do bloco). Janela de regime: alturas 2..hmax-2, hmax = maior altura finalizada por todos os nos.",
        "# CbRedeConsenso_*: mesmo agregado, SO-CONSENSO (sem a carga do bloco).",
        "# CbPrimeiro_*: granularidade = PRIMEIRO NO A FINALIZAR cada altura (bytes recebidos por ele ate finalizar, com corte local); TOTAL. COLUNA SECUNDARIA (a do Cb_avg historico, que e em MB). CbPrimeiro_* sobre TODAS as alturas finalizadas; CbPrimeiroRegime_avg_B sobre a janela de regime (mesma populacao de CbRede_*).",
        "# percentis: nearest-rank, posicao ceil(p/100*N) (base 1) no vetor ordenado das alturas; p50 = nearest-rank (nao interpola); populacao = alturas de todos os trials agrupadas.",
        "# convencoes PROVISORIAS (a definir com o humano; ADR-0015 D1): (i) PosFinal_avg_B = media por altura dos bytes que chegaram depois de o DESTINO finalizar a altura, JA INCLUIDOS em CbRede_*; (ii) SemAltura_B/SemAltura_pkt = trafego sem altura (NEW_VIEW sem QC, voto nil), FORA de CbRede_*, soma da corrida inteira sobre todos os nos e trials.",
    };

    public static final String CSV_HEADER = "project;n;trials;regime_heights;"
        + "CbRede_avg_B;CbRede_p50_B;CbRede_p90_B;CbRede_p99_B;"
        + "CbRedeConsenso_avg_B;CbRedeConsenso_p50_B;CbRedeConsenso_p90_B;CbRedeConsenso_p99_B;"
        + "CbPrimeiro_avg_B;CbPrimeiro_p50_B;CbPrimeiro_p90_B;CbPrimeiro_p99_B;CbPrimeiroRegime_avg_B;"
        + "PosFinal_avg_B;SemAltura_B;SemAltura_pkt";

    /** Uma linha do mvp-results-cb.csv (sem quebra de linha). */
    public static String csvLine(String project, int n, int trials, Pool pool) {
        double[] net = pool.networkTotal();
        double[] cons = pool.networkConsensus();
        double[] first = pool.firstNodeAll();
        return String.join(";", project, Integer.toString(n), Integer.toString(trials),
                Integer.toString(net.length),
                fmt(mean(net)), fmt(percentile(net, 50)), fmt(percentile(net, 90)), fmt(percentile(net, 99)),
                fmt(mean(cons)), fmt(percentile(cons, 50)), fmt(percentile(cons, 90)), fmt(percentile(cons, 99)),
                fmt(mean(first)), fmt(percentile(first, 50)), fmt(percentile(first, 90)), fmt(percentile(first, 99)),
                fmt(mean(pool.firstNodeRegime())),
                fmt(mean(pool.postFinal())),
                Long.toString(pool.unattributedBytes()), Long.toString(pool.unattributedPackets()));
    }

    /** Linha de um (protocolo, n) HOTSTUFF/TENDERMINT sem nenhum trial medido: tudo {@code -1} (nao medido, nunca zero). */
    public static String csvLineNotMeasured(String project, int n, int trials) {
        String m = fmt(NOT_MEASURED);
        return String.join(";", project, Integer.toString(n), Integer.toString(trials), m,
                m, m, m, m, m, m, m, m, m, m, m, m, m, m, m, m);
    }

    /** Visao ordenada (so para testes e relatorios): alturas do acumulador de um no. */
    public static SortedMap<Long, NetworkHeightLedger.Cell> heightsOf(NetworkHeightLedger l) {
        return l.heights();
    }
}
