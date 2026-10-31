package jabs.consensus.algorithm;

import jabs.simulator.Simulator;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/** Dubles de teste que lancam {@link FalhaInjetada} sob demanda, e leitura da fila de eventos do simulador. */
public final class Duples {
    /** Simulador cujo {@code putEvent} falha quando {@link #falhar} e verdadeiro. */
    public static final class SimuladorQueFalha extends Simulator {
        public boolean falhar;

        @Override
        public ScheduledEvent putEvent(Event event, double remainingTimeToExecution) {
            if (falhar) throw new FalhaInjetada("putEvent(" + event.getClass().getSimpleName() + ")");
            return super.putEvent(event, remainingTimeToExecution);
        }
    }

    /** Gerador cujo {@code sampleExponentialDistribution} falha quando {@link #falhar} e verdadeiro. */
    public static final class RngQueFalha extends RandomnessEngine {
        public boolean falhar;

        public RngQueFalha(long seed) {
            super(seed);
        }

        @Override
        public double sampleExponentialDistribution(double mean) {
            if (falhar) throw new FalhaInjetada("sampleExponentialDistribution");
            return super.sampleExponentialDistribution(mean);
        }
    }

    /** Eventos hoje agendados no simulador (sem executa-los), em ordem de fila arbitraria. */
    public static List<Event> pendentes(Simulator sim) throws ReflectiveOperationException {
        Field f = Simulator.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        PriorityQueue<Simulator.ScheduledEvent> fila = (PriorityQueue<Simulator.ScheduledEvent>) f.get(sim);
        List<Event> out = new ArrayList<>();
        for (Simulator.ScheduledEvent se : fila) out.add(se.event());
        return out;
    }

    private Duples() {}
}
