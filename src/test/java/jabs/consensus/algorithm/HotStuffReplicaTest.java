package jabs.consensus.algorithm;

import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffBlockWithTx;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffProposalMessage.Phase;
import jabs.network.message.HotStuffQcNetworkMessage;
import jabs.network.message.Message;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.hotstuff.HotStuffLocalLANNetwork;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static jabs.network.node.nodes.hotstuff.HotStuffNode.HOTSTUFF_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2c-2a: lado da REPLICA do caminho honesto, com rede. Harness minimo: as mensagens do lider sao ENTREGUES A
 * MAO (sem lider real) a uma replica; os votos que ela envia andam pela rede simulada ate o lider destinatario.
 * Rede de 4 nos, lider ROTATING = {@code view mod 4}: visao 0 -> no 0, visao 1 -> no 1. A replica sob teste e o no 2
 * (nunca lider nas visoes 0 e 1); o no 0 e usado para o caso "replica que e o lider".
 */
class HotStuffReplicaTest {
    private static final int N = 4;
    private static final int TX_BYTES = 512;

    private HotStuffConsensusConfig cfg;
    private Simulator sim;
    private HotStuffLocalLANNetwork net;
    private HotStuffNode replica;
    private HotStuffBlockWithTx a; // bloco da visao 0 (filho do genesis)

    @BeforeEach
    void montar() {
        cfg = HotStuffTestConfig.config();
        sim = new Simulator();
        net = new HotStuffLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, N, cfg);
        replica = net.getNode(2);
        a = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(0), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 20);
    }

    // ---------- auxiliares ----------

    private static HotStuff<HotStuffBlock, HotStuffTx> hs(HotStuffNode no) {
        @SuppressWarnings("unchecked")
        HotStuff<HotStuffBlock, HotStuffTx> r = (HotStuff<HotStuffBlock, HotStuffTx>) no.getConsensusAlgorithm();
        return r;
    }

    private Node leaderOf(long view) {
        return net.getNode((int) (view % N));
    }

    private HotStuffQuorumCertificate qc(HotStuffVote.Phase fase, long view, HotStuffBlock b) {
        List<Node> votantes = new ArrayList<>(net.getAllNodes().subList(0, HotStuffConsensusConfig.quorumSize(N)));
        return new HotStuffQuorumCertificate(fase, view, b, votantes, N, cfg);
    }

    private HotStuffProposalMessage prop(Phase fase, long view, HotStuffBlock b) {
        Node l = leaderOf(view);
        switch (fase) {
            case PREPARE: return HotStuffProposalMessage.prepareWithoutHighQc(view, l, b, cfg, N);
            case PRE_COMMIT: return HotStuffProposalMessage.preCommit(view, l, b, qc(HotStuffVote.Phase.PREPARE, view, b));
            case COMMIT: return HotStuffProposalMessage.commit(view, l, b, qc(HotStuffVote.Phase.PRE_COMMIT, view, b));
            default: return HotStuffProposalMessage.decide(view, l, b, qc(HotStuffVote.Phase.COMMIT, view, b));
        }
    }

    private Packet entrega(HotStuffNode para, Node de, Message m) {
        Packet p = new Packet(de, para, m);
        para.processIncomingPacket(p);
        return p;
    }

    private Packet entregaDoLider(HotStuffNode para, HotStuffProposalMessage m) {
        return entrega(para, m.getSender(), m);
    }

    /** Executa todos os eventos pendentes (envio/entrega de pacotes) e devolve os eventos de finalizacao. */
    private List<BlockFinalizationEvent> roda() {
        List<BlockFinalizationEvent> eventos = new ArrayList<>();
        while (sim.isThereMoreEvents()) {
            Event e = sim.peekEvent();
            if (e instanceof BlockFinalizationEvent fe) {
                eventos.add(fe);
            }
            sim.executeNextEvent();
        }
        return eventos;
    }

    /** Leva a replica pelas quatro fases da visao 0 sobre {@code a}; devolve os pacotes entregues em ordem. */
    private List<Packet> visao0Completa() {
        List<Packet> pacotes = new ArrayList<>();
        for (Phase f : Phase.values()) {
            pacotes.add(entregaDoLider(replica, prop(f, 0, a)));
        }
        return pacotes;
    }

    private int votosNoLider(Node lider, HotStuffVote.Phase fase, long view, HotStuffBlock b) {
        return hs((HotStuffNode) lider).getVoteAggregator().distinctVoters(fase, view, b);
    }

    private long descartes(HotStuffNode no, DiscardReason r) {
        return hs(no).getDiscardCount(r);
    }

    // ---------- proposta valida em cada fase: voto certo, destinatario certo, estado certo ----------

    @Test
    void prepareGeraVotoPrepareAoLiderESoAdmiteOBloco() {
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        roda();
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.PREPARE, 0, a));
        assertEquals(0, votosNoLider(net.getNode(1), HotStuffVote.Phase.PREPARE, 0, a), "so o lider recebe");
        assertEquals(0, votosNoLider(net.getNode(3), HotStuffVote.Phase.PREPARE, 0, a));
        assertEquals(0, votosNoLider(replica, HotStuffVote.Phase.PREPARE, 0, a), "sem autoentrega");
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty(), "prepareQC so em PRE_COMMIT");
        assertTrue(hs(replica).getReplicaState().getLockedQc().isEmpty());
        assertEquals(0, hs(replica).getTotalDiscards());
        assertTrue(hs(replica).getLocalBlockTree().contains(a), "bloco da proposta entra na arvore local");
    }

    @Test
    void preCommitAtualizaPrepareQcEVotaPreCommit() {
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        HotStuffProposalMessage pc = prop(Phase.PRE_COMMIT, 0, a);
        entregaDoLider(replica, pc);
        roda();
        assertSame(pc.getQc().get(), hs(replica).getReplicaState().getPrepareQc().get());
        assertTrue(hs(replica).getReplicaState().getLockedQc().isEmpty(), "lockedQC so em COMMIT");
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.PRE_COMMIT, 0, a));
        assertEquals(0, votosNoLider(net.getNode(0), HotStuffVote.Phase.COMMIT, 0, a));
        assertEquals(0, hs(replica).getTotalDiscards());
    }

    @Test
    void commitAtualizaLockedQcEVotaCommit() {
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, 0, a));
        HotStuffProposalMessage cm = prop(Phase.COMMIT, 0, a);
        entregaDoLider(replica, cm);
        roda();
        assertSame(cm.getQc().get(), hs(replica).getReplicaState().getLockedQc().get());
        assertEquals(HotStuffVote.Phase.PREPARE, hs(replica).getReplicaState().getPrepareQc().get().getPhase());
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.COMMIT, 0, a));
        assertTrue(hs(replica).getReplicaState().getHighestFinalized().isEmpty(), "ainda nao finalizou");
        assertEquals(0, hs(replica).getCurrentView());
    }

    @Test
    void decideFinalizaAvancaAVisaoENaoGeraVoto() {
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, 0, a));
        entregaDoLider(replica, prop(Phase.COMMIT, 0, a));
        roda(); // esvazia os tres votos
        entregaDoLider(replica, prop(Phase.DECIDE, 0, a));
        assertFalse(sim.isThereMoreEvents() && !(sim.peekEvent() instanceof BlockFinalizationEvent),
                "DECIDE nao gera pacote de voto");
        List<BlockFinalizationEvent> ev = roda();
        assertEquals(1, ev.size());
        assertSame(a, ev.get(0).getBlock());
        assertSame(replica, ev.get(0).getNode());
        assertSame(a, hs(replica).getReplicaState().getHighestFinalized().get());
        assertEquals(1, hs(replica).getCurrentView());
        assertTrue(hs(replica).isLocallyFinalized(a));
        assertSame(a, hs(replica).getCanonicalChainHead());
        assertEquals(1, hs(replica).getNumOfConfirmedBlocks());
        assertEquals(0, votosNoLider(net.getNode(0), HotStuffVote.Phase.COMMIT, 1, a));
    }

    @Test
    void decideEmiteEventoComTrafegoMedidoDosPacotesEntregues() {
        List<Packet> pacotes = visao0Completa();
        List<BlockFinalizationEvent> ev = roda();
        assertEquals(1, ev.size());
        long total = 0;
        for (Packet p : pacotes) {
            total += p.getSize();
        }
        assertEquals(total, ev.get(0).getTrafficUntilFinalization(), "C_B_total = soma dos pacotes recebidos");
        assertEquals(total - a.getSize(), ev.get(0).getConsensusMessageTraffic(),
                "C_B_consenso = total menos a carga do bloco (so o PREPARE a leva)");
        assertTrue(ev.get(0).getConsensusMessageTraffic() >= 0, "zero medido ou mais, nunca o sentinela -1");
        assertEquals(0, hs(replica).getBlockTraffic(a), "contadores do bloco retirados no take");
        assertEquals(0, hs(replica).getConsensusMessageTraffic(a));
        // corte: pacote tardio do bloco ja finalizado nao recomeca a conta
        entregaDoLider(replica, prop(Phase.DECIDE, 0, a));
        assertEquals(0, hs(replica).getBlockTraffic(a));
    }

    @Test
    void decidePorMensagemDeQcTemOMesmoEfeitoQueDecidePorProposta() {
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, 0, a));
        entregaDoLider(replica, prop(Phase.COMMIT, 0, a));
        HotStuffQcMessage d = HotStuffQcMessage.decide(net.getNode(0), 0, qc(HotStuffVote.Phase.COMMIT, 0, a));
        Packet p = entrega(replica, net.getNode(0), new HotStuffQcNetworkMessage(d));
        List<BlockFinalizationEvent> ev = roda();
        assertEquals(1, ev.size());
        assertSame(a, hs(replica).getReplicaState().getHighestFinalized().get());
        assertEquals(1, hs(replica).getCurrentView());
        assertTrue(ev.get(0).getTrafficUntilFinalization() >= p.getSize(), "o pacote de DECIDE entra no tráfego");
    }

    // ---------- descarte contado, sem estado alterado e sem voto ----------

    private void assertSemEfeito(long votosPrepareAntes) {
        roda();
        assertEquals(votosPrepareAntes, votosNoLider(net.getNode(0), HotStuffVote.Phase.PREPARE, 0, a));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
        assertTrue(hs(replica).getReplicaState().getLockedQc().isEmpty());
    }

    @Test
    void propostaDeLiderErradoEContadaESemEfeito() {
        HotStuffProposalMessage errada = HotStuffProposalMessage.prepareWithoutHighQc(0, net.getNode(1), a, cfg, N);
        entrega(replica, net.getNode(1), errada);
        assertEquals(1, descartes(replica, DiscardReason.WRONG_LEADER));
        assertEquals(1, hs(replica).getTotalDiscards());
        assertSemEfeito(0);
        assertFalse(hs(replica).getLocalBlockTree().contains(a), "proposta rejeitada nao deixa rastro na arvore");
        assertEquals(0, hs(replica).getCurrentView());
        assertEquals(0, hs(replica).getPendingProposalCount());
    }

    @Test
    void qcIncoerenteComAVisaoDaMensagemEContado() {
        // QC da visao 0 numa proposta PRE_COMMIT da visao 1 (as fabricas so impoem view >= qc.view)
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        HotStuffProposalMessage m = HotStuffProposalMessage.preCommit(1, net.getNode(1), a, qc(HotStuffVote.Phase.PREPARE, 0, a));
        entrega(replica, net.getNode(1), m);
        assertEquals(1, descartes(replica, DiscardReason.INCOHERENT_QC));
        assertEquals(0, hs(replica).getPendingProposalCount(), "incoerente nao ocupa a retencao");
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
    }

    @Test
    void qcDeOutroTamanhoDeRedeEContadoIncoerente() {
        HotStuffLocalLANNetwork net7 = new HotStuffLocalLANNetwork(new RandomnessEngine(9));
        net7.populateNetwork(new Simulator(), 7, cfg);
        List<Node> cinco = new ArrayList<>(net7.getAllNodes().subList(0, HotStuffConsensusConfig.quorumSize(7)));
        HotStuffQuorumCertificate qc7 = new HotStuffQuorumCertificate(HotStuffVote.Phase.PREPARE, 0, a, cinco, 7, cfg);
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        entrega(replica, net.getNode(0), HotStuffProposalMessage.preCommit(0, net.getNode(0), a, qc7));
        assertEquals(1, descartes(replica, DiscardReason.INCOHERENT_QC));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
    }

    @Test
    void prepareQueFalhaSafeNodeEContadoSemVotoESemAdmitirOBloco() {
        visao0Completa();
        roda();
        // lockedQC = a (visao 0). Visao 1: bloco irmao de a, sem QC => nao estende o travado e nao ha QC mais novo
        HotStuffBlockWithTx irmao = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(1), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        assertSame(a, hs(replica).getReplicaState().getLockedQc().get().getBlock());
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(1, net.getNode(1), irmao, cfg, N));
        roda();
        assertEquals(1, descartes(replica, DiscardReason.SAFE_NODE_FAILED));
        assertEquals(0, votosNoLider(net.getNode(1), HotStuffVote.Phase.PREPARE, 1, irmao));
        assertFalse(hs(replica).getLocalBlockTree().contains(irmao));
        assertEquals(1, hs(replica).getCurrentView(), "visao nao muda por descarte");
        assertSame(a, hs(replica).getReplicaState().getLockedQc().get().getBlock());
    }

    @Test
    void prepareQueEstendeOTravadoPassaMesmoComListaDePaisDoTravadoMutada() {
        visao0Completa();
        roda();
        HotStuffBlockWithTx filho = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(1), a, cfg, TX_BYTES, 5);
        // divida da base: Block.getParents e mutavel; a decisao nao pode depender dela depois da admissao de a
        a.getParents().clear();
        a.getParents().add(null);
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(1, net.getNode(1), filho, cfg, N));
        roda();
        assertEquals(0, hs(replica).getTotalDiscards());
        assertEquals(1, votosNoLider(net.getNode(1), HotStuffVote.Phase.PREPARE, 1, filho));
    }

    @Test
    void prepareCujoBlocoNaoEstendeOHighQcEContado() {
        visao0Completa();
        roda();
        HotStuffBlockWithTx irmao = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(1), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        HotStuffProposalMessage m = HotStuffProposalMessage.prepare(1, net.getNode(1), irmao,
                qc(HotStuffVote.Phase.PREPARE, 0, a), N);
        entregaDoLider(replica, m);
        assertEquals(1, descartes(replica, DiscardReason.NOT_EXTENDING_JUSTIFY));
        assertFalse(hs(replica).getLocalBlockTree().contains(irmao));
    }

    @Test
    void prepareComPaiDesconhecidoEContado() {
        HotStuffBlockWithTx orfao = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(0), a, cfg, TX_BYTES, 5);
        // a nunca foi proposto a esta replica: o pai e desconhecido
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(0, net.getNode(0), orfao, cfg, N));
        assertEquals(1, descartes(replica, DiscardReason.UNKNOWN_PARENT));
        assertSemEfeito(0);
    }

    @Test
    void visaoAntigaEContada() {
        visao0Completa();
        roda();
        assertEquals(1, hs(replica).getCurrentView());
        long antes = hs(replica).getTotalDiscards();
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        assertEquals(1, descartes(replica, DiscardReason.OLD_VIEW));
        assertEquals(antes + 1, hs(replica).getTotalDiscards());
        assertEquals(1, hs(replica).getCurrentView());
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.PREPARE, 0, a), "nenhum voto novo");
    }

    @Test
    void fasePosteriorParaBlocoQueAReplicaNuncaAdmitiuEContada() {
        // PRE_COMMIT da visao 0 sem que a replica tenha visto o PREPARE: fase adiantada => RETIDA, nao descartada
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, 0, a));
        assertEquals(1, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards());
        // um PREPARE de OUTRO bloco libera a retida, que e entao rejeitada por bloco desconhecido (contada)
        HotStuffBlockWithTx outro = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(0), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        entregaDoLider(replica, prop(Phase.PREPARE, 0, outro));
        assertEquals(1, descartes(replica, DiscardReason.UNKNOWN_BLOCK));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
        assertEquals(0, hs(replica).getPendingProposalCount());
    }

    @Test
    void propostaRepetidaDaMesmaFaseEContada() {
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        assertEquals(1, descartes(replica, DiscardReason.DUPLICATE_PHASE));
        roda();
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.PREPARE, 0, a), "um unico voto por fase");
    }

    // ---------- visao futura: retida e processada quando a visao alcanca ----------

    @Test
    void propostaDeVisaoFuturaEhRetidaEProcessadaQuandoAVisaoAlcanca() {
        HotStuffBlockWithTx b = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(1), a, cfg, TX_BYTES, 5);
        // PREPARE da visao 1 chega ANTES de a replica terminar a visao 0
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(1, net.getNode(1), b, cfg, N));
        assertEquals(1, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards(), "visao futura nunca e descartada");
        roda();
        assertEquals(0, votosNoLider(net.getNode(1), HotStuffVote.Phase.PREPARE, 1, b), "ainda nao votou");
        assertFalse(hs(replica).getLocalBlockTree().contains(b));

        visao0Completa(); // o DECIDE da visao 0 leva a visao a 1 e libera a retida
        roda();
        assertEquals(0, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards());
        assertEquals(1, votosNoLider(net.getNode(1), HotStuffVote.Phase.PREPARE, 1, b));
        assertTrue(hs(replica).getLocalBlockTree().contains(b));
    }

    @Test
    void propostaDaFaseSeguinteAdiantadaNaMesmaVisaoEhRetidaEReavaliada() {
        entregaDoLider(replica, prop(Phase.COMMIT, 0, a)); // a rede reordenou: COMMIT antes de PRE_COMMIT
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, 0, a)); // tambem adiantado (falta o PREPARE)
        assertEquals(2, hs(replica).getPendingProposalCount());
        entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        roda();
        assertEquals(0, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards());
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.PREPARE, 0, a));
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.PRE_COMMIT, 0, a));
        assertEquals(1, votosNoLider(net.getNode(0), HotStuffVote.Phase.COMMIT, 0, a));
        assertSame(a, hs(replica).getReplicaState().getLockedQc().get().getBlock());
    }

    // ---------- retencao sem teto, pela rede simulada ----------

    @Test
    void replicaLentaRetemMuitasVisoesFuturasSemLancarEExpoePico() {
        int visoes = 10 * (N + 1); // alem de qualquer teto 4 * (N + 1)
        for (long v = 1; v <= visoes; v++) {
            for (Phase f : Phase.values()) {
                final long vv = v;
                final Phase ff = f;
                assertDoesNotThrow(() -> entregaDoLider(replica, prop(ff, vv, a)));
            }
        }
        assertEquals(4 * visoes, hs(replica).getPendingProposalCount());
        assertEquals(4 * visoes, hs(replica).getPeakPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards(), "nada descartado: atraso finito nao perde proposta honesta");
    }

    // ---------- replica que e o lider: sem pacote para si ----------

    @Test
    void replicaQueELiderDaVisaoNaoGeraPacoteParaSi() {
        HotStuffNode lider0 = net.getNode(0);
        entregaDoLider(lider0, prop(Phase.PREPARE, 0, a));
        entregaDoLider(lider0, prop(Phase.PRE_COMMIT, 0, a));
        entregaDoLider(lider0, prop(Phase.COMMIT, 0, a));
        assertFalse(sim.isThereMoreEvents(), "nenhum pacote para si: o voto vai direto ao agregador local");
        assertEquals(1, hs(lider0).getVoteAggregator().distinctVoters(HotStuffVote.Phase.PREPARE, 0, a));
        assertEquals(1, hs(lider0).getVoteAggregator().distinctVoters(HotStuffVote.Phase.PRE_COMMIT, 0, a));
        assertEquals(1, hs(lider0).getVoteAggregator().distinctVoters(HotStuffVote.Phase.COMMIT, 0, a));
        assertEquals(0, hs(lider0).getBlockTraffic(a), "proposta e voto proprios nao sao tráfego");
        assertEquals(0, hs(lider0).getConsensusMessageTraffic(a));
        assertEquals(0, hs(lider0).getTotalDiscards());
    }

    // ---------- tráfego so de pacotes entregues ----------

    @Test
    void trafegoSomaSoPacotesEntreguesPelaRede() {
        HotStuffNode lider0 = net.getNode(0);
        Packet pp = entregaDoLider(replica, prop(Phase.PREPARE, 0, a));
        assertEquals(pp.getSize(), hs(replica).getBlockTraffic(a));
        HotStuffVote voto = new HotStuffVote(replica, HotStuffVote.Phase.PREPARE, 0, a, cfg);
        assertEquals(0, hs(lider0).getBlockTraffic(a), "voto na fila de envio ainda nao e tráfego de ninguem");
        roda(); // o voto percorre a rede ate o lider
        assertEquals(voto.getSize(), hs(lider0).getBlockTraffic(a), "o lider soma o pacote de voto entregue");
        assertEquals(voto.getSize(), hs(lider0).getConsensusMessageTraffic(a));
        assertEquals(pp.getSize(), hs(replica).getBlockTraffic(a), "o envio nao soma no remetente");
    }

    // ---------- papel de lider: so armazena ----------

    @Test
    void liderSoArmazenaVotosEContaRepetidosEEntregaErrada() {
        HotStuffNode lider0 = net.getNode(0);
        HotStuffVote v1 = new HotStuffVote(net.getNode(1), HotStuffVote.Phase.PREPARE, 0, a, cfg);
        HotStuffVote v1b = new HotStuffVote(net.getNode(1), HotStuffVote.Phase.PREPARE, 0, a, cfg);
        entrega(lider0, net.getNode(1), new VoteMessage(v1));
        entrega(lider0, net.getNode(1), new VoteMessage(v1b));
        assertEquals(1, votosNoLider(lider0, HotStuffVote.Phase.PREPARE, 0, a));
        assertEquals(1, descartes(lider0, DiscardReason.DUPLICATE_VOTE));
        // voto da visao 0 entregue a quem nao lidera a visao 0
        entrega(replica, net.getNode(1), new VoteMessage(new HotStuffVote(net.getNode(1), HotStuffVote.Phase.PREPARE, 0, a, cfg)));
        assertEquals(1, descartes(replica, DiscardReason.NOT_VIEW_LEADER));
        assertEquals(0, votosNoLider(replica, HotStuffVote.Phase.PREPARE, 0, a));
        // armazenar nao forma QC nem propoe nada: o lado do lider e a parte 2c-2b
        assertFalse(sim.isThereMoreEvents());
    }

    @Test
    void newViewSoAtualizaOAgregadorDoLiderDaVisaoDeDestino() {
        HotStuffNode lider1 = net.getNode(1);
        HotStuffQuorumCertificate pq = qc(HotStuffVote.Phase.PREPARE, 0, a);
        // NEW_VIEW da visao 0 (a que expirou) vai ao lider da visao 1
        entrega(lider1, replica, new HotStuffQcNetworkMessage(HotStuffQcMessage.newView(replica, 0, pq)));
        entrega(lider1, net.getNode(3), new HotStuffQcNetworkMessage(
                HotStuffQcMessage.newViewWithoutPrepareQc(net.getNode(3), 0, N, cfg)));
        assertEquals(2, hs(lider1).getNewViewAggregator().count(1));
        assertSame(pq, hs(lider1).getNewViewAggregator().highQc(1).get());
        // repetido
        entrega(lider1, replica, new HotStuffQcNetworkMessage(HotStuffQcMessage.newView(replica, 0, pq)));
        assertEquals(1, descartes(lider1, DiscardReason.DUPLICATE_NEW_VIEW));
        // entregue a quem nao lidera a visao 1
        entrega(replica, net.getNode(3), new HotStuffQcNetworkMessage(
                HotStuffQcMessage.newViewWithoutPrepareQc(net.getNode(3), 0, N, cfg)));
        assertEquals(1, descartes(replica, DiscardReason.NOT_VIEW_LEADER));
        assertFalse(sim.isThereMoreEvents(), "o lider nao age sobre NEW_VIEW nesta parte");
        assertEquals(0, hs(lider1).getCurrentView(), "NEW_VIEW nao troca a visao (2c-3)");
    }

    // ---------- determinismo ----------

    @Test
    void mesmaSequenciaDaMesmaSaida() {
        long t1 = rodaSequenciaCompleta();
        montar();
        long t2 = rodaSequenciaCompleta();
        assertEquals(t1, t2);
    }

    private long rodaSequenciaCompleta() {
        visao0Completa();
        List<BlockFinalizationEvent> ev = roda();
        return ev.get(0).getTrafficUntilFinalization() * 1000 + ev.get(0).getConsensusMessageTraffic();
    }

    // ---------- entradas invalidas ----------

    @Test
    void entradasNulasFalhamAlto() {
        assertThrows(IllegalArgumentException.class, () -> hs(replica).onProposal(null));
        assertThrows(IllegalArgumentException.class, () -> hs(replica).onQcMessage(null));
        assertThrows(IllegalArgumentException.class, () -> hs(replica).onVote(null));
    }
}
