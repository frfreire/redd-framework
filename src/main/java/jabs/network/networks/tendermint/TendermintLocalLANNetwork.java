package jabs.network.networks.tendermint;

import jabs.consensus.config.ConsensusAlgorithmConfig;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.network.stats.lan.LAN100MNetworkStats;
import jabs.network.stats.lan.SingleNodeType;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;

/**
 * Rede LAN do Tendermint: monta os nos e a malha completa. O agendador de propostas e o proprio protocolo: ao
 * iniciar, cada no chama {@code start()} e o proponente do round 0 difunde a primeira proposta; os timeouts levam
 * aos rounds seguintes e a altura seguinte comeca ao decidir. Nao ha "intervalo entre blocos" externo.
 */
public class TendermintLocalLANNetwork extends Network<TendermintNode, SingleNodeType> {
    public TendermintLocalLANNetwork(RandomnessEngine randomnessEngine) {
        super(randomnessEngine, new LAN100MNetworkStats(randomnessEngine));
    }

    public TendermintNode createNewTendermintNode(Simulator simulator, int nodeID, int numAllParticipants,
                                                  TendermintConsensusConfig config) {
        return new TendermintNode(simulator, this, nodeID,
                this.sampleDownloadBandwidth(SingleNodeType.LAN_NODE),
                this.sampleUploadBandwidth(SingleNodeType.LAN_NODE),
                numAllParticipants, config);
    }

    @Override
    public void populateNetwork(Simulator simulator, ConsensusAlgorithmConfig consensusConfig) {
        throw new UnsupportedOperationException(
                "TendermintLocalLANNetwork nao tem n padrao: use populateNetwork(simulator, numNodes, config)");
    }

    @Override
    public void populateNetwork(Simulator simulator, int numNodes, ConsensusAlgorithmConfig consensusConfig) {
        if (!(consensusConfig instanceof TendermintConsensusConfig)) {
            throw new IllegalArgumentException("TendermintConsensusConfig obrigatorio, recebido: "
                    + (consensusConfig == null ? "null" : consensusConfig.getClass().getName()));
        }
        TendermintConsensusConfig config = (TendermintConsensusConfig) consensusConfig;
        config.validateFor(numNodes);

        for (int i = 0; i < numNodes; i++) {
            this.addNode(createNewTendermintNode(simulator, i, numNodes, config), SingleNodeType.LAN_NODE);
        }

        for (Node node : this.getAllNodes()) {
            node.getP2pConnections().connectToNetwork(this);
        }
    }

    @Override
    public void addNode(TendermintNode node) {
        this.addNode(node, SingleNodeType.LAN_NODE);
    }

    /**
     * Inicia o Algoritmo 1 em todos os nos. Deve ser chamado depois de {@link #populateNetwork} e antes de rodar a
     * simulacao; a partir dai o protocolo se autossustenta (timeouts e decisoes).
     */
    public void startConsensusRound(Simulator simulator, double simulationStopTime) {
        for (TendermintNode node : this.getAllNodes()) {
            node.startConsensus();
        }
    }
}
