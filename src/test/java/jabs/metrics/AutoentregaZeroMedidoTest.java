package jabs.metrics;

import jabs.example.ByzantineSweepTestAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 4 do D2: trava os mutantes sobreviventes L7, L7b e M31 (">= 0" virando "> 0"). Autoentrega medida e igual a
 * ZERO e um valor valido (conta na media); so a sentinela -1 significa "nao medido".
 */
class AutoentregaZeroMedidoTest {

    @Test
    void zeroMedidoNoTotalContaNaMedia_L7() {
        SimulationMetrics m = new SimulationMetrics();
        m.recordSelfDelivery(0, -1);
        assertEquals(0.0, m.getAverageSelfDeliveryBytesPerBlock(), 0.0, "zero medido e media 0, nao a sentinela");
        assertEquals(SimulationMetrics.SELF_DELIVERY_NOT_MEASURED, m.getAverageSelfDeliveryConsensusBytesPerBlock(), 0.0);
    }

    @Test
    void zeroMedidoNoConsensoContaNaMedia_L7b() {
        SimulationMetrics m = new SimulationMetrics();
        m.recordSelfDelivery(-1, 0);
        assertEquals(0.0, m.getAverageSelfDeliveryConsensusBytesPerBlock(), 0.0, "zero medido e media 0, nao a sentinela");
        assertEquals(SimulationMetrics.SELF_DELIVERY_NOT_MEASURED, m.getAverageSelfDeliveryBytesPerBlock(), 0.0);
    }

    @Test
    void zeroEntraNaMediaJuntoComValoresPositivos() {
        SimulationMetrics m = new SimulationMetrics();
        m.recordSelfDelivery(0, 0);
        m.recordSelfDelivery(10, 4);
        assertEquals(5.0, m.getAverageSelfDeliveryBytesPerBlock(), 1e-12);
        assertEquals(2.0, m.getAverageSelfDeliveryConsensusBytesPerBlock(), 1e-12);
    }

    @Test
    void sweepContaTrialComAutoentregaZero_M31() {
        assertTrue(ByzantineSweepTestAccess.conta(true, 0.0), "zero medido conta");
        assertTrue(ByzantineSweepTestAccess.conta(true, 12.5));
        assertFalse(ByzantineSweepTestAccess.conta(true, -1.0), "sentinela nao conta");
        assertFalse(ByzantineSweepTestAccess.conta(false, 12.5), "sem coluna nao conta");
    }
}
