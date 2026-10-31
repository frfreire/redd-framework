package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.consensus.config.DAGsperConfig;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.network.networks.ethereum.DAGsperGlobalBlockchainNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.randengine.RandomnessEngine;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;

/**
 * Cenario executavel do DAGsper (ADR-0006, tarefa 5, debito 7).
 *
 * Rede Ethereum-like (GHOST) em que TODO no, minerador ou nao, e participante do DAGsper: a rede instancia
 * {@code EthereumDAGsperMiner}/{@code EthereumDAGsperNode} (ver {@link DAGsperGlobalBlockchainNetwork}) e o
 * numero de participantes da config ({@code numOfStakeholders}) e o total de nos, o mesmo que vota. O quorum de
 * justificacao e finalizacao e 2f+1, f = floor((n-1)/3) ({@code DAGsper#quorumSize}).
 *
 * Trafego: nada e calculado aqui. O C_B que chega ao CSV vem dos hooks de {@code PeerBlockchainNode}, que somam
 * {@code packet.getSize()} so de pacotes que passaram pela rede simulada (autoentrega local nao conta).
 *
 * LIMITE n >= {@value #MIN_NODES}: {@code AbstractBlockchainP2PConnections.connectToNetwork} (compartilhado com
 * Casper, Parlia e Nakamoto, NAO alterado: mudar mudaria a saida deles) repete o sorteio de vizinhos ate obter
 * {@code numOutboundConnections} (3 no EthereumGethP2P) distintos e que ainda nao tenham conectado a este no. Com
 * poucos nos nao ha tantos candidatos e o laco nao termina (laco infinito, nao lentidao). O construtor falha alto
 * com n &lt; {@value #MIN_NODES}, mas n = {@value #MIN_NODES} nao e garantia teorica de termino: um no que ja
 * recebeu mais de n-1-3 conexoes de entrada nao acha candidatos. Nas sementes e tamanhos exercitados nos testes
 * e no portao termina.
 *
 * Falha bizantina: so {@code SILENT} e {@code WITHHOLD} (o no bizantino nao emite o proprio voto de
 * justificacao/finalizacao; continua recebendo, propagando e minerando), com a mesma semantica do voto do
 * Casper. {@code EQUIVOCATION} e {@code DOUBLE_SIGN} NAO estao implementados para o DAGsper e o construtor os
 * recusa, em vez de tratar o no como honesto em silencio. Os nos bizantinos sao sorteados com um
 * {@link RandomnessEngine} proprio derivado da semente (nao consome o fluxo da simulacao) e sao aninhados:
 * mesma semente e count maior contem o conjunto do count menor.
 */
public class DAGsperNetworkScenario extends AbstractScenario {
    /** Menor rede em que o setup P2P (EthereumGethP2P) costuma terminar; ver o limite no javadoc da classe. */
    public static final int MIN_NODES = 10;
    /** Deslocamento fixo da semente do sorteio de bizantinos (fluxo separado do da simulacao). */
    private static final long BYZANTINE_SELECTION_SEED_OFFSET = 7919L;

    private final double simulationStopTime;
    private final double averageBlockInterval;
    private final int checkpointSpace;
    private final int numOfMiners;
    private final int numOfNonMiners;
    private final int byzantineCount;
    private final ByzantineConfig.AttackType attackType;
    private final boolean enableTransactions;
    private final double txInterval;
    private final long seed;

    private SimulationMetrics metrics;
    private ByzantineConfig byzantineConfig;

    /**
     * @param name                 nome do cenario (vai para o comentario inicial do CSV)
     * @param seed                 semente do RandomnessEngine (tambem derivada para o sorteio de bizantinos)
     * @param simulationStopTime   duracao simulada, em segundos
     * @param averageBlockInterval intervalo medio entre blocos, em segundos
     * @param checkpointSpace      espacamento de checkpoints do DAGsper (um no vota na altura h se
     *                             h % checkpointSpace == nodeID % checkpointSpace)
     * @param numOfMiners          mineradores (ids 0..numOfMiners-1)
     * @param numOfNonMiners       nos nao mineradores (todos tambem votam)
     * @param byzantineCount       quantos nos nao emitem o proprio voto (0 = todos honestos)
     * @param attackType           NONE, SILENT ou WITHHOLD
     * @param enableTransactions   gera transacoes (habilita a metrica Tt)
     * @param txInterval           intervalo medio entre transacoes, em segundos
     */
    public DAGsperNetworkScenario(String name, long seed, double simulationStopTime, double averageBlockInterval,
                                  int checkpointSpace, int numOfMiners, int numOfNonMiners,
                                  int byzantineCount, ByzantineConfig.AttackType attackType,
                                  boolean enableTransactions, double txInterval) {
        super(name, seed);
        int total = numOfMiners + numOfNonMiners;
        if (total < MIN_NODES) {
            throw new IllegalArgumentException("DAGsper exige n >= " + MIN_NODES + " (recebido " + total
                    + "): EthereumGethP2P.connectToNetwork nao termina com rede pequena (laco infinito)");
        }
        if (numOfMiners < 1) {
            throw new IllegalArgumentException("DAGsper precisa de ao menos 1 minerador");
        }
        if (checkpointSpace < 1) {
            throw new IllegalArgumentException("checkpointSpace deve ser >= 1");
        }
        if (byzantineCount < 0 || byzantineCount > total) {
            throw new IllegalArgumentException("byzantineCount fora de [0, n]: " + byzantineCount);
        }
        ByzantineConfig.AttackType ataque = attackType == null ? ByzantineConfig.AttackType.NONE : attackType;
        if (byzantineCount > 0 && ataque != ByzantineConfig.AttackType.SILENT
                && ataque != ByzantineConfig.AttackType.WITHHOLD) {
            throw new UnsupportedOperationException("DAGsper so implementa SILENT e WITHHOLD (recebido " + ataque
                    + "): recusado em vez de tratar o no como honesto em silencio");
        }
        this.seed = seed;
        this.simulationStopTime = simulationStopTime;
        this.averageBlockInterval = averageBlockInterval;
        this.checkpointSpace = checkpointSpace;
        this.numOfMiners = numOfMiners;
        this.numOfNonMiners = numOfNonMiners;
        this.byzantineCount = byzantineCount;
        this.attackType = ataque;
        this.enableTransactions = enableTransactions;
        this.txInterval = txInterval;
    }

    /** Total de nos da rede (todos participam do DAGsper). */
    public int totalNodes() {
        return numOfMiners + numOfNonMiners;
    }

    @Override
    protected void createNetwork() {
        DAGsperGlobalBlockchainNetwork<?> dagsperNetwork = new DAGsperGlobalBlockchainNetwork<>(randomnessEngine,
                this.checkpointSpace, new EthereumProofOfWorkGlobalNetworkStats6Regions(randomnessEngine));
        this.network = dagsperNetwork;
        dagsperNetwork.populateNetwork(simulator, this.numOfMiners, this.numOfNonMiners,
                new DAGsperConfig<>(EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022),
                        this.averageBlockInterval, this.checkpointSpace, totalNodes()));

        if (this.byzantineCount > 0) {
            Set<Integer> byzantineIds = selectByzantineIds();
            this.byzantineConfig = new ByzantineConfig(totalNodes(), byzantineIds, this.attackType.name(), this.seed);
            for (Object o : this.network.getAllNodes()) {
                ((PeerBlockchainNode<?, ?>) o).getConsensusAlgorithm().setByzantineConfig(this.byzantineConfig);
            }
            if (this.metrics == null) {
                this.metrics = new SimulationMetrics();
            }
            this.metrics.setByzantineValidators(this.byzantineConfig.getByzantineCount());
        }
    }

    /** Embaralha os ids 0..n-1 com um engine proprio e toma os primeiros {@code byzantineCount}. */
    private Set<Integer> selectByzantineIds() {
        RandomnessEngine selection = new RandomnessEngine(this.seed + BYZANTINE_SELECTION_SEED_OFFSET);
        List<Integer> ids = new ArrayList<>();
        for (Object o : this.network.getAllNodes()) {
            ids.add(((Node) o).nodeID);
        }
        for (int i = ids.size() - 1; i > 0; i--) { // Fisher-Yates
            Collections.swap(ids, i, selection.nextInt(i + 1));
        }
        return new HashSet<>(ids.subList(0, this.byzantineCount));
    }

    @Override
    protected void insertInitialEvents() {
        ((DAGsperGlobalBlockchainNetwork<?>) network).startAllMiningProcesses();
        if (enableTransactions) {
            jabs.simulator.event.TxGenerationProcessRandomNetworkNode txProcess =
                    new jabs.simulator.event.TxGenerationProcessRandomNetworkNode(
                            simulator, network, randomnessEngine, txInterval);
            simulator.putEvent(txProcess, txProcess.timeToNextGeneration());
        }
    }

    @Override
    protected boolean simulationStopCondition() {
        return simulator.getSimulationTime() > simulationStopTime;
    }

    public void addMetricsLogger(String outputPath, SimulationMetrics metrics) throws IOException {
        EnhancedBlockFinalizationLogger logger = new EnhancedBlockFinalizationLogger(Paths.get(outputPath), metrics);
        this.metrics = metrics;
        this.metrics.setTotalValidators(totalNodes());
        if (this.byzantineCount > 0) {
            this.metrics.setByzantineValidators(this.byzantineCount);
        }
        this.AddNewLogger(logger);
    }

    public SimulationMetrics getMetrics() {
        return this.metrics;
    }

    /** Config bizantina efetiva (null se todos honestos ou antes de {@code run()}). */
    public ByzantineConfig getByzantineConfig() {
        return this.byzantineConfig;
    }
}
