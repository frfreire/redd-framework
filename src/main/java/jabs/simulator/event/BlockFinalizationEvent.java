package jabs.simulator.event;

import jabs.ledgerdata.Block;
import jabs.network.node.nodes.Node;

/**
 * Event for block finalization logging.
 *
 * Granularidade do trafego (ADR-0006): bytes RECEBIDOS pelo no {@link #getNode()} ate a sua
 * finalizacao local do bloco (convencao "trafego recebido por no"), nao agregado de rede.
 */
public class BlockFinalizationEvent extends AbstractLogEvent {
    /** Sentinela de "nao medido por este protocolo". Nao e zero. */
    public static final long NOT_MEASURED = -1L;

    private final Node node;
    private final Block block;
    private final long trafficUntilFinalization; // in bytes, C_B_total
    private final long consensusMessageTraffic;  // in bytes, C_B_consenso, or NOT_MEASURED
    private final long selfDeliveryTraffic;           // in bytes, autoentrega (D2), or NOT_MEASURED
    private final long selfDeliveryConsensusTraffic;  // in bytes, parte so-consenso da autoentrega, or NOT_MEASURED

    public BlockFinalizationEvent(double time, Node node, Block block, long trafficUntilFinalization) {
        this(time, node, block, trafficUntilFinalization, NOT_MEASURED);
    }

    public BlockFinalizationEvent(double time, Node node, Block block, long trafficUntilFinalization,
                                  long consensusMessageTraffic) {
        this(time, node, block, trafficUntilFinalization, consensusMessageTraffic, NOT_MEASURED, NOT_MEASURED);
    }

    /**
     * D2 (ADR-0015, registro de 10/11/2026): {@code selfDeliveryTraffic} e a parte do trafego que o PROPRIO no
     * entregou a si mesmo (Packet.getFrom() == receptor), FORA de {@code trafficUntilFinalization} (C_B principal), e
     * {@code selfDeliveryConsensusTraffic} a parte so-consenso dela. {@link #NOT_MEASURED} (-1) = o protocolo produtor
     * nao separa a autoentrega (nunca zero: zero e zero medido).
     */
    public BlockFinalizationEvent(double time, Node node, Block block, long trafficUntilFinalization,
                                  long consensusMessageTraffic, long selfDeliveryTraffic,
                                  long selfDeliveryConsensusTraffic) {
        super(time);
        this.node = node;
        this.block = block;
        this.trafficUntilFinalization = trafficUntilFinalization;
        this.consensusMessageTraffic = consensusMessageTraffic;
        this.selfDeliveryTraffic = selfDeliveryTraffic;
        this.selfDeliveryConsensusTraffic = selfDeliveryConsensusTraffic;
    }

    public Node getNode() {
        return node;
    }

    public Block getBlock() {
        return block;
    }

    /** C_B_total: bytes recebidos pelo no ate a finalizacao (inclui carga do bloco). */
    public long getTrafficUntilFinalization() {
        return trafficUntilFinalization;
    }

    /**
     * C_B_consenso: bytes recebidos pelo no que pertencem so as mensagens de consenso,
     * ou {@link #NOT_MEASURED} (-1) se o protocolo produtor nao mede a decomposicao.
     */
    public long getConsensusMessageTraffic() {
        return consensusMessageTraffic;
    }

    /** Autoentrega (D2) do bloco neste no, em bytes (total), ou {@link #NOT_MEASURED} se o protocolo nao a separa. */
    public long getSelfDeliveryTraffic() {
        return selfDeliveryTraffic;
    }

    /** Parte so-consenso da autoentrega (D2), ou {@link #NOT_MEASURED}. */
    public long getSelfDeliveryConsensusTraffic() {
        return selfDeliveryConsensusTraffic;
    }
}
