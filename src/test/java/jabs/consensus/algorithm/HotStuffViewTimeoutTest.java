package jabs.consensus.algorithm;

import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.network.networks.hotstuff.HotStuffLocalLANNetwork;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.Event;
import jabs.simulator.event.HotStuffViewTimeoutEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D3a-2a-2c-3: o timer de visao no {@link HotStuff}: atraso vindo do config, evento sem aleatoriedade, no em crash. */
class HotStuffViewTimeoutTest {
    private static final int N = 4;

    @SuppressWarnings("unchecked")
    private static HotStuff<HotStuffBlock, HotStuffTx> hs(HotStuffNode no) {
        return (HotStuff<HotStuffBlock, HotStuffTx>) no.getConsensusAlgorithm();
    }

    private static HotStuffLocalLANNetwork monta(Simulator sim, long seed, HotStuffConsensusConfig cfg) {
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(sim, N, cfg);
        return net;
    }

    @Test
    void startArmaOTimerDaVisaoInicialComOAtrasoDoConfigSemValorEmbutido() {
        for (double timeout : new double[]{3.7, 0.9, 123.25}) {
            Simulator sim = new Simulator();
            HotStuffLocalLANNetwork net = monta(sim, 1, HotStuffTestConfig.config(timeout));
            hs(net.getNode(0)).start();
            HotStuffViewTimeoutEvent achado = null;
            double quando = -1;
            while (sim.isThereMoreEvents() && achado == null) {
                Event e = sim.peekEvent();
                if (e instanceof HotStuffViewTimeoutEvent te) {
                    achado = te;
                    quando = sim.peekEventTime();
                } else {
                    sim.executeNextEvent();
                }
            }
            assertTrue(achado != null, "timer armado");
            assertEquals(HotStuff.INITIAL_VIEW, achado.getView());
            assertEquals(timeout, quando, 0.0, "disparo = agora + viewTimeoutSeconds do config");
        }
    }

    @Test
    void timeoutAntesDeStartFalhaAltoEOEventoValidaArgumentos() {
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = monta(sim, 1, HotStuffTestConfig.config());
        assertThrows(IllegalStateException.class, () -> hs(net.getNode(0)).onViewTimeout(1L));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffViewTimeoutEvent(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffViewTimeoutEvent(hs(net.getNode(0)), -1L));
    }

    @Test
    void noEmCrashIgnoraOTimeoutEContaSemAvancarVisaoNemEnviarPacote() {
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = monta(sim, 1, HotStuffTestConfig.config());
        net.startConsensusRound(sim, 10.0, Set.of());
        HotStuffNode no = net.getNode(2);
        no.crash();
        long antes = hs(no).getCurrentView();
        hs(no).onViewTimeout(antes);
        assertEquals(antes, hs(no).getCurrentView(), "no em crash nao troca de visao");
        assertEquals(1L, hs(no).getCrashedTimeouts());
        assertEquals(0L, hs(no).getTimeoutAdvances());
        assertEquals(0L, hs(no).getDiscardCount(DiscardReason.STALE_VIEW_TIMEOUT));
    }

    @Test
    void timeoutDaVisaoCorrenteAvancaEUmSegundoTimeoutDaMesmaVisaoEObsoleto() {
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = monta(sim, 1, HotStuffTestConfig.config());
        HotStuffNode no = net.getNode(3);
        hs(no).start();
        hs(no).onViewTimeout(HotStuff.INITIAL_VIEW);
        assertEquals(HotStuff.INITIAL_VIEW + 1, hs(no).getCurrentView());
        hs(no).onViewTimeout(HotStuff.INITIAL_VIEW);
        assertEquals(HotStuff.INITIAL_VIEW + 1, hs(no).getCurrentView(), "nao regride nem avanca de novo");
        assertEquals(1L, hs(no).getDiscardCount(DiscardReason.STALE_VIEW_TIMEOUT));
        assertEquals(1L, hs(no).getTimeoutAdvances());
    }

    // ---------- (ix) o evento nao consome aleatoriedade ----------

    @Test
    void oEventoDeTimeoutNaoGuardaNemUsaRandomnessEngine() {
        for (Field f : HotStuffViewTimeoutEvent.class.getDeclaredFields()) {
            assertFalse(RandomnessEngine.class.isAssignableFrom(f.getType()), "campo " + f.getName());
            assertFalse(java.util.Random.class.isAssignableFrom(f.getType()), "campo " + f.getName());
        }
    }

    @Test
    void executarOsEventosDeTimeoutNaoAvancaOMotorDeAleatoriedade() {
        RandomnessEngine a = new RandomnessEngine(77);
        RandomnessEngine b = new RandomnessEngine(77);
        Simulator sa = new Simulator();
        Simulator sb = new Simulator();
        HotStuffLocalLANNetwork na = new HotStuffLocalLANNetwork(a);
        HotStuffLocalLANNetwork nb = new HotStuffLocalLANNetwork(b);
        na.populateNetwork(sa, N, HotStuffTestConfig.config());
        nb.populateNetwork(sb, N, HotStuffTestConfig.config());
        na.startConsensusRound(sa, 10.0, Set.of());
        nb.startConsensusRound(sb, 10.0, Set.of());
        // so em B: timeouts obsoletos e um da visao corrente (que envia NEW_VIEW e arma novo timer); nenhum evento de
        // pacote foi executado ainda, entao nenhuma latencia foi amostrada em nenhum dos dois
        for (int id = 0; id < N; id++) {
            new HotStuffViewTimeoutEvent(hs(nb.getNode(id)), 0L).execute();                       // obsoleto
            new HotStuffViewTimeoutEvent(hs(nb.getNode(id)), HotStuff.INITIAL_VIEW).execute();    // corrente
            new HotStuffViewTimeoutEvent(hs(nb.getNode(id)), HotStuff.INITIAL_VIEW).execute();    // agora obsoleto
        }
        assertEquals(a.nextLong(), b.nextLong(), "o motor de B nao foi consumido pelos eventos de timeout");
        assertEquals(a.nextDouble(), b.nextDouble(), 0.0);
    }
}
