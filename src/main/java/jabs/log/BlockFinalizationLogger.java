package jabs.log;

import jabs.ledgerdata.Block;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.simulator.event.AbstractLogEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.BlockFinalizationEvent;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;

/**
 * Logs block finalization events (when a block is finalized by a node).
 */
public class BlockFinalizationLogger extends AbstractCSVLogger {
    /**
     * creates an abstract CSV logger
     * @param writer this is output CSV of the logger
     */
    public BlockFinalizationLogger(Writer writer) {
        super(writer);
    }

    /**
     * creates an abstract CSV logger
     * @param path this is output path of CSV file
     */
    public BlockFinalizationLogger(Path path) throws IOException {
        super(path);
    }

    /** Nome da coluna ADITIVA da autoentrega (D2, ADR-0015); a mesma do {@link EnhancedBlockFinalizationLogger}. */
    static final String SELF_DELIVERY_COLUMN = "Cb_autoentrega_B";

    private Boolean selfDeliveryColumn;

    /**
     * D2: a coluna de autoentrega existe so se o consenso dos nos separa a autoentrega (MESMO criterio do
     * {@link EnhancedBlockFinalizationLogger}: {@code separatesSelfDelivery()} do primeiro no peer). Decidida uma vez,
     * depois do cenario montado (initialLog roda apos createNetwork). Sem cenario/rede/no peer: sem coluna (saida de antes).
     */
    private boolean selfDeliveryColumn() {
        if (selfDeliveryColumn == null) {
            boolean on = false;
            if (this.scenario != null && this.scenario.getNetwork() != null) {
                for (Object o : this.scenario.getNetwork().getAllNodes()) {
                    if (o instanceof PeerBlockchainNode) {
                        on = ((PeerBlockchainNode<?, ?>) o).getConsensusAlgorithm().separatesSelfDelivery();
                        break;
                    }
                }
            }
            selfDeliveryColumn = on;
        }
        return selfDeliveryColumn;
    }

    @Override
    protected String csvStartingComment() {
        String base = String.format("Simulation name: %s      Number of nodes: %d      Network type: %s", scenario.getName(),
                this.scenario.getNetwork().getAllNodes().size(), this.scenario.getNetwork().getClass().getSimpleName());
        if (selfDeliveryColumn()) {
            // D2: so quando a coluna existe (PBFT, Parlia, Casper, Nakamoto); os demais protocolos mantem o comentario de antes
            // String.concat (nao '+'): nenhum callsite de concatenacao por invokedynamic novo (consome hash de identidade)
            base = base.concat("      Cb granularity: one row per finalization event (NodeID, BlockHeight); bytes received by THAT node until it finalized the block (not a network aggregate, not only the first finalizer); ");
            base = base.concat("TrafficUntilFinalization=C_B_total EXCLUDING self-delivery (D2, ADR-0015); ");
            base = base.concat("Cb_autoentrega_B=bytes the node delivered to itself (no network hop counted), own last column, ");
            base = base.concat("same node and height as TrafficUntilFinalization; -1=not measured (never zero); ");
            base = base.concat("TrafficUntilFinalization + Cb_autoentrega_B = total counted before D2; Seed=");
            base = base.concat(Long.toString(scenario.getSeed()));
            base = base.concat("; SimulatorVersion=");
            base = base.concat(LinkLoadRecorder.SIMULATOR_VERSION);
        }
        return base;
    }

    @Override
    protected boolean csvOutputConditionBeforeEvent(Event event) {
        return false;
    }

    @Override
    protected boolean csvOutputConditionAfterEvent(Event event) {
        return event instanceof BlockFinalizationEvent;
    }

    @Override
    protected boolean csvOutputConditionFinalPerNode() {
        return false;
    }

    @Override
    protected String[] csvHeaderOutput() {
        String[] cols = new String[]{"Time", "NodeID", "BlockHeight", "BlockHashCode", "BlockSize", "BlockCreationTime", "BlockCreator", "BLockFinalizationTime", "TrafficUntilFinalization"};
        if (selfDeliveryColumn()) { // D2: coluna ADITIVA no fim; nome e posicao das 9 existentes intactos
            cols = java.util.Arrays.copyOf(cols, cols.length + 1);
            cols[cols.length - 1] = SELF_DELIVERY_COLUMN;
        }
        return cols;
    }

    @Override
    protected String[] csvEventOutput(Event event) {
        BlockFinalizationEvent finalizationEvent = (BlockFinalizationEvent) event;
        Node node = finalizationEvent.getNode();
        Block block = finalizationEvent.getBlock();

        String[] row = new String[]{
                Double.toString(this.scenario.getSimulator().getSimulationTime()),
                Integer.toString(node.nodeID),
                Integer.toString(block.getHeight()),
                Integer.toString(block.hashCode()),
                Integer.toString(block.getSize()),
                Double.toString(block.getCreationTime()),
                Integer.toString(block.getCreator().nodeID),
                Double.toString(this.scenario.getSimulator().getSimulationTime() - block.getCreationTime()),
                Long.toString(finalizationEvent.getTrafficUntilFinalization())
        };
        if (selfDeliveryColumn()) { // D2: autoentrega do mesmo no e bloco; -1 = nao medido
            row = java.util.Arrays.copyOf(row, row.length + 1);
            row[row.length - 1] = Long.toString(finalizationEvent.getSelfDeliveryTraffic());
        }
        return row;
    }
}