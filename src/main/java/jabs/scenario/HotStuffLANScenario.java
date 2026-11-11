package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.log.EnhancedBlockFinalizationLogger;
import jabs.metrics.SimulationMetrics;
import jabs.network.networks.hotstuff.HotStuffLocalLANNetwork;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Cenario executavel do HotStuff basico no CAMINHO HONESTO (D3a-2a-2c-2b-2) sobre a rede LAN de malha completa.
 *
 * <p>A configuracao e obrigatoria e carrega TODOS os parametros de pesquisa (lote, tamanho de transacao, tamanhos
 * de assinatura, politica de lider): nao ha padrao neste cenario. A rede exige {@code n >= 4}. Ao iniciar, cada no
 * chama {@code start()} (NEW_VIEW inicial) e o protocolo se autossustenta: cada DECIDE leva ao NEW_VIEW da visao
 * seguinte. A retencao de propostas de visao futura usa {@code maxFutureViewHorizon} do config (obrigatorio; valor de
 * producao a definir com o humano; este cenario nao o escolhe). Cada no arma um timer por visao (troca de visao por timeout, 2c-3; {@code viewTimeoutSeconds} do config).
 * Ataque bizantino so por {@link ByzantineConfig} opcional (abaixo); sem ele o cenario e o honesto de sempre.
 *
 * <p><b>Bizantinos (D3a-2a-2d-1).</b> Opcional: um {@link ByzantineConfig} com conjunto EXPLICITO de ids (terceiro
 * construtor de {@code ByzantineConfig}) e injetado em TODOS os nos por {@code HotStuffLocalLANNetwork#applyByzantineConfig},
 * que falha alto para ataque que o HotStuff nao implementa (so {@code EQUIVOCATION}). Todos os nos recebem a mesma
 * config para que cada um saiba quem e bizantino (pre-condicao de destinatarios honestos do ataque e metrica de BFT);
 * so os ids da config agem como bizantinos. O SORTEIO do conjunto (universo, gerador, aninhamento entre valores de
 * f) NAO esta aqui: depende de ADR-0010 PI-13 / ADR-0015 Q30, ainda {@code a definir com o humano}. A particao, a
 * abrangencia e a coordenacao do ataque sao PROVISORIAS (ADR-0015 Q28; ver {@code HotStuff}). Sem config (ou
 * {@code null}) a saida e identica a anterior. Sem flag no {@code MVPComparison} (tarefa 2d-2).
 *
 * <p><b>Nos em crash (2c-3).</b> O conjunto explicito {@code crashedNodeIds} (default vazio: comportamento anterior,
 * saida identica) poe esses nos em CRASH desde o inicio ({@code Node#crash()}) e nao os inicia. E falha por crash
 * injetada, NAO ataque do estudo. Sem flag no {@code MVPComparison}.
 *
 * <p>Metricas: o {@link EnhancedBlockFinalizationLogger} consome os {@code BlockFinalizationEvent} (granularidade
 * ADR-0006: trafego recebido por no que finaliza primeiro cada altura, por bloco).
 */
public class HotStuffLANScenario extends AbstractScenario {
    private final int numNodes;
    private final double simulationStopTime;
    private final HotStuffConsensusConfig config;
    private final SimulationMetrics metrics;
    private final Set<Integer> crashedNodeIds;
    private final ByzantineConfig byzantineConfig;

    public HotStuffLANScenario(String name, long seed, int numNodes, double simulationStopTime,
                               HotStuffConsensusConfig config) {
        this(name, seed, numNodes, simulationStopTime, config, Set.of());
    }

    /**
     * @param crashedNodeIds ids em crash desde o inicio, em [0, n); vazio = nenhum
     * @throws IllegalArgumentException se o conjunto for nulo ou tiver id fora de [0, n)
     */
    public HotStuffLANScenario(String name, long seed, int numNodes, double simulationStopTime,
                               HotStuffConsensusConfig config, Set<Integer> crashedNodeIds) {
        this(name, seed, numNodes, simulationStopTime, config, crashedNodeIds, null);
    }

    /**
     * @param byzantineConfig config com ids explicitos em [0, numNodes) e {@code totalValidators == numNodes}, ou
     *                        {@code null} para o cenario sem ataque (comportamento anterior, mesma saida)
     * @throws IllegalArgumentException se a config for incoerente com {@code numNodes}
     */
    public HotStuffLANScenario(String name, long seed, int numNodes, double simulationStopTime,
                               HotStuffConsensusConfig config, Set<Integer> crashedNodeIds,
                               ByzantineConfig byzantineConfig) {
        super(name, seed);
        if (config == null) {
            throw new IllegalArgumentException("HotStuffConsensusConfig obrigatorio");
        }
        config.validateFor(numNodes);
        if (!(simulationStopTime > 0.0) || Double.isInfinite(simulationStopTime)) {
            throw new IllegalArgumentException("simulationStopTime deve ser finito e > 0: " + simulationStopTime);
        }
        if (crashedNodeIds == null) {
            throw new IllegalArgumentException("crashedNodeIds obrigatorio (use Set.of() para nenhum)");
        }
        for (int id : crashedNodeIds) {
            if (id < 0 || id >= numNodes) {
                throw new IllegalArgumentException("id em crash fora de [0," + numNodes + "): " + id);
            }
        }
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
        this.byzantineConfig = byzantineConfig;
        this.crashedNodeIds = Set.copyOf(crashedNodeIds);
        this.numNodes = numNodes;
        this.simulationStopTime = simulationStopTime;
        this.config = config;
        this.metrics = new SimulationMetrics();
        this.metrics.setTotalValidators(numNodes);
        if (byzantineConfig != null) {
            this.metrics.setByzantineValidators(byzantineConfig.getByzantineCount());
        }
        this.metrics.setConsensusType(SimulationMetrics.ConsensusType.VOTING);
    }

    @Override
    protected void createNetwork() {
        HotStuffLocalLANNetwork hotStuffNetwork = new HotStuffLocalLANNetwork(randomnessEngine);
        this.network = hotStuffNetwork;
        hotStuffNetwork.populateNetwork(this.simulator, this.numNodes, this.config);
        if (this.byzantineConfig != null) {
            // Sem try/catch: ataque que o HotStuff nao implementa falha alto aqui.
            hotStuffNetwork.applyByzantineConfig(this.byzantineConfig);
        }
    }

    @Override
    protected void insertInitialEvents() {
        ((HotStuffLocalLANNetwork) this.network).startConsensusRound(this.simulator, this.simulationStopTime,
                this.crashedNodeIds);
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

    public Set<Integer> getCrashedNodeIds() {
        return crashedNodeIds;
    }

    /** Config bizantina injetada, ou {@code null} no cenario sem ataque. */
    public ByzantineConfig getByzantineConfig() {
        return byzantineConfig;
    }

    public int getNumNodes() {
        return numNodes;
    }

    public HotStuffConsensusConfig getConfig() {
        return config;
    }
}
