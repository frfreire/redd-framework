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
 * <p><b>O que fica para D3a-2a-2:</b> o agendador de propostas (equivalente a
 * {@code PBFTLocalLANNetwork#startConsensusRound}). Aqui so existe o ponto de extensao
 * {@link #startConsensusRound}, que FALHA ALTO: nao e no-op, para que nenhuma simulacao rode "sem erro" sem
 * produzir bloco algum (ver criterio 4 do bar). No HotStuff o lider e quem propoe (ROTATING muda a cada visao,
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
     * PONTO DE EXTENSAO de D3a-2a-2: inicia as visoes (o lider da visao 0 propoe o primeiro bloco e o
     * agendamento segue por visao). Ainda nao implementado.
     *
     * @throws UnsupportedOperationException sempre, em D3a-2a-1
     */
    public void startConsensusRound(Simulator simulator, double simulationStopTime) {
        throw new UnsupportedOperationException(
                "HotStuff: agendador de propostas e algoritmo vem em D3a-2a-2 (esqueleto D3a-2a-1)");
    }
}
