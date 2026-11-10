package jabs.network.networks.hotstuff;

import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.Packet;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.PacketSendingProcess;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2c-3 (rodada 2), gap do criterio 3: o timeout do LIDER dispara antes de os NEW_VIEW (ou os votos) chegarem.
 * Artigo (Yin et al., arXiv:1803.05069, secao 4.3): "A nextView interrupt aborts all operations in any as block, and
 * jumps to the Finally block" (Algoritmo 2, linhas 41-43): uma visao abandonada nao inicia nem avanca proposta.
 *
 * <p>Atrasos DETERMINISTICOS e de TESTE (nao de pesquisa): a rede e a LAN normal, com um acrescimo de {@link #EXTRA_S}
 * segundos a todo pacote que CHEGA ao no {@link #LIDER} (de outro no) enviado a partir de {@code atrasoAPartirDe}.
 * Cenario: n=4, o no 1 (lider da visao 1) esta em crash; todos expiram a visao 1 em t=TIMEOUT_S e mandam NEW_VIEW ao no 2
 * (lider da visao 2). O lider da visao 2 e o no 2.
 */
class HotStuffAbandonedViewTest {
    private static final int N = 4;
    private static final int LIDER = 2;
    private static final int CRASH = 1;
    private static final double TIMEOUT_S = 1.0;
    private static final double EXTRA_S = 4.0;
    private static final double DURACAO_S = 9.0;

    /** LAN normal; so acrescenta atraso fixo (sem consumir aleatoriedade a mais: o super sempre e chamado). */
    private static final class RedeComAtraso extends HotStuffLocalLANNetwork {
        private final Simulator sim;
        private final double aPartirDe;

        RedeComAtraso(RandomnessEngine rng, Simulator sim, double aPartirDe) {
            super(rng);
            this.sim = sim;
            this.aPartirDe = aPartirDe;
        }

        @Override
        public double getLatency(HotStuffNode from, HotStuffNode to) {
            double base = super.getLatency(from, to);
            boolean atrasa = to.getNodeID() == LIDER && from.getNodeID() != LIDER
                    && sim.getSimulationTime() >= aPartirDe;
            return atrasa ? base + EXTRA_S : base;
        }
    }

    private static final class Execucao {
        final RedeComAtraso net;
        /** Propostas de fase PREPARE/PRE_COMMIT/COMMIT efetivamente saidas da fila de envio, por (no, fase, visao). */
        final List<String> enviadas = new ArrayList<>();
        final List<String> violacoes = new ArrayList<>();

        Execucao(double atrasoAPartirDe) {
            Simulator sim = new Simulator();
            net = new RedeComAtraso(new RandomnessEngine(1), sim, atrasoAPartirDe);
            net.populateNetwork(sim, N, HotStuffTestConfig.config(TIMEOUT_S));
            net.startConsensusRound(sim, DURACAO_S, Set.of(CRASH));
            while (sim.isThereMoreEvents() && sim.peekEventTime() <= DURACAO_S) {
                if (sim.peekEvent() instanceof PacketSendingProcess sp && !sp.isQueueEmpty()) {
                    Packet p = sp.peek();
                    if (p.getMessage() instanceof HotStuffProposalMessage pm) {
                        int de = p.getFrom().getNodeID();
                        enviadas.add(de + ":" + pm.getPhase() + ":" + pm.getView());
                        // DECIDE: a autoentrega do proprio DECIDE avanca a replica do lider a v+1 ANTES de o pacote sair da
                        // fila (legitimo, sem timeout); por isso para DECIDE a folga e de 1 visao. As demais fases exigem
                        // visao do remetente <= visao da proposta. A checagem exata no instante da DECISAO de enviar e o
                        // tripwire getLeaderActionsOnAbandonedView do HotStuff.
                        long folga = pm.getPhase() == HotStuffProposalMessage.Phase.DECIDE ? 1 : 0;
                        if (pm.getView() + folga < hs(de).getCurrentView()) {
                            violacoes.add("no " + de + " enviou " + pm.getPhase() + " da visao " + pm.getView()
                                    + " com a replica na visao " + hs(de).getCurrentView() + " em t=" + sim.peekEventTime());
                        }
                    }
                }
                sim.executeNextEvent();
            }
        }

        @SuppressWarnings("unchecked")
        HotStuff<HotStuffBlock, HotStuffTx> hs(int id) {
            return (HotStuff<HotStuffBlock, HotStuffTx>) net.getNode(id).getConsensusAlgorithm();
        }
    }

    /** (d1) NEW_VIEW tardio: o n-f-esimo chega quando a replica do lider ja esta numa visao > 2. */
    @Test
    void newViewQueChegaDepoisDoTimeoutDoLiderNaoIniciaPropostaNumaVisaoAbandonada() {
        Execucao r = new Execucao(0.9);
        System.out.println("[liveness] atraso de NEW_VIEW ao lider: no" + LIDER + " visao=" + r.hs(LIDER).getCurrentView()
                + " LEADER_VIEW_ABANDONED=" + r.hs(LIDER).getDiscardCount(DiscardReason.LEADER_VIEW_ABANDONED)
                + " enviadas(no" + LIDER + ")=" + r.enviadas.stream().filter(s -> s.startsWith(LIDER + ":")).toList());
        assertTrue(r.hs(LIDER).getCurrentView() > 2, "o cenario exige a replica do lider alem da visao 2");
        assertTrue(r.hs(LIDER).getTimeoutAdvances() >= 2, "a replica do lider expirou a visao 2");
        // os 2 NEW_VIEW(1) atrasados foram contados, nenhum entrou como pendente
        assertEquals(2, r.hs(LIDER).getDiscardCount(DiscardReason.LEADER_VIEW_ABANDONED));
        assertFalse(r.hs(LIDER).getLeaderCore().hasStarted(2), "o lider nao propos na visao 2");
        assertFalse(r.enviadas.contains(LIDER + ":PREPARE:2"), "nenhuma proposta da visao 2 saiu do no " + LIDER);
        assertEquals(List.of(), r.violacoes);
        for (int id = 0; id < N; id++) {
            assertEquals(0, r.hs(id).getLeaderActionsOnAbandonedView(), "no " + id + ": nenhuma acao de lider em visao abandonada");
        }
    }

    /** (d2) votos tardios: PREPARE ja difundido na visao 2, a replica expira, os votos chegam depois. */
    @Test
    void votosQueChegamDepoisDoTimeoutDoLiderNaoGeramFaseSeguinteNemSaemNaRede() {
        Execucao r = new Execucao(1.05);
        System.out.println("[liveness] atraso de votos ao lider: no" + LIDER + " visao=" + r.hs(LIDER).getCurrentView()
                + " LEADER_VIEW_ABANDONED=" + r.hs(LIDER).getDiscardCount(DiscardReason.LEADER_VIEW_ABANDONED)
                + " enviadas(no" + LIDER + ")=" + r.enviadas.stream().filter(s -> s.startsWith(LIDER + ":")).toList());
        assertTrue(r.hs(LIDER).getLeaderCore().hasStarted(2), "o lider ja tinha difundido o PREPARE da visao 2");
        assertTrue(r.enviadas.contains(LIDER + ":PREPARE:2"));
        assertTrue(r.hs(LIDER).getCurrentView() > 2);
        assertTrue(r.hs(LIDER).getDiscardCount(DiscardReason.LEADER_VIEW_ABANDONED) >= 2,
                "os votos tardios (os dois outros replicas honestas) foram contados");
        assertFalse(r.enviadas.contains(LIDER + ":PRE_COMMIT:2"), "visao abandonada nao emite PRE_COMMIT");
        assertFalse(r.enviadas.contains(LIDER + ":COMMIT:2"));
        assertFalse(r.enviadas.contains(LIDER + ":DECIDE:2"));
        assertEquals(List.of(), r.violacoes);
        for (int id = 0; id < N; id++) {
            assertEquals(0, r.hs(id).getLeaderActionsOnAbandonedView(), "no " + id);
        }
    }

    /** O mesmo cenario, determinista por semente. */
    @Test
    void mesmaSementeMesmaSequenciaDePropostasEnviadas() {
        assertEquals(new Execucao(0.9).enviadas, new Execucao(0.9).enviadas);
        assertEquals(new Execucao(1.05).enviadas, new Execucao(1.05).enviadas);
    }
}
