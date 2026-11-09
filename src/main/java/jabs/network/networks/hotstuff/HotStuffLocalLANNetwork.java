package jabs.network.networks.hotstuff;

import jabs.consensus.config.ConsensusAlgorithmConfig;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.network.stats.lan.LAN100MNetworkStats;
import jabs.network.stats.lan.SingleNodeType;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;

/**
 * Rede LAN do HotStuff (molde: PBFTLocalLANNetwork). D3a-2a-1: monta os nos e a malha completa.
 *
 * <p>{@link #startConsensusRound} (D3a-2a-2c-2b-2) inicia o protocolo em todos os nos (molde: Tendermint). No HotStuff o lider e quem propoe (ROTATING muda a cada visao,
 * STABLE e sempre o no 0: {@link HotStuffConsensusConfig#leaderFor}); por isso, ao contrario do PBFT, nao ha
 * "no do meio" escolhido aqui para fugir de bizantinos: quem e bizantino e a politica de lider sao do algoritmo
 * e do cenario.
 *
 * <p>A configuracao e obrigatoria e valida o n ({@code n >= 4}, via {@link HotStuffConsensusConfig#validateFor}).
 * Nao ha constante: a rede nao tem "40 nos" padrao como o PBFT; {@link #populateNetwork(Simulator,
 * ConsensusAlgorithmConfig)} sem n exige o numero explicito e por isso lanca.
 */
public class HotStuffLocalLANNetwork extends Network<HotStuffNode, SingleNodeType> {
    public HotStuffLocalLANNetwork(RandomnessEngine randomnessEngine) {
        super(randomnessEngine, new LAN100MNetworkStats(randomnessEngine));
    }

    public HotStuffNode createNewHotStuffNode(Simulator simulator, int nodeID, int numAllParticipants,
                                              HotStuffConsensusConfig config) {
        return new HotStuffNode(simulator, this, nodeID,
                this.sampleDownloadBandwidth(SingleNodeType.LAN_NODE),
                this.sampleUploadBandwidth(SingleNodeType.LAN_NODE),
                numAllParticipants, config);
    }

    /** Sem n padrao: o numero de nos do estudo vem sempre explicito (fator do experimento). */
    @Override
    public void populateNetwork(Simulator simulator, ConsensusAlgorithmConfig hotStuffConsensusConfig) {
        throw new UnsupportedOperationException(
                "HotStuffLocalLANNetwork nao tem n padrao: use populateNetwork(simulator, numNodes, config)");
    }

    /**
     * Cria {@code numNodes} nos (ids 0..n-1, na ordem) e fecha a malha completa. Nao consome aleatoriedade da
     * rede alem da amostragem de banda/latencia herdada ({@link LAN100MNetworkStats}: banda constante).
     *
     * @param hotStuffConsensusConfig deve ser um {@link HotStuffConsensusConfig}
     * @throws IllegalArgumentException se o config for nulo, de outro tipo, ou {@code numNodes < 4}
     */
    @Override
    public void populateNetwork(Simulator simulator, int numNodes, ConsensusAlgorithmConfig hotStuffConsensusConfig) {
        if (!(hotStuffConsensusConfig instanceof HotStuffConsensusConfig)) {
            throw new IllegalArgumentException("HotStuffConsensusConfig obrigatorio, recebido: "
                    + (hotStuffConsensusConfig == null ? "null" : hotStuffConsensusConfig.getClass().getName()));
        }
        HotStuffConsensusConfig config = (HotStuffConsensusConfig) hotStuffConsensusConfig;
        config.validateFor(numNodes);

        for (int i = 0; i < numNodes; i++) {
            this.addNode(createNewHotStuffNode(simulator, i, numNodes, config), SingleNodeType.LAN_NODE);
        }

        for (Node node : this.getAllNodes()) {
            node.getP2pConnections().connectToNetwork(this);
        }
    }

    /**
     * @param node A HotStuff node to add to the network
     */
    @Override
    public void addNode(HotStuffNode node) {
        this.addNode(node, SingleNodeType.LAN_NODE);
    }

    /**
     * Inicia o HotStuff em todos os nos (cada um emite o NEW_VIEW inicial ao lider da visao inicial). Deve ser
     * chamado depois de {@link #populateNetwork} e antes de rodar a simulacao; a partir dai o caminho honesto se
     * autossustenta (cada DECIDE leva ao NEW_VIEW da visao seguinte). Nao ha agendador externo nem timeout
     * (troca de visao por timeout: 2c-3). Chamar duas vezes falha alto.
     */
    public void startConsensusRound(Simulator simulator, double simulationStopTime) {
        for (HotStuffNode node : this.getAllNodes()) {
            node.startConsensus();
        }
    }
}
