package jabs.consensus.algorithm;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffBlockWithTx;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffQcNetworkMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.hotstuff.HotStuffLocalLANNetwork;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static jabs.network.node.nodes.hotstuff.HotStuffNode.HOTSTUFF_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2b: ramos de trafego das mensagens de rede do HotStuff (proposta e QC), corte da contagem e atribuicao
 * de bloco independente da lista de pais. Desde D3a-2a-2c-2a o algoritmo da replica existe: o marcador antigo
 * (UnsupportedOperationException do esqueleto) foi trocado por entrega direta; as assercoes de TRAFEGO nao mudaram.
 * O comportamento do algoritmo e testado em HotStuffReplicaTest.
 */
class HotStuffProposalQcTrafficTest {
    private static final int TX_BYTES = 512;
    private static final int N = 4;

    private HotStuffConsensusConfig cfg;
    private Simulator sim;
    private HotStuffLocalLANNetwork net;
    private HotStuffNode lider;
    private HotStuffNode receptor;
    private HotStuffBlockWithTx bloco;

    @BeforeEach
    void montar() {
        cfg = HotStuffTestConfig.config();
        sim = new Simulator();
        net = new HotStuffLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, N, cfg);
        lider = net.getAllNodes().get(0);
        receptor = net.getAllNodes().get(1);
        bloco = BlockFactory.sampleHotStuffBlockWithTx(sim, lider, HOTSTUFF_GENESIS_BLOCK, cfg, TX_BYTES, 20);
    }

    private static HotStuff<HotStuffBlock, HotStuffTx> hs(HotStuffNode no) {
        @SuppressWarnings("unchecked")
        HotStuff<HotStuffBlock, HotStuffTx> r = (HotStuff<HotStuffBlock, HotStuffTx>) no.getConsensusAlgorithm();
        return r;
    }

    private HotStuffQuorumCertificate qc(HotStuffVote.Phase fase, HotStuffBlock b) {
        List<Node> votantes = new ArrayList<>(net.getAllNodes().subList(0, HotStuffConsensusConfig.quorumSize(N)));
        return new HotStuffQuorumCertificate(fase, 0, b, votantes, N, cfg);
    }

    private HotStuffProposalMessage proposta(HotStuffProposalMessage.Phase fase) {
        switch (fase) {
            case PREPARE: return HotStuffProposalMessage.prepareWithoutHighQc(0, lider, bloco, cfg, N);
            case PRE_COMMIT: return HotStuffProposalMessage.preCommit(0, lider, bloco, qc(HotStuffVote.Phase.PREPARE, bloco));
            case COMMIT: return HotStuffProposalMessage.commit(0, lider, bloco, qc(HotStuffVote.Phase.PRE_COMMIT, bloco));
            default: return HotStuffProposalMessage.decide(0, lider, bloco, qc(HotStuffVote.Phase.COMMIT, bloco));
        }
    }

    /** Entrega direta ao receptor (como os testes D3a-2a-1). */
    private void entregaProposta(HotStuffNode para, Packet p) {
        para.processIncomingPacket(p);
    }

    private void entregaQc(HotStuffNode para, Packet p) {
        para.processIncomingPacket(p);
    }

    // ---------- proposta ----------

    @Test
    void propostaPrepareSomaTotalComBlocoEConsensoSemBloco() {
        HotStuffProposalMessage m = proposta(HotStuffProposalMessage.Phase.PREPARE);
        Packet p = new Packet(lider, receptor, m);
        entregaProposta(receptor, p);

        assertEquals(p.getSize(), hs(receptor).getBlockTraffic(bloco));
        assertEquals(m.getConsensusSize(), hs(receptor).getConsensusMessageTraffic(bloco));
        assertEquals(bloco.getSize(), hs(receptor).getBlockTraffic(bloco) - hs(receptor).getConsensusMessageTraffic(bloco),
                "a diferenca total - consenso e exatamente a carga do bloco");
        assertTrue(hs(receptor).getBlockTraffic(bloco) > hs(receptor).getConsensusMessageTraffic(bloco));
    }

    @Test
    void propostasDasDemaisFasesSomamIgualNosDois() {
        for (HotStuffProposalMessage.Phase fase : new HotStuffProposalMessage.Phase[]{
                HotStuffProposalMessage.Phase.PRE_COMMIT, HotStuffProposalMessage.Phase.COMMIT,
                HotStuffProposalMessage.Phase.DECIDE}) {
            setUp2();
            HotStuffProposalMessage m = proposta(fase);
            Packet p = new Packet(lider, receptor, m);
            entregaProposta(receptor, p);
            assertEquals(p.getSize(), hs(receptor).getBlockTraffic(bloco), fase.name());
            assertEquals(p.getSize(), hs(receptor).getConsensusMessageTraffic(bloco), fase.name());
            assertEquals(m.getConsensusSize(), p.getSize(), fase.name());
        }
    }

    private void setUp2() {
        montar();
    }

    @Test
    void propostaPelaRedeSimuladaSomaPacoteEChegaAoAlgoritmo() {
        HotStuffProposalMessage m = proposta(HotStuffProposalMessage.Phase.PREPARE);
        lider.getNodeNetworkInterface().addToUpLinkQueue(new Packet(lider, receptor, m));
        // o despacho ao algoritmo ocorre dentro do evento de entrega; o voto de resposta tambem anda pela rede
        while (sim.isThereMoreEvents()) {
            sim.executeNextEvent();
        }
        assertEquals(m.getSize(), hs(receptor).getBlockTraffic(bloco));
        assertEquals(m.getConsensusSize(), hs(receptor).getConsensusMessageTraffic(bloco));
    }

    @Test
    void autoentregaDePropostaNaoSomaMasChegaAoAlgoritmo() {
        Packet p = new Packet(lider, lider, proposta(HotStuffProposalMessage.Phase.PREPARE));
        entregaProposta(lider, p);
        assertEquals(0, hs(lider).getBlockTraffic(bloco));
        assertEquals(0, hs(lider).getConsensusMessageTraffic(bloco));
    }

    @Test
    void duplicataDePropostaConta() {
        Packet p = new Packet(lider, receptor, proposta(HotStuffProposalMessage.Phase.PREPARE));
        entregaProposta(receptor, p);
        entregaProposta(receptor, p);
        assertEquals(2L * p.getSize(), hs(receptor).getBlockTraffic(bloco));
    }

    // ---------- mensagem de QC ----------

    @Test
    void decideSomaInteiroNosDoisParaOBlocoDoQc() {
        HotStuffQcMessage d = HotStuffQcMessage.decide(lider, 0, qc(HotStuffVote.Phase.COMMIT, bloco));
        Packet p = new Packet(lider, receptor, new HotStuffQcNetworkMessage(d));
        entregaQc(receptor, p);
        assertEquals(d.getSize(), p.getSize());
        assertEquals(p.getSize(), hs(receptor).getBlockTraffic(bloco));
        assertEquals(p.getSize(), hs(receptor).getConsensusMessageTraffic(bloco));
    }

    @Test
    void newViewComQcAtribuiAoBlocoDoQc() {
        HotStuffQcMessage nv = HotStuffQcMessage.newView(receptor, 1, qc(HotStuffVote.Phase.PREPARE, bloco));
        Packet p = new Packet(receptor, lider, new HotStuffQcNetworkMessage(nv));
        entregaQc(lider, p);
        assertEquals(p.getSize(), hs(lider).getBlockTraffic(bloco));
        assertEquals(p.getSize(), hs(lider).getConsensusMessageTraffic(bloco));
        assertEquals(0, hs(lider).getUnattributedTraffic());
    }

    @Test
    void newViewSemQcVaiParaContadorAParteENaoParaNenhumBloco() {
        HotStuffQcMessage nv = HotStuffQcMessage.newViewWithoutPrepareQc(receptor, 1, N, cfg);
        Packet p = new Packet(receptor, lider, new HotStuffQcNetworkMessage(nv));
        entregaQc(lider, p);
        entregaQc(lider, p); // duplicata conta
        assertEquals(2L * cfg.getMessageOverheadBytes(), hs(lider).getUnattributedTraffic());
        assertEquals(0, hs(lider).getBlockTraffic(bloco));
        assertEquals(0, hs(lider).getBlockTraffic(HOTSTUFF_GENESIS_BLOCK));
        assertEquals(2L * cfg.getMessageOverheadBytes(), hs(lider).takeUnattributedTraffic());
        assertEquals(0, hs(lider).getUnattributedTraffic());
    }

    @Test
    void autoentregaDeQcNaoSoma() {
        HotStuffQcMessage d = HotStuffQcMessage.decide(lider, 0, qc(HotStuffVote.Phase.COMMIT, bloco));
        entregaQc(lider, new Packet(lider, lider, new HotStuffQcNetworkMessage(d)));
        HotStuffQcMessage nv = HotStuffQcMessage.newViewWithoutPrepareQc(lider, 1, N, cfg);
        entregaQc(lider, new Packet(lider, lider, new HotStuffQcNetworkMessage(nv)));
        assertEquals(0, hs(lider).getBlockTraffic(bloco));
        assertEquals(0, hs(lider).getConsensusMessageTraffic(bloco));
        assertEquals(0, hs(lider).getUnattributedTraffic());
    }

    @Test
    void duplicataDeQcConta() {
        HotStuffQcMessage d = HotStuffQcMessage.decide(lider, 0, qc(HotStuffVote.Phase.COMMIT, bloco));
        Packet p = new Packet(lider, receptor, new HotStuffQcNetworkMessage(d));
        entregaQc(receptor, p);
        entregaQc(receptor, p);
        assertEquals(2L * p.getSize(), hs(receptor).getBlockTraffic(bloco));
        assertEquals(2L * p.getSize(), hs(receptor).getConsensusMessageTraffic(bloco));
    }

    // ---------- pontos de entrada ----------

    @Test
    void pontosDeEntradaRejeitamNuloEDespachamPropostaValida() {
        assertThrows(IllegalArgumentException.class, () -> hs(receptor).onProposal(null));
        assertThrows(IllegalArgumentException.class, () -> hs(receptor).onQcMessage(null));
        hs(receptor).onProposal(proposta(HotStuffProposalMessage.Phase.PREPARE));
        assertEquals(0, hs(receptor).getTotalDiscards(), "proposta valida nao e descartada");
    }

    // ---------- corte e take ----------

    @Test
    void cortePosFinalizacaoLocalVale_paraPropostaVotoEQc() {
        HotStuff<HotStuffBlock, HotStuffTx> h = hs(receptor);
        Packet pp = new Packet(lider, receptor, proposta(HotStuffProposalMessage.Phase.PREPARE));
        entregaProposta(receptor, pp);
        long totalAntes = h.getBlockTraffic(bloco);
        long consensoAntes = h.getConsensusMessageTraffic(bloco);
        assertTrue(totalAntes > 0);
        assertFalse(h.isLocallyFinalized(bloco));

        h.markLocallyFinalized(bloco);
        assertTrue(h.isLocallyFinalized(bloco));

        entregaProposta(receptor, pp);
        receptor.processIncomingPacket(new Packet(lider, receptor,
                new VoteMessage(new HotStuffVote(lider, HotStuffVote.Phase.COMMIT, 0, bloco, cfg))));
        HotStuffQcMessage d = HotStuffQcMessage.decide(lider, 0, qc(HotStuffVote.Phase.COMMIT, bloco));
        entregaQc(receptor, new Packet(lider, receptor, new HotStuffQcNetworkMessage(d)));

        assertEquals(totalAntes, h.getBlockTraffic(bloco), "nada soma depois da finalizacao local");
        assertEquals(consensoAntes, h.getConsensusMessageTraffic(bloco));
    }

    @Test
    void corteEPorBlocoOutroBlocoContinuaSomando() {
        HotStuffBlockWithTx outro = BlockFactory.sampleHotStuffBlockWithTx(sim, lider, bloco, cfg, TX_BYTES, 5);
        hs(receptor).markLocallyFinalized(bloco);
        Packet p = new Packet(lider, receptor, HotStuffProposalMessage.prepareWithoutHighQc(1, lider, outro, cfg, N));
        entregaProposta(receptor, p);
        assertEquals(p.getSize(), hs(receptor).getBlockTraffic(outro));
        assertEquals(0, hs(receptor).getBlockTraffic(bloco));
    }

    @Test
    void takeTrafficDevolveEZeraSemDesfazerOCorte() {
        HotStuff<HotStuffBlock, HotStuffTx> h = hs(receptor);
        HotStuffProposalMessage m = proposta(HotStuffProposalMessage.Phase.PREPARE);
        Packet p = new Packet(lider, receptor, m);
        entregaProposta(receptor, p);

        HotStuff.BlockTraffic t = h.takeTraffic(bloco);
        assertEquals(p.getSize(), t.total());
        assertEquals(m.getConsensusSize(), t.consensus());
        assertEquals(0, h.getBlockTraffic(bloco));
        assertEquals(0, h.getConsensusMessageTraffic(bloco));
        HotStuff.BlockTraffic vazio = h.takeTraffic(bloco);
        assertEquals(0, vazio.total(), "zero medido, nao sentinela -1");
        assertEquals(0, vazio.consensus());

        h.markLocallyFinalized(bloco);
        entregaProposta(receptor, p);
        assertEquals(0, h.takeTraffic(bloco).total(), "pacote tardio nao recomeca a conta");
    }

    @Test
    void takeEMarkRejeitamBlocoNulo() {
        assertThrows(IllegalArgumentException.class, () -> hs(receptor).takeTraffic(null));
        assertThrows(IllegalArgumentException.class, () -> hs(receptor).markLocallyFinalized(null));
    }

    // ---------- identificacao do bloco independe da lista de pais ----------

    @Test
    void blocoIdentificadoSemDependerDaListaDePaisMutavel() {
        HotStuffProposalMessage m = proposta(HotStuffProposalMessage.Phase.PREPARE);
        HotStuffQcMessage d = HotStuffQcMessage.decide(lider, 0, qc(HotStuffVote.Phase.COMMIT, bloco));
        int alturaAntes = bloco.getHeight();
        Object hashAntes = bloco.getHash();

        // muta a lista de pais DEPOIS de criar a mensagem e o QC (divida da base: Block.getParents e mutavel)
        bloco.getParents().clear();
        bloco.getParents().add(null);
        bloco.getParents().add(HOTSTUFF_GENESIS_BLOCK);

        entregaProposta(receptor, new Packet(lider, receptor, m));
        entregaQc(receptor, new Packet(lider, receptor, new HotStuffQcNetworkMessage(d)));
        Packet pv = new Packet(lider, receptor,
                new VoteMessage(new HotStuffVote(lider, HotStuffVote.Phase.PREPARE, 0, bloco, cfg)));
        receptor.processIncomingPacket(pv);

        long esperado = m.getSize() + d.getSize() + pv.getSize();
        assertEquals(esperado, hs(receptor).getBlockTraffic(bloco), "mesmo bloco apos a mutacao dos pais");
        assertEquals(m.getConsensusSize() + d.getSize() + pv.getSize(), hs(receptor).getConsensusMessageTraffic(bloco));
        assertEquals(alturaAntes, bloco.getHeight());
        assertTrue(hashAntes == bloco.getHash());
        assertEquals(0, hs(receptor).getBlockTraffic(HOTSTUFF_GENESIS_BLOCK));

        // e o corte tambem usa a mesma identidade
        hs(receptor).markLocallyFinalized(bloco);
        entregaQc(receptor, new Packet(lider, receptor, new HotStuffQcNetworkMessage(d)));
        assertEquals(esperado, hs(receptor).getBlockTraffic(bloco));
    }
}
