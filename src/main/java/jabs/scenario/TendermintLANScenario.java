package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.network.networks.tendermint.TendermintLocalLANNetwork;
import jabs.network.node.nodes.tendermint.TendermintNode;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Cenario executavel do Tendermint (caminho honesto) sobre a rede LAN de malha completa.
 *
 * <p>A configuracao e obrigatoria e carrega os parametros de pesquisa (timeouts, lote e tamanho de transacao);
 * a rede exige {@code n >= 4}. O protocolo se autossustenta: ao iniciar, cada no chama {@code start()} e o
 * proponente do round 0 difunde a primeira proposta; timeouts e decisoes encadeiam as alturas seguintes.
 *
 * <p>Metricas: o {@link EnhancedBlockFinalizationLogger} consome os {@code BlockFinalizationEvent} emitidos
 * pelo adaptador e preenche {@link SimulationMetrics} (Tb, Cb, Tt quando ha transacoes).
 *
 * <p><b>Byzantinos (D3b-tendermint-equivocation).</b> Opcional: um {@link ByzantineConfig} com conjunto EXPLICITO de
 * ids (ver o terceiro construtor de {@code ByzantineConfig}) e injetado em TODOS os nos via
 * {@code setByzantineConfig}, que falha alto para ataque que o Tendermint nao implementa (so {@code EQUIVOCATION}).
 * Todos os nos recebem a mesma config para que cada um saiba quem e bizantino (pre-condicao de destinatarios
 * honestos do ataque e a metrica de BFT por no); so os ids da config agem como bizantinos. O SORTEIO do conjunto
 * (universo, gerador, aninhamento entre valores de f) NAO esta aqui: depende de ADR-0010 PI-13 / ADR-0015 Q30, ainda
 * {@code a definir com o humano}. Sem config (ou {@code null}) o cenario e o honesto de antes, com a mesma saida.
 */
public class TendermintLANScenario extends AbstractScenario {
    private final int numNodes;
    private final double simulationStopTime;
    private final TendermintConsensusConfig config;
    private final ByzantineConfig byzantineConfig;
    private SimulationMetrics metrics;

    public TendermintLANScenario(String name, long seed, int numNodes, double simulationStopTime,
                                 TendermintConsensusConfig config) {
        this(name, seed, numNodes, simulationStopTime, config, null);
    }

    /**
     * @param byzantineConfig config com ids explicitos em [0, numNodes) e {@code totalValidators == numNodes}, ou
     *                        {@code null} para o cenario honesto
     */
    public TendermintLANScenario(String name, long seed, int numNodes, double simulationStopTime,
                                 TendermintConsensusConfig config, ByzantineConfig byzantineConfig) {
        super(name, seed);
        if (config == null) {
            throw new IllegalArgumentException("TendermintConsensusConfig obrigatorio");
        }
        config.validateFor(numNodes);
        if (byzantineConfig != null) {
            if (byzantineConfig.getTotalValidators() != numNodes) {
                throw new IllegalArgumentException("ByzantineConfig.totalValidators=" + byzantineConfig.getTotalValidators()
                        + " difere de numNodes=" + numNodes);
            }
            for (int id : byzantineConfig.getByzantineValidatorIds()) {
                if (id < 0 || id >= numNodes) {
                    throw new IllegalArgumentException("id bizantino fora de [0, " + numNodes + "): " + id);
                }
            }
        }
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
        this.config = config;
        this.byzantineConfig = byzantineConfig;
        this.metrics = new SimulationMetrics();
        this.metrics.setTotalValidators(numNodes);
        if (byzantineConfig != null) {
            this.metrics.setByzantineValidators(byzantineConfig.getByzantineCount());
        }
        this.metrics.setConsensusType(SimulationMetrics.ConsensusType.VOTING);
    }

    @Override
    protected void createNetwork() {
        TendermintLocalLANNetwork tendermintNetwork = new TendermintLocalLANNetwork(randomnessEngine);
        this.network = tendermintNetwork;
        tendermintNetwork.populateNetwork(this.simulator, this.numNodes, this.config);
        if (this.byzantineConfig != null) {
            // Sem try/catch: ataque que o Tendermint nao implementa falha alto aqui (criterio 4).
            for (TendermintNode node : tendermintNetwork.getAllNodes()) {
                node.getConsensusAlgorithm().setByzantineConfig(this.byzantineConfig);
            }
        }
    }

    @Override
    protected void insertInitialEvents() {
        ((TendermintLocalLANNetwork) this.network).startConsensusRound(this.simulator, this.simulationStopTime);
    }

    @Override
    public boolean simulationStopCondition() {
        return this.simulator.getSimulationTime() > this.simulationStopTime;
    }

    /** Adiciona o logger de metricas gravando em {@code outputPath}. */
    public void addMetricsLogger(String outputPath) throws IOException {
        this.AddNewLogger(new EnhancedBlockFinalizationLogger(Paths.get(outputPath), this.metrics));
    }

    public SimulationMetrics getMetrics() {
        return this.metrics;
    }

    public int getNumNodes() {
        return numNodes;
    }

    public TendermintConsensusConfig getConfig() {
        return config;
    }

    /** Config bizantina injetada, ou {@code null} no cenario honesto. */
    public ByzantineConfig getByzantineConfig() {
        return byzantineConfig;
    }
}
