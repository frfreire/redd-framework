package jabs.scenario;

import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.BlockProposalEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.VoteDeliveryEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Auxiliar do {@link ParliaDeterminismTest}: roda o cenario Parlia/AMBIFY (3 mineradores + 7
 * stakeholders = n 10, como o MVPComparison) e imprime uma linha por evento observavel.
 * Pode ser chamado em processo separado (main) ou na mesma JVM (simular).
 *
 * Por que processo separado: {@code EthereumMinerNode.memPool} e um HashSet de EthereumTx sem
 * hashCode proprio (hash de identidade) percorrido em generateNewBlock com corte por gas. Dentro
 * de uma mesma JVM o gerador de identity hash avanca entre execucoes e o conteudo do bloco muda;
 * entre processos novos a sequencia e a mesma. Esse residuo e anterior a T3, esta fora do Parlia
 * e e compartilhado com o Casper (corrigi-lo mudaria a saida do Casper: criterio 7).
 */
public final class ParliaRunMain {
    public static final int MINERS = 3;
    public static final int STAKEHOLDERS = 7;

    /** Resultado de uma execucao: linhas observaveis e faixa dos atrasos de voto agendados. */
    public record Resultado(List<String> linhas, int votosAgendados, double minDelay, double maxDelay) {}

    public static Resultado simular(long seed, double stop) throws Exception {
        ParliaBSCNetworkScenario cenario = new ParliaBSCNetworkScenario(
                "parlia-det", seed, stop, 3.0, 3, 21, MINERS, STAKEHOLDERS);
        cenario.createNetwork();
        cenario.insertInitialEvents();
        Simulator sim = cenario.getSimulator();

        Field f = Simulator.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        PriorityQueue<Simulator.ScheduledEvent> fila = (PriorityQueue<Simulator.ScheduledEvent>) f.get(sim);

        List<String> linhas = new ArrayList<>();
        int votos = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long ultimoVisto = -1;
        while (sim.isThereMoreEvents() && !cenario.simulationStopCondition()) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockProposalEvent bp) {
                linhas.add("PROP|" + sim.getSimulationTime() + "|" + bp.getBlock().getSize());
            }
            if (ev instanceof BlockFinalizationEvent fe) {
                linhas.add("FIN|" + fe.getNode().nodeID + "|" + fe.getBlock().getHeight() + "|"
                        + fe.getTrafficUntilFinalization() + "|" + sim.getSimulationTime());
            }
            // Votos agendados por este evento: atraso = tempo agendado - tempo atual.
            long novoMax = ultimoVisto;
            for (Simulator.ScheduledEvent se : fila) {
                if (se.number() > ultimoVisto) {
                    novoMax = Math.max(novoMax, se.number());
                    if (se.event() instanceof VoteDeliveryEvent) {
                        double atraso = se.time() - sim.getSimulationTime();
                        votos++;
                        min = Math.min(min, atraso);
                        max = Math.max(max, atraso);
                        linhas.add("VOTE|" + se.time() + "|" + atraso);
                    }
                }
            }
            ultimoVisto = novoMax;
        }
        return new Resultado(linhas, votos, min, max);
    }

    /** Uso: ParliaRunMain SEMENTE DURACAO. Imprime as linhas observaveis na saida padrao. */
    public static void main(String[] args) throws Exception {
        Resultado r = simular(Long.parseLong(args[0]), Double.parseDouble(args[1]));
        StringBuilder sb = new StringBuilder();
        for (String l : r.linhas()) {
            sb.append(l).append('\n');
        }
        System.out.print(sb);
        System.out.flush();
    }

    private ParliaRunMain() {}
}
