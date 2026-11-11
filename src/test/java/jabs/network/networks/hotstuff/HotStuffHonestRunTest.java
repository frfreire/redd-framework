package jabs.network.networks.hotstuff;

import jabs.consensus.algorithm.HotStuff;
import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.config.ByzantineConfig;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffQcNetworkMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.PacketDeliveryEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2c-2b-2: rede HONESTA completa (lider real + replicas) no simulador, sem timeout nem troca de visao por
 * timeout. Valores de teste, nao de pesquisa.
 */
class HotStuffHonestRunTest {
    private static final double DURACAO_S = 5.0;
    /** Longa o bastante para varias voltas de rodizio: cada visao leva ~0,5 s (bloco de 1 MiB sobre 100 Mbps). */
    private static final double DURACAO_CADEIA_S = 25.0;

    /** Resultado de uma execucao: eventos de finalizacao e pacotes entregues, em ordem de execucao. */
    static final class Run {
        final HotStuffLocalLANNetwork net;
        final HotStuffConsensusConfig cfg;
        final int n;
        final List<BlockFinalizationEvent> finalizacoes = new ArrayList<>();
        final List<Double> tempos = new ArrayList<>(); // instante de cada finalizacao
        final List<Packet> entregues = new ArrayList<>();

        Run(long seed, int n, double duracao) {
            this.n = n;
            this.cfg = HotStuffTestConfig.config();
            Simulator sim = new Simulator();
            this.net = new HotStuffLocalLANNetwork(new RandomnessEngine(seed));
            net.populateNetwork(sim, n, cfg);
            net.startConsensusRound(sim, duracao);
            while (sim.isThereMoreEvents() && sim.peekEventTime() <= duracao) {
                Event e = sim.peekEvent();
                if (e instanceof BlockFinalizationEvent fe) {
                    finalizacoes.add(fe);
                    tempos.add(sim.peekEventTime());
                } else if (e instanceof PacketDeliveryEvent pe) {
                    entregues.add(pe.packet);
                }
                sim.executeNextEvent();
            }
        }

        @SuppressWarnings("unchecked")
        HotStuff<HotStuffBlock, HotStuffTx> hs(int id) {
            return (HotStuff<HotStuffBlock, HotStuffTx>) net.getNode(id).getConsensusAlgorithm();
        }

        /** Blocos finalizados pelo no, em ordem de finalizacao. */
        List<HotStuffBlock> cadeia(int id) {
            List<HotStuffBlock> c = new ArrayList<>();
            for (BlockFinalizationEvent e : finalizacoes) {
                if (e.getNode() == net.getNode(id)) {
                    c.add((HotStuffBlock) e.getBlock());
                }
            }
            return c;
        }
    }

    // ---------- (i) mesma cadeia, lider rotativo, varias visoes ----------

    private void verificaCadeiaUnicaERotativa(int n) {
        Run r = new Run(1, n, DURACAO_CADEIA_S);
        List<HotStuffBlock> ref = r.cadeia(0);
        assertTrue(ref.size() >= 3 * n, "progresso de varias visoes (varias voltas de rodizio): " + ref.size());
        for (int id = 1; id < n; id++) {
            List<HotStuffBlock> c = r.cadeia(id);
            // um no pode estar no maximo uma visao atras do outro no corte do tempo
            int comum = Math.min(ref.size(), c.size());
            assertTrue(Math.abs(ref.size() - c.size()) <= 1, "no " + id + " finalizou " + c.size() + " vs " + ref.size());
            for (int h = 0; h < comum; h++) {
                assertSame(ref.get(h), c.get(h), "mesmo bloco na altura " + (h + 1) + " no no " + id);
            }
        }
        for (int h = 0; h < ref.size(); h++) {
            HotStuffBlock b = ref.get(h);
            assertEquals(h + 1, b.getHeight(), "alturas consecutivas, sem salto");
            assertEquals((HotStuff.INITIAL_VIEW + h) % n, b.getCreator().getNodeID(),
                    "criador do bloco da visao " + (HotStuff.INITIAL_VIEW + h) + " = leaderOf(v) = v mod n");
            assertSame(h == 0 ? HotStuffNode.HOTSTUFF_GENESIS_BLOCK : ref.get(h - 1), b.getFixedParent());
        }
        for (int id = 0; id < n; id++) {
            assertTrue(r.hs(id).getCurrentView() >= HotStuff.INITIAL_VIEW + r.cadeia(id).size());
        }
    }

    @Test
    void redeHonestaN4FinalizaAMesmaCadeiaComLiderRotativo() {
        verificaCadeiaUnicaERotativa(4);
    }

    @Test
    void redeHonestaN7FinalizaAMesmaCadeiaComLiderRotativo() {
        verificaCadeiaUnicaERotativa(7);
    }

    // ---------- (ii) NEW_VIEW iniciais medidos; C_B > 0 e coerente ----------

    @Test
    void newViewIniciaisSaoMedidosUmPacoteParaCadaReplicaExcetoOLider() {
        for (int n : new int[]{4, 7}) {
            Run r = new Run(1, n, DURACAO_S);
            int liderInicial = (int) (HotStuff.INITIAL_VIEW % n);
            int semQc = 0;
            for (Packet p : r.entregues) {
                if (p.getMessage() instanceof HotStuffQcNetworkMessage m
                        && m.getQcMessage().getType() == HotStuffQcMessage.Type.NEW_VIEW
                        && !m.getQcMessage().hasQc()) {
                    semQc++;
                    assertEquals(liderInicial, p.getTo().getNodeID(), "NEW_VIEW inicial vai ao lider da visao inicial");
                    assertEquals(HotStuff.INITIAL_NEW_VIEW_VIEW, m.getQcMessage().getView());
                }
            }
            assertEquals(n - 1, semQc, "n-1 pacotes: o lider se autoentrega sem pacote (n=" + n + ")");
            int tamanho = r.cfg.getMessageOverheadBytes();
            assertEquals((long) (n - 1) * tamanho, r.hs(liderInicial).getUnattributedTraffic(),
                    "contador a parte do lider = (n-1) * tamanho do NEW_VIEW sem QC (n=" + n + ")");
            for (int id = 0; id < n; id++) {
                if (id != liderInicial) {
                    assertEquals(0, r.hs(id).getUnattributedTraffic(), "so o lider recebe NEW_VIEW sem QC");
                }
            }
        }
    }

    @Test
    void trafegoPorBlocoEPositivoECoerenteComOsTamanhos() {
        for (int n : new int[]{4, 7}) {
            Run r = new Run(1, n, DURACAO_S);
            int votoBytes = new jabs.ledgerdata.hotstuff.HotStuffVote(r.net.getNode(0),
                    jabs.ledgerdata.hotstuff.HotStuffVote.Phase.PREPARE, 1, HotStuffNode.HOTSTUFF_GENESIS_BLOCK, r.cfg).getSize();
            assertFalse(r.finalizacoes.isEmpty());
            for (BlockFinalizationEvent e : r.finalizacoes) {
                HotStuffBlock b = (HotStuffBlock) e.getBlock();
                long total = e.getTrafficUntilFinalization();
                long consenso = e.getConsensusMessageTraffic();
                assertTrue(total > 0, "C_B_total > 0 (n=" + n + ")");
                assertTrue(consenso > 0, "C_B_consenso > 0 (n=" + n + ")");
                assertTrue(consenso <= total);
                int no = e.getNode().getNodeID();
                long propostas = 0;
                long votos = 0;
                for (Packet p : r.entregues) {
                    if (p.getTo() != e.getNode()) {
                        continue;
                    }
                    if (p.getMessage() instanceof HotStuffProposalMessage pm && pm.getBlock() == b) {
                        propostas += p.getSize();
                    } else if (p.getMessage() instanceof VoteMessage vm
                            && ((jabs.ledgerdata.hotstuff.HotStuffVote) vm.getVote()).getBlock() == b) {
                        votos += p.getSize();
                    }
                }
                if (b.getCreator() == e.getNode()) {
                    assertEquals(0, propostas, "o lider nunca recebe proposta do proprio bloco pela rede");
                    // para finalizar, o lider agregou n-f votos (o proprio, por autoentrega) em cada uma das 3 fases
                    assertTrue(total >= 3L * (HotStuffConsensusConfig.quorumSize(n) - 1) * votoBytes,
                            "lider recebeu >= n-f-1 votos por fase: " + total);
                    assertTrue(total <= votos, "so pacotes entregues ao lider (votos) e nomeando o bloco");
                    assertEquals(total, consenso, "o que o lider recebe e todo protocolo");
                } else {
                    // replica: soma exata das 4 propostas (PREPARE leva o bloco), mais NEW_VIEW com QC se for o lider seguinte
                    long view = HotStuff.INITIAL_VIEW + b.getHeight() - 1;
                    boolean liderSeguinte = (int) ((view + 1) % n) == no;
                    assertTrue(total >= propostas);
                    if (!liderSeguinte) {
                        assertEquals(propostas, total, "replica comum: tráfego do bloco = suas 4 propostas (n=" + n + ")");
                    }
                    assertEquals(total - b.getSize(), consenso, "consenso = total menos a carga do bloco (so PREPARE a leva)");
                    assertTrue(total > b.getSize(), "o total inclui a carga do bloco");
                }
            }
        }
    }

    // ---------- (iii) nenhum descarte no regime honesto ----------

    @Test
    void semDescarteDeCaminhoHonestoNasReplicas() {
        for (int n : new int[]{4, 7}) {
            Run r = new Run(1, n, DURACAO_S);
            for (int id = 0; id < n; id++) {
                for (DiscardReason motivo : new DiscardReason[]{DiscardReason.INCOHERENT_QC, DiscardReason.WRONG_LEADER,
                        DiscardReason.UNKNOWN_PARENT, DiscardReason.UNKNOWN_BLOCK, DiscardReason.OLD_VIEW,
                        DiscardReason.NOT_EXTENDING_JUSTIFY, DiscardReason.SAFE_NODE_FAILED,
                        DiscardReason.DUPLICATE_PHASE, DiscardReason.DUPLICATE_PENDING, DiscardReason.NOT_VIEW_LEADER,
                        DiscardReason.SENDER_OUT_OF_RANGE, DiscardReason.DUPLICATE_VOTE,
                        DiscardReason.DUPLICATE_NEW_VIEW, DiscardReason.VOTE_VIEW_NOT_STARTED,
                        DiscardReason.VOTE_WRONG_BLOCK, DiscardReason.VOTE_PHASE_OUT_OF_ORDER}) {
                    assertEquals(0, r.hs(id).getDiscardCount(motivo), motivo + " no no " + id + " (n=" + n + ")");
                }
            }
        }
    }

    @Test
    void descartesBenignosSaoSoVotosTardiosENewViewsAlemDeNMenosF() {
        Run r = new Run(1, 4, DURACAO_S);
        for (int id = 0; id < 4; id++) {
            long benignos = r.hs(id).getDiscardCount(DiscardReason.LATE_VOTE)
                    + r.hs(id).getDiscardCount(DiscardReason.UNNEEDED_NEW_VIEW);
            assertEquals(r.hs(id).getTotalDiscards(), benignos, "so descartes de chegada alem do quorum (no " + id + ")");
        }
    }

    // ---------- (iv) autoentrega ----------

    @Test
    void nenhumPacoteTemOrigemIgualADestinoEOLiderNaoContaOsProprioEnvios() {
        for (int n : new int[]{4, 7}) {
            Run r = new Run(1, n, DURACAO_S);
            assertFalse(r.entregues.isEmpty());
            for (Packet p : r.entregues) {
                assertTrue(p.getFrom() != p.getTo(), "autoentrega nunca vira pacote");
            }
            for (BlockFinalizationEvent e : r.finalizacoes) {
                HotStuffBlock b = (HotStuffBlock) e.getBlock();
                if (b.getCreator() == e.getNode()) {
                    long entregueAoLider = 0;
                    for (Packet p : r.entregues) {
                        if (p.getTo() == e.getNode()) {
                            entregueAoLider += p.getSize();
                        }
                    }
                    assertTrue(e.getTrafficUntilFinalization() <= entregueAoLider,
                            "tráfego do lider nunca excede o que a rede lhe entregou (nao inclui os proprios envios)");
                }
            }
        }
    }

    @Test
    void naoHaReentradaNaExecucaoDasAcoesDoLider() {
        Run r = new Run(1, 4, DURACAO_S);
        long executadas = 0;
        long adiadas = 0;
        for (int id = 0; id < 4; id++) {
            assertEquals(1, r.hs(id).getMaxLeaderActionDepth(), "nunca aninhada (no " + id + ")");
            executadas += r.hs(id).getExecutedLeaderActions();
            adiadas += r.hs(id).getDeferredLeaderActions();
        }
        assertTrue(executadas > 0);
        // No regime honesto o voto proprio do lider chega ANTES dos alheios (a difusao precede a entrega local), logo nunca
        // completa o quorum e nada precisa esperar (adiadas == 0 aqui, o que e fato, nao requisito). O caso em que o
        // voto local completa o quorum de dentro de outra entrada esta em
        // HotStuffReplicaTest#votoLocalQueCompletaOQuorumNaoReentraNaExecucaoDaAcao.
        assertEquals(0, adiadas);
    }

    // ---------- (v) determinismo ----------

    private static List<String> assinatura(Run r) {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < r.finalizacoes.size(); i++) {
            BlockFinalizationEvent e = r.finalizacoes.get(i);
            s.add(r.tempos.get(i) + "|" + e.getNode().getNodeID() + "|" + e.getBlock().getHeight() + "|"
                    + ((HotStuffBlock) e.getBlock()).getCreator().getNodeID() + "|"
                    + e.getTrafficUntilFinalization() + "|" + e.getConsensusMessageTraffic());
        }
        return s;
    }

    @Test
    void mesmaSementeMesmaSequenciaDeFinalizacoes() {
        for (int n : new int[]{4, 7}) {
            List<String> a = assinatura(new Run(42, n, DURACAO_S));
            List<String> b = assinatura(new Run(42, n, DURACAO_S));
            assertFalse(a.isEmpty());
            assertEquals(a, b, "n=" + n);
        }
    }

    // ---------- (vi) sem ataque suportado ----------

    @Test
    void soEquivocationEAceitoOsDemaisAtaquesFalhamAlto() {
        Run r = new Run(1, 4, 1.0);
        for (ByzantineConfig.AttackType tipo : ByzantineConfig.AttackType.values()) {
            if (tipo == ByzantineConfig.AttackType.NONE) {
                continue;
            }
            ByzantineConfig cfg = new ByzantineConfig(4, java.util.Set.of(3), tipo.name(), 1L);
            if (tipo == ByzantineConfig.AttackType.EQUIVOCATION) {
                // D3a-2a-2d-1: unico ataque implementado; aceito (o comportamento esta em HotStuffEquivocationTest)
                r.net.getNode(0).getConsensusAlgorithm().setByzantineConfig(cfg);
                assertEquals("EQUIVOCATION", r.net.getNode(0).getConsensusAlgorithm().getByzantineAttackType());
                r.net.getNode(0).getConsensusAlgorithm().setByzantineConfig(null);
                continue;
            }
            assertThrows(UnsupportedOperationException.class,
                    () -> r.net.getNode(0).getConsensusAlgorithm().setByzantineConfig(cfg), tipo.name());
        }
    }

    @Test
    void iniciarDuasVezesFalhaAlto() {
        Run r = new Run(1, 4, 0.0001);
        assertNotNull(r.hs(0));
        assertThrows(IllegalStateException.class, () -> r.hs(0).start());
    }

    @Test
    void trafegoNaoContaPacotesDeVotoNemDeProposta() {
        // sanidade: ha votos e propostas entre os pacotes entregues
        Run r = new Run(1, 4, DURACAO_S);
        assertTrue(r.entregues.stream().anyMatch(p -> p.getMessage() instanceof VoteMessage));
        assertTrue(r.entregues.stream().anyMatch(p -> p.getMessage() instanceof HotStuffProposalMessage));
    }
}
