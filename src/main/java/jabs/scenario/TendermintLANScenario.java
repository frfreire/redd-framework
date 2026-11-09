package jabs.scenario;

import jabs.consensus.config.TendermintConsensusConfig;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.network.networks.tendermint.TendermintLocalLANNetwork;

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
 */
public class TendermintLANScenario extends AbstractScenario {
    private final int numNodes;
    private final double simulationStopTime;
    private final TendermintConsensusConfig config;
    private SimulationMetrics metrics;

    public TendermintLANScenario(String name, long seed, int numNodes, double simulationStopTime,
                                 TendermintConsensusConfig config) {
        super(name, seed);
        if (config == null) {
            throw new IllegalArgumentException("TendermintConsensusConfig obrigatorio");
        }
        config.validateFor(numNodes);
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
        this.config = config;
        this.metrics = new SimulationMetrics();
        this.metrics.setTotalValidators(numNodes);
        this.metrics.setConsensusType(SimulationMetrics.ConsensusType.VOTING);
    }

    @Override
    protected void createNetwork() {
        TendermintLocalLANNetwork tendermintNetwork = new TendermintLocalLANNetwork(randomnessEngine);
        this.network = tendermintNetwork;
        tendermintNetwork.populateNetwork(this.simulator, this.numNodes, this.config);
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
}
