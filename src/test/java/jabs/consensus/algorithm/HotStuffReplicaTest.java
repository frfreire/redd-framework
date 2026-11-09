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
 * Rede de 4 nos, lider ROTATING = {@code view mod 4}. Visao inicial = {@link HotStuff#INITIAL_VIEW} = 1 (decisao
 * D3a-2a-2c-2b-2: fiel ao artigo): V0 = primeira visao (lider: no 1), V1 = a seguinte (lider: no 2). A replica sob teste
 * e o no 3 (nunca lider em V0 e V1); o no L0 e usado para o caso "replica que e o lider". Estes testes eram escritos
 * com a visao inicial 0 (lider no 0, replica no 2); foram DESLOCADOS (+1 na visao, ids reatribuidos) sem alterar nenhuma
 * assercao de comportamento, exceto o caso do lider (ver {@code liderRecebeAutoentregaSemPacoteESoVotaAposIniciarAVisao}).
 */
class HotStuffReplicaTest {
    private static final int N = 4;
    private static final int TX_BYTES = 512;
    private static final long V0 = HotStuff.INITIAL_VIEW;
    private static final long V1 = V0 + 1;
    private static final int L0 = (int) (V0 % N); // lider de V0
    private static final int L1 = (int) (V1 % N); // lider de V1
    private static final int OUTRO = 0;           // no que nao lidera V0, nem V1, nem e a replica

    private HotStuffConsensusConfig cfg;
    private Simulator sim;
    private HotStuffLocalLANNetwork net;
    private HotStuffNode replica;
    private HotStuffBlockWithTx a; // bloco da V0 (filho do genesis)
    private final List<Packet> entregues = new ArrayList<>(); // pacotes que a rede entregou (ordem de execucao)

    @BeforeEach
    void montar() {
        cfg = HotStuffTestConfig.config();
        sim = new Simulator();
        net = new HotStuffLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, N, cfg);
        entregues.clear();
        replica = net.getNode(3);
        a = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L0), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 20);
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
            if (e instanceof jabs.simulator.event.PacketDeliveryEvent pe) {
                entregues.add(pe.packet);
            }
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
            pacotes.add(entregaDoLider(replica, prop(f, V0, a)));
        }
        return pacotes;
    }

    /**
     * Votos DISTINTOS (por votante) com essa fase/visao/bloco que a REDE ENTREGOU ao no {@code lider}. Mede o que este
     * teste quer provar (a replica enviou o voto certo ao lider certo, e so a ele). Desde a 2b-2 o lider real so
     * AGREGA votos de visao que ele mesmo propos (senao conta VOTE_VIEW_NOT_STARTED); como estes testes entregam
     * propostas a mao, sem lider real, o agregador nao e o lugar de observar. Ver {@link #votosAgregados}.
     */
    private int votosNoLider(Node lider, HotStuffVote.Phase fase, long view, HotStuffBlock b) {
        java.util.Set<Integer> votantes = new java.util.HashSet<>();
        for (Packet p : entregues) {
            if (p.getTo() == lider && p.getMessage() instanceof VoteMessage vm
                    && vm.getVote() instanceof HotStuffVote hv
                    && hv.getPhase() == fase && hv.getView() == view && hv.getBlock() == b) {
                votantes.add(hv.getVoter().getNodeID());
            }
        }
        return votantes.size();
    }

    /** Votos distintos no AGREGADOR do no (so os que o lider aceitou). */
    private int votosAgregados(Node lider, HotStuffVote.Phase fase, long view, HotStuffBlock b) {
        return hs((HotStuffNode) lider).getVoteAggregator().distinctVoters(fase, view, b);
    }

    private long descartes(HotStuffNode no, DiscardReason r) {
        return hs(no).getDiscardCount(r);
    }

    // ---------- proposta valida em cada fase: voto certo, destinatario certo, estado certo ----------

    @Test
    void prepareGeraVotoPrepareAoLiderESoAdmiteOBloco() {
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        roda();
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PREPARE, V0, a));
        assertEquals(0, votosNoLider(net.getNode(OUTRO), HotStuffVote.Phase.PREPARE, V0, a), "so o lider recebe");
        assertEquals(0, votosNoLider(net.getNode(L1), HotStuffVote.Phase.PREPARE, V0, a));
        assertEquals(0, votosNoLider(replica, HotStuffVote.Phase.PREPARE, V0, a), "sem autoentrega");
        assertEquals(0, votosAgregados(replica, HotStuffVote.Phase.PREPARE, V0, a));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty(), "prepareQC so em PRE_COMMIT");
        assertTrue(hs(replica).getReplicaState().getLockedQc().isEmpty());
        assertEquals(0, hs(replica).getTotalDiscards());
        assertTrue(hs(replica).getLocalBlockTree().contains(a), "bloco da proposta entra na arvore local");
    }

    @Test
    void preCommitAtualizaPrepareQcEVotaPreCommit() {
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        HotStuffProposalMessage pc = prop(Phase.PRE_COMMIT, V0, a);
        entregaDoLider(replica, pc);
        roda();
        assertSame(pc.getQc().get(), hs(replica).getReplicaState().getPrepareQc().get());
        assertTrue(hs(replica).getReplicaState().getLockedQc().isEmpty(), "lockedQC so em COMMIT");
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PRE_COMMIT, V0, a));
        assertEquals(0, votosNoLider(net.getNode(L0), HotStuffVote.Phase.COMMIT, V0, a));
        assertEquals(0, hs(replica).getTotalDiscards());
    }

    @Test
    void commitAtualizaLockedQcEVotaCommit() {
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, V0, a));
        HotStuffProposalMessage cm = prop(Phase.COMMIT, V0, a);
        entregaDoLider(replica, cm);
        roda();
        assertSame(cm.getQc().get(), hs(replica).getReplicaState().getLockedQc().get());
        assertEquals(HotStuffVote.Phase.PREPARE, hs(replica).getReplicaState().getPrepareQc().get().getPhase());
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.COMMIT, V0, a));
        assertTrue(hs(replica).getReplicaState().getHighestFinalized().isEmpty(), "ainda nao finalizou");
        assertEquals(V0, hs(replica).getCurrentView());
    }

    @Test
    void decideFinalizaAvancaAVisaoENaoGeraVoto() {
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, V0, a));
        entregaDoLider(replica, prop(Phase.COMMIT, V0, a));
        roda(); // esvazia os tres votos
        entregaDoLider(replica, prop(Phase.DECIDE, V0, a));
        assertFalse(sim.isThereMoreEvents() && !(sim.peekEvent() instanceof BlockFinalizationEvent),
                "DECIDE nao gera pacote de voto");
        List<BlockFinalizationEvent> ev = roda();
        assertEquals(1, ev.size());
        assertSame(a, ev.get(0).getBlock());
        assertSame(replica, ev.get(0).getNode());
        assertSame(a, hs(replica).getReplicaState().getHighestFinalized().get());
        assertEquals(V1, hs(replica).getCurrentView());
        assertTrue(hs(replica).isLocallyFinalized(a));
        assertSame(a, hs(replica).getCanonicalChainHead());
        assertEquals(1, hs(replica).getNumOfConfirmedBlocks());
        assertEquals(0, votosNoLider(net.getNode(L0), HotStuffVote.Phase.COMMIT, V1, a));
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
        entregaDoLider(replica, prop(Phase.DECIDE, V0, a));
        assertEquals(0, hs(replica).getBlockTraffic(a));
    }

    @Test
    void decidePorMensagemDeQcTemOMesmoEfeitoQueDecidePorProposta() {
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, V0, a));
        entregaDoLider(replica, prop(Phase.COMMIT, V0, a));
        HotStuffQcMessage d = HotStuffQcMessage.decide(net.getNode(L0), V0, qc(HotStuffVote.Phase.COMMIT, V0, a));
        Packet p = entrega(replica, net.getNode(L0), new HotStuffQcNetworkMessage(d));
        List<BlockFinalizationEvent> ev = roda();
        assertEquals(1, ev.size());
        assertSame(a, hs(replica).getReplicaState().getHighestFinalized().get());
        assertEquals(V1, hs(replica).getCurrentView());
        assertTrue(ev.get(0).getTrafficUntilFinalization() >= p.getSize(), "o pacote de DECIDE entra no tráfego");
    }

    // ---------- descarte contado, sem estado alterado e sem voto ----------

    private void assertSemEfeito(long votosPrepareAntes) {
        roda();
        assertEquals(votosPrepareAntes, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PREPARE, V0, a));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
        assertTrue(hs(replica).getReplicaState().getLockedQc().isEmpty());
    }

    @Test
    void propostaDeLiderErradoEContadaESemEfeito() {
        HotStuffProposalMessage errada = HotStuffProposalMessage.prepareWithoutHighQc(V0, net.getNode(L1), a, cfg, N);
        entrega(replica, net.getNode(L1), errada);
        assertEquals(1, descartes(replica, DiscardReason.WRONG_LEADER));
        assertEquals(1, hs(replica).getTotalDiscards());
        assertSemEfeito(0);
        assertFalse(hs(replica).getLocalBlockTree().contains(a), "proposta rejeitada nao deixa rastro na arvore");
        assertEquals(V0, hs(replica).getCurrentView());
        assertEquals(0, hs(replica).getPendingProposalCount());
    }

    @Test
    void qcIncoerenteComAVisaoDaMensagemEContado() {
        // QC da visao V0 numa proposta PRE_COMMIT da visao V1 (as fabricas so impoem view >= qc.view)
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        HotStuffProposalMessage m = HotStuffProposalMessage.preCommit(V1, net.getNode(L1), a, qc(HotStuffVote.Phase.PREPARE, V0, a));
        entrega(replica, net.getNode(L1), m);
        assertEquals(1, descartes(replica, DiscardReason.INCOHERENT_QC));
        assertEquals(0, hs(replica).getPendingProposalCount(), "incoerente nao ocupa a retencao");
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
    }

    @Test
    void qcDeOutroTamanhoDeRedeEContadoIncoerente() {
        HotStuffLocalLANNetwork net7 = new HotStuffLocalLANNetwork(new RandomnessEngine(9));
        net7.populateNetwork(new Simulator(), 7, cfg);
        List<Node> cinco = new ArrayList<>(net7.getAllNodes().subList(0, HotStuffConsensusConfig.quorumSize(7)));
        HotStuffQuorumCertificate qc7 = new HotStuffQuorumCertificate(HotStuffVote.Phase.PREPARE, V0, a, cinco, 7, cfg);
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        entrega(replica, net.getNode(L0), HotStuffProposalMessage.preCommit(V0, net.getNode(L0), a, qc7));
        assertEquals(1, descartes(replica, DiscardReason.INCOHERENT_QC));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
    }

    @Test
    void prepareQueFalhaSafeNodeEContadoSemVotoESemAdmitirOBloco() {
        visao0Completa();
        roda();
        // lockedQC = a (V0). V1: bloco irmao de a, sem QC => nao estende o travado e nao ha QC mais novo
        HotStuffBlockWithTx irmao = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L1), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        assertSame(a, hs(replica).getReplicaState().getLockedQc().get().getBlock());
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(V1, net.getNode(L1), irmao, cfg, N));
        roda();
        assertEquals(1, descartes(replica, DiscardReason.SAFE_NODE_FAILED));
        assertEquals(0, votosNoLider(net.getNode(L1), HotStuffVote.Phase.PREPARE, V1, irmao));
        assertFalse(hs(replica).getLocalBlockTree().contains(irmao));
        assertEquals(V1, hs(replica).getCurrentView(), "visao nao muda por descarte");
        assertSame(a, hs(replica).getReplicaState().getLockedQc().get().getBlock());
    }

    @Test
    void prepareQueEstendeOTravadoPassaMesmoComListaDePaisDoTravadoMutada() {
        visao0Completa();
        roda();
        HotStuffBlockWithTx filho = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L1), a, cfg, TX_BYTES, 5);
        // divida da base: Block.getParents e mutavel; a decisao nao pode depender dela depois da admissao de a
        a.getParents().clear();
        a.getParents().add(null);
        long descartesAntes = hs(replica).getTotalDiscards();
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(V1, net.getNode(L1), filho, cfg, N));
        roda();
        assertEquals(descartesAntes, hs(replica).getTotalDiscards());
        assertEquals(1, votosNoLider(net.getNode(L1), HotStuffVote.Phase.PREPARE, V1, filho));
        // L1 nao iniciou a V1 (sem n-f NEW_VIEW): o voto chegou e foi CONTADO, nao agregado (lider real, 2b-2)
        assertEquals(1, descartes(net.getNode(L1), DiscardReason.VOTE_VIEW_NOT_STARTED));
    }

    @Test
    void prepareCujoBlocoNaoEstendeOHighQcEContado() {
        visao0Completa();
        roda();
        HotStuffBlockWithTx irmao = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L1), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        HotStuffProposalMessage m = HotStuffProposalMessage.prepare(V1, net.getNode(L1), irmao,
                qc(HotStuffVote.Phase.PREPARE, V0, a), N);
        entregaDoLider(replica, m);
        assertEquals(1, descartes(replica, DiscardReason.NOT_EXTENDING_JUSTIFY));
        assertFalse(hs(replica).getLocalBlockTree().contains(irmao));
    }

    @Test
    void prepareComPaiDesconhecidoEContado() {
        HotStuffBlockWithTx orfao = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L0), a, cfg, TX_BYTES, 5);
        // a nunca foi proposto a esta replica: o pai e desconhecido
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(V0, net.getNode(L0), orfao, cfg, N));
        assertEquals(1, descartes(replica, DiscardReason.UNKNOWN_PARENT));
        assertSemEfeito(0);
    }

    @Test
    void visaoAntigaEContada() {
        visao0Completa();
        roda();
        assertEquals(V1, hs(replica).getCurrentView());
        long antes = hs(replica).getTotalDiscards();
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        assertEquals(1, descartes(replica, DiscardReason.OLD_VIEW));
        assertEquals(antes + 1, hs(replica).getTotalDiscards());
        assertEquals(V1, hs(replica).getCurrentView());
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PREPARE, V0, a), "nenhum voto novo");
    }

    @Test
    void fasePosteriorParaBlocoQueAReplicaNuncaAdmitiuEContada() {
        // PRE_COMMIT da V0 sem que a replica tenha visto o PREPARE: fase adiantada => RETIDA, nao descartada
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, V0, a));
        assertEquals(1, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards());
        // um PREPARE de OUTRO bloco libera a retida, que e entao rejeitada por bloco desconhecido (contada)
        HotStuffBlockWithTx outro = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L0), HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 5);
        entregaDoLider(replica, prop(Phase.PREPARE, V0, outro));
        assertEquals(1, descartes(replica, DiscardReason.UNKNOWN_BLOCK));
        assertTrue(hs(replica).getReplicaState().getPrepareQc().isEmpty());
        assertEquals(0, hs(replica).getPendingProposalCount());
    }

    @Test
    void propostaRepetidaDaMesmaFaseEContada() {
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        assertEquals(1, descartes(replica, DiscardReason.DUPLICATE_PHASE));
        roda();
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PREPARE, V0, a), "um unico voto por fase");
    }

    // ---------- visao futura: retida e processada quando a visao alcanca ----------

    @Test
    void propostaDeVisaoFuturaEhRetidaEProcessadaQuandoAVisaoAlcanca() {
        HotStuffBlockWithTx b = BlockFactory.sampleHotStuffBlockWithTx(sim, net.getNode(L1), a, cfg, TX_BYTES, 5);
        // PREPARE da V1 chega ANTES de a replica terminar a V0
        entregaDoLider(replica, HotStuffProposalMessage.prepareWithoutHighQc(V1, net.getNode(L1), b, cfg, N));
        assertEquals(1, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards(), "visao futura nunca e descartada");
        roda();
        assertEquals(0, votosNoLider(net.getNode(L1), HotStuffVote.Phase.PREPARE, V1, b), "ainda nao votou");
        assertFalse(hs(replica).getLocalBlockTree().contains(b));

        visao0Completa(); // o DECIDE da V0 leva a visao a V1 e libera a retida
        roda();
        assertEquals(0, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards());
        assertTrue(hs(replica).getLocalBlockTree().contains(b));
        assertEquals(1, votosNoLider(net.getNode(L1), HotStuffVote.Phase.PREPARE, V1, b));
        // L1 nao iniciou a V1 (sem n-f NEW_VIEW): o voto chegou e foi CONTADO, nao agregado (lider real, 2b-2)
        assertEquals(1, descartes(net.getNode(L1), DiscardReason.VOTE_VIEW_NOT_STARTED));
    }

    @Test
    void propostaDaFaseSeguinteAdiantadaNaMesmaVisaoEhRetidaEReavaliada() {
        entregaDoLider(replica, prop(Phase.COMMIT, V0, a)); // a rede reordenou: COMMIT antes de PRE_COMMIT
        entregaDoLider(replica, prop(Phase.PRE_COMMIT, V0, a)); // tambem adiantado (falta o PREPARE)
        assertEquals(2, hs(replica).getPendingProposalCount());
        entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        roda();
        assertEquals(0, hs(replica).getPendingProposalCount());
        assertEquals(0, hs(replica).getTotalDiscards());
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PREPARE, V0, a));
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.PRE_COMMIT, V0, a));
        assertEquals(1, votosNoLider(net.getNode(L0), HotStuffVote.Phase.COMMIT, V0, a));
        assertSame(a, hs(replica).getReplicaState().getLockedQc().get().getBlock());
    }

    // ---------- retencao sem teto, pela rede simulada ----------

    @Test
    void replicaLentaRetemMuitasVisoesFuturasSemLancarEExpoePico() {
        int visoes = 10 * (N + 1); // alem de qualquer teto 4 * (N + 1)
        for (long v = V1; v < V1 + visoes; v++) {
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

    // ---------- tráfego so de pacotes entregues ----------

    @Test
    void trafegoSomaSoPacotesEntreguesPelaRede() {
        HotStuffNode lider0 = net.getNode(L0);
        Packet pp = entregaDoLider(replica, prop(Phase.PREPARE, V0, a));
        assertEquals(pp.getSize(), hs(replica).getBlockTraffic(a));
        HotStuffVote voto = new HotStuffVote(replica, HotStuffVote.Phase.PREPARE, V0, a, cfg);
        assertEquals(0, hs(lider0).getBlockTraffic(a), "voto na fila de envio ainda nao e tráfego de ninguem");
        roda(); // o voto percorre a rede ate o lider
        assertEquals(voto.getSize(), hs(lider0).getBlockTraffic(a), "o lider soma o pacote de voto entregue");
        assertEquals(voto.getSize(), hs(lider0).getConsensusMessageTraffic(a));
        assertEquals(pp.getSize(), hs(replica).getBlockTraffic(a), "o envio nao soma no remetente");
    }

    // ---------- papel de lider ----------

    /**
     * Faz o no lider da V0 iniciar a visao: entrega n-f NEW_VIEW sem QC de outros tres nos (pacotes de rede). O
     * PREPARE sai (broadcast) e a autoentrega local acontece na hora. NAO roda os eventos: os pacotes ficam na fila.
     */
    private HotStuffBlock liderIniciaV0() {
        HotStuffNode lider = net.getNode(L0);
        for (int id : new int[]{0, 2, 3}) {
            entrega(lider, net.getNode(id), new HotStuffQcNetworkMessage(
                    HotStuffQcMessage.newViewWithoutPrepareQc(net.getNode(id), HotStuff.INITIAL_NEW_VIEW_VIEW, N, cfg)));
        }
        return hs(lider).getLeaderCore().curProposal(V0).orElseThrow();
    }

    @Test
    void liderRecebeAutoentregaSemPacoteESoVotaAposIniciarAVisao() {
        // ADAPTADO da versao 2c-2a, em que o lider so ARMAZENAVA votos e podia receber a proposta "a" de fora. Agora o
        // voto do lider vai ao LeaderCore, que so o aceita depois de o lider ter proposto a visao; o teste inicia a
        // visao de fato (n-f NEW_VIEW) e verifica a autoentrega: voto proprio contado sem pacote nem trafego.
        HotStuffNode lider = net.getNode(L0);
        HotStuffBlock b = liderIniciaV0();
        assertEquals(0, hs(lider).getTotalDiscards(), "autoentrega do PREPARE e do voto proprio nao descarta nada");
        assertEquals(1, hs(lider).getVoteAggregator().distinctVoters(HotStuffVote.Phase.PREPARE, V0, b),
                "voto proprio contado como um dos n-f, por chamada direta");
        assertEquals(0, hs(lider).getBlockTraffic(b), "proposta e voto proprios nao sao tráfego");
        assertEquals(0, hs(lider).getConsensusMessageTraffic(b));
        assertEquals(1, hs(lider).getMaxLeaderActionDepth(), "sem reentrada");
    }

    @Test
    void liderSoAceitaVotosDoBlocoEDaVisaoQueProps() {
        HotStuffNode lider = net.getNode(L0);
        HotStuffBlock b = liderIniciaV0();
        HotStuffVote v1 = new HotStuffVote(net.getNode(OUTRO), HotStuffVote.Phase.PREPARE, V0, b, cfg);
        HotStuffVote v1b = new HotStuffVote(net.getNode(OUTRO), HotStuffVote.Phase.PREPARE, V0, b, cfg);
        entrega(lider, net.getNode(OUTRO), new VoteMessage(v1));
        entrega(lider, net.getNode(OUTRO), new VoteMessage(v1b));
        assertEquals(2, votosAgregados(lider, HotStuffVote.Phase.PREPARE, V0, b), "voto proprio (autoentrega) + o do no OUTRO");
        assertEquals(1, descartes(lider, DiscardReason.DUPLICATE_VOTE));
        // voto para outro bloco da mesma visao
        entrega(lider, net.getNode(2), new VoteMessage(new HotStuffVote(net.getNode(2), HotStuffVote.Phase.PREPARE, V0, a, cfg)));
        assertEquals(1, descartes(lider, DiscardReason.VOTE_WRONG_BLOCK));
        // voto da V0 entregue a quem nao lidera a V0
        entrega(replica, net.getNode(OUTRO), new VoteMessage(new HotStuffVote(net.getNode(OUTRO), HotStuffVote.Phase.PREPARE, V0, a, cfg)));
        assertEquals(1, descartes(replica, DiscardReason.NOT_VIEW_LEADER));
        assertEquals(0, votosAgregados(replica, HotStuffVote.Phase.PREPARE, V0, a));
    }

    @Test
    void newViewAbaixoDoQuorumSoArmazenaEForaDoLiderDeDestinoEContado() {
        HotStuffNode lider1 = net.getNode(L1); // lider da V1: destino de NEW_VIEW com view = V0
        HotStuffQuorumCertificate pq = qc(HotStuffVote.Phase.PREPARE, V0, a);
        entrega(lider1, replica, new HotStuffQcNetworkMessage(HotStuffQcMessage.newView(replica, V0, pq)));
        entrega(lider1, net.getNode(OUTRO), new HotStuffQcNetworkMessage(
                HotStuffQcMessage.newViewWithoutPrepareQc(net.getNode(OUTRO), V0, N, cfg)));
        assertEquals(2, hs(lider1).getNewViewAggregator().count(V1));
        assertSame(pq, hs(lider1).getNewViewAggregator().highQc(V1).get());
        // repetido
        entrega(lider1, replica, new HotStuffQcNetworkMessage(HotStuffQcMessage.newView(replica, V0, pq)));
        assertEquals(1, descartes(lider1, DiscardReason.DUPLICATE_NEW_VIEW));
        // entregue a quem nao lidera a V1
        entrega(replica, net.getNode(OUTRO), new HotStuffQcNetworkMessage(
                HotStuffQcMessage.newViewWithoutPrepareQc(net.getNode(OUTRO), V0, N, cfg)));
        assertEquals(1, descartes(replica, DiscardReason.NOT_VIEW_LEADER));
        assertFalse(sim.isThereMoreEvents(), "abaixo de n-f o lider nao propoe nada");
        assertFalse(hs(lider1).getLeaderCore().hasStarted(V1));
        assertEquals(V1 - 1, hs(lider1).getCurrentView(), "NEW_VIEW nao troca a visao da replica");
    }

    @Test
    void votoLocalQueCompletaOQuorumNaoReentraNaExecucaoDaAcao() {
        // L1 lidera V1. Recebe n-f NEW_VIEW (com o prepareQC de V0 sobre a) enquanto a SUA replica ainda esta em V0:
        // o PREPARE de V1 e autoentregue mas RETIDO (visao futura). Dois votos alheios chegam e sao agregados. Quando o
        // DECIDE de V0 chega, a replica local libera o PREPARE retido e vota; esse voto PROPRIO e o que completa o
        // quorum n-f, de dentro do processamento de outra mensagem. A fase seguinte nao pode executar ali dentro.
        HotStuffNode lider = net.getNode(L1);
        HotStuffQuorumCertificate pq = qc(HotStuffVote.Phase.PREPARE, V0, a);
        for (int id : new int[]{0, 1, 3}) {
            entrega(lider, net.getNode(id), new HotStuffQcNetworkMessage(HotStuffQcMessage.newView(net.getNode(id), V0, pq)));
        }
        HotStuffBlock b = hs(lider).getLeaderCore().curProposal(V1).orElseThrow();
        assertSame(a, b.getFixedParent(), "highQC = prepareQC sobre a: o bloco novo estende a");
        assertEquals(1, hs(lider).getPendingProposalCount(), "PREPARE de V1 autoentregue e retido (a replica local esta em V0)");
        for (int id : new int[]{0, 3}) {
            entrega(lider, net.getNode(id), new VoteMessage(new HotStuffVote(net.getNode(id), HotStuffVote.Phase.PREPARE, V1, b, cfg)));
        }
        assertEquals(2, votosAgregados(lider, HotStuffVote.Phase.PREPARE, V1, b));
        long executadasAntes = hs(lider).getExecutedLeaderActions();
        assertEquals(1, executadasAntes, "so o PREPARE ate aqui");
        assertEquals(0, hs(lider).getDeferredLeaderActions());

        // a replica local de L1 alcanca V0 -> DECIDE (entregas como se viessem do lider de V0)
        for (Phase f : Phase.values()) {
            entregaDoLider(lider, prop(f, V0, a));
        }
        assertEquals(V1, hs(lider).getCurrentView());
        assertEquals(3, votosAgregados(lider, HotStuffVote.Phase.PREPARE, V1, b), "voto proprio completou n-f");
        assertEquals(executadasAntes + 1, hs(lider).getExecutedLeaderActions(), "PRE_COMMIT executado uma vez");
        assertEquals(1, hs(lider).getDeferredLeaderActions(), "a acao veio de entrada aninhada e esperou a vez");
        assertEquals(1, hs(lider).getMaxLeaderActionDepth(), "nunca aninhada");
        // unico descarte: o NEW_VIEW do proprio lider (fim de V0 -> V1), que chega depois dos n-f (benigno, contado)
        assertEquals(1, descartes(lider, DiscardReason.UNNEEDED_NEW_VIEW));
        assertEquals(1, hs(lider).getTotalDiscards());
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
