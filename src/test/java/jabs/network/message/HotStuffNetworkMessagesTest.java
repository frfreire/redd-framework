package jabs.network.message;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.HotStuffConsensusConfig.SignatureMode;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQcMessage;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.p2p.AbstractP2PConnections;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mensagens de rede do HotStuff (proposta e embrulho de QC): tamanhos e invariantes, sem simulador. */
class HotStuffNetworkMessagesTest {

    private static final HotStuffVote.Phase PREPARE = HotStuffVote.Phase.PREPARE;
    private static final HotStuffVote.Phase PRE_COMMIT = HotStuffVote.Phase.PRE_COMMIT;
    private static final HotStuffVote.Phase COMMIT = HotStuffVote.Phase.COMMIT;
    private static final int BLOCK_SIZE = 1000;
    private static final int[] NS = {4, 16, 64, 128, 400, 800};

    private static final class StubP2P extends AbstractP2PConnections {
        @Override public void connectToNetwork(Network network) { }
        @Override public boolean requestConnection(Node node) { return false; }
    }

    private static final class StubNode extends Node {
        StubNode(int id) { super(null, null, id, 1, 1, new StubP2P()); }
        @Override public void processIncomingPacket(Packet packet) { }
        @Override public void generateNewTransaction() { }
    }

    private static HotStuffConsensusConfig cfg(SignatureMode m) {
        return HotStuffConsensusConfig.builder().signatureMode(m)
                .voterEncoding(HotStuffConsensusConfig.VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0).batchSize(HotStuffConsensusConfig.batchSizeFor(1024, 512))
                .individualSignatureSizeBytes(64).aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96).build();
    }

    private static HotStuffConsensusConfig cfg25() {
        return HotStuffConsensusConfig.builder()
                .voterEncoding(HotStuffConsensusConfig.VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0).batchSize(2048).messageOverheadBytes(25)
                .individualSignatureSizeBytes(64).aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96).build();
    }

    private static HotStuffBlock block() {
        return new HotStuffBlock(BLOCK_SIZE, 0, 0.0, null, null);
    }

    private static List<Node> voters(int k) {
        List<Node> l = new ArrayList<>();
        for (int i = 0; i < k; i++) l.add(new StubNode(i));
        return l;
    }

    private static HotStuffQuorumCertificate qc(HotStuffVote.Phase ph, long view, HotStuffBlock b, int n, SignatureMode m) {
        return qc(ph, view, b, n, cfg(m));
    }

    private static HotStuffQuorumCertificate qc(HotStuffVote.Phase ph, long view, HotStuffBlock b, int n,
                                                HotStuffConsensusConfig c) {
        return new HotStuffQuorumCertificate(ph, view, b, voters(HotStuffConsensusConfig.quorumSize(n)), n, c);
    }

    private static int bitmap(int n) {
        return (n + 7) / 8;
    }

    private static final HotStuffConsensusConfig IND = cfg(SignatureMode.INDIVIDUAL);

    // ---------- tamanhos ----------

    @Test
    void mensagemDeQcTemOTamanhoDoQc() {
        HotStuffBlock b = block();
        for (SignatureMode m : SignatureMode.values()) {
            HotStuffQuorumCertificate commit = qc(COMMIT, 3, b, 7, m);
            HotStuffQuorumCertificate prepare = qc(PREPARE, 3, b, 7, m);
            HotStuffQcNetworkMessage decide = new HotStuffQcNetworkMessage(
                    HotStuffQcMessage.decide(new StubNode(0), 3, commit));
            HotStuffQcNetworkMessage nv = new HotStuffQcNetworkMessage(
                    HotStuffQcMessage.newView(new StubNode(1), 3, prepare));
            assertEquals(commit.getSize(), decide.getSize());
            assertEquals(prepare.getSize(), nv.getSize());
            assertEquals(decide.getQcMessage().getSize(), decide.getSize());
            assertEquals(HotStuffQcMessage.Type.DECIDE, decide.getQcMessage().getType());
        }
    }

    @Test
    void mensagemDeQcSemPrepareQcTemSoOOverhead() {
        HotStuffQcMessage vazio = HotStuffQcMessage.newViewWithoutPrepareQc(new StubNode(2), 0, 4, IND);
        HotStuffQcNetworkMessage m = new HotStuffQcNetworkMessage(vazio);
        assertEquals(IND.getMessageOverheadBytes(), m.getSize());
        assertSame(vazio, m.getQcMessage());
        assertFalse(m.getQcMessage().hasQc());
    }

    @Test
    void mensagemDeQcRecusaNulo() {
        assertThrows(IllegalArgumentException.class, () -> new HotStuffQcNetworkMessage(null));
    }

    @Test
    void propostaIndividualCresceComN() {
        int prev = -1;
        for (int n : NS) {
            HotStuffBlock b = block();
            HotStuffProposalMessage p = HotStuffProposalMessage.preCommit(
                    1, new StubNode(0), b, qc(PREPARE, 1, b, n, SignatureMode.INDIVIDUAL));
            int q = HotStuffConsensusConfig.quorumSize(n);
            assertEquals(32 + 10 + (32 + 10 + bitmap(n) + q * 64), p.getConsensusSize(), "n=" + n);
            assertTrue(p.getConsensusSize() > prev, "n=" + n);
            prev = p.getConsensusSize();
        }
    }

    @Test
    void propostaAgregadaCrescePeloBitmapApenas() {
        int prev = -1;
        for (int n : NS) {
            HotStuffBlock b = block();
            HotStuffProposalMessage p = HotStuffProposalMessage.preCommit(
                    1, new StubNode(0), b, qc(PREPARE, 1, b, n, SignatureMode.AGGREGATED));
            assertEquals(32 + 10 + (32 + 10 + bitmap(n) + 96), p.getConsensusSize(), "n=" + n);
            assertTrue(p.getConsensusSize() > prev, "n=" + n);
            prev = p.getConsensusSize();
        }
        HotStuffBlock b4 = block();
        HotStuffBlock b800 = block();
        int s4 = HotStuffProposalMessage.preCommit(1, new StubNode(0), b4,
                qc(PREPARE, 1, b4, 4, SignatureMode.AGGREGATED)).getConsensusSize();
        int s800 = HotStuffProposalMessage.preCommit(1, new StubNode(0), b800,
                qc(PREPARE, 1, b800, 800, SignatureMode.AGGREGATED)).getConsensusSize();
        assertEquals(99, s800 - s4); // so o bitmap: 1 B -> 100 B
    }

    @Test
    void propostaPrepareSeparaTotalDeConsenso() {
        HotStuffBlock b = block();
        HotStuffQuorumCertificate high = qc(PREPARE, 1, b, 7, SignatureMode.INDIVIDUAL);
        HotStuffProposalMessage com = HotStuffProposalMessage.prepare(2, new StubNode(2), b, high, 7);
        assertEquals(32 + 10 + high.getSize(), com.getConsensusSize());
        assertEquals(com.getConsensusSize() + BLOCK_SIZE, com.getTotalSize());
        assertEquals(com.getTotalSize(), com.getSize());

        HotStuffProposalMessage sem = HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(0), b, IND, 4);
        assertEquals(32 + 10, sem.getConsensusSize());
        assertEquals(32 + 10 + BLOCK_SIZE, sem.getTotalSize());
        assertEquals(sem.getTotalSize(), sem.getSize());
    }

    @Test
    void fasesSeguintesTemTotalIgualAoConsenso() {
        HotStuffBlock b = block();
        HotStuffProposalMessage pc = HotStuffProposalMessage.preCommit(1, new StubNode(1), b, qc(PREPARE, 1, b, 7, SignatureMode.INDIVIDUAL));
        HotStuffProposalMessage cm = HotStuffProposalMessage.commit(1, new StubNode(1), b, qc(PRE_COMMIT, 1, b, 7, SignatureMode.INDIVIDUAL));
        HotStuffProposalMessage dc = HotStuffProposalMessage.decide(1, new StubNode(1), b, qc(COMMIT, 1, b, 7, SignatureMode.INDIVIDUAL));
        for (HotStuffProposalMessage p : List.of(pc, cm, dc)) {
            assertEquals(p.getConsensusSize(), p.getTotalSize());
            assertEquals(p.getConsensusSize(), p.getSize());
            assertTrue(p.getTotalSize() < BLOCK_SIZE + p.getConsensusSize());
            assertEquals(32 + 10 + p.getQc().orElseThrow().getSize(), p.getSize());
        }
        assertEquals(HotStuffProposalMessage.Phase.PRE_COMMIT, pc.getPhase());
        assertEquals(HotStuffProposalMessage.Phase.COMMIT, cm.getPhase());
        assertEquals(HotStuffProposalMessage.Phase.DECIDE, dc.getPhase());
    }

    @Test
    void propostaUsaOOverheadDoConfig() {
        HotStuffConsensusConfig c = cfg25();
        // sem QC: o config e explicito
        HotStuffProposalMessage p = HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(0), block(), c, 4);
        assertEquals(32 + 25, p.getConsensusSize());
    }

    @Test
    void propostaComQcUsaOOverheadDoConfigDoQc() {
        HotStuffConsensusConfig c = cfg25();
        HotStuffBlock b = block();
        // QC e proposta com overhead 25 B: 32 + 25 (proposta) + QC (32 + 25 + bitmap + 3 * 64)
        HotStuffQuorumCertificate prepareQc = qc(PREPARE, 1, b, 4, c);
        HotStuffQuorumCertificate precommitQc = qc(PRE_COMMIT, 1, b, 4, c);
        HotStuffQuorumCertificate commitQc = qc(COMMIT, 1, b, 4, c);
        int qcSize = 32 + 25 + bitmap(4) + HotStuffConsensusConfig.quorumSize(4) * 64;
        assertEquals(qcSize, prepareQc.getSize());
        HotStuffProposalMessage pc = HotStuffProposalMessage.preCommit(1, new StubNode(0), b, prepareQc);
        HotStuffProposalMessage cm = HotStuffProposalMessage.commit(1, new StubNode(0), b, precommitQc);
        HotStuffProposalMessage dc = HotStuffProposalMessage.decide(1, new StubNode(0), b, commitQc);
        HotStuffProposalMessage pr = HotStuffProposalMessage.prepare(1, new StubNode(0), b, prepareQc, 4);
        for (HotStuffProposalMessage m : List.of(pc, cm, dc, pr)) {
            assertEquals(32 + 25 + qcSize, m.getConsensusSize());
        }
        assertEquals(32 + 25 + qcSize + BLOCK_SIZE, pr.getTotalSize());
    }

    @Test
    void qcExpoeOConfigComQueFoiConstruido() {
        HotStuffConsensusConfig c = cfg25();
        HotStuffBlock b = block();
        HotStuffQuorumCertificate q = qc(PREPARE, 1, b, 4, c);
        assertSame(c, q.getConfig());
        // expor o config nao muda o tamanho do QC
        assertEquals(HotStuffQuorumCertificate.computeSize(b, q.getVoters(), 4, c), q.getSize());
        assertEquals(c.getMessageOverheadBytes(), q.getConfig().getMessageOverheadBytes());
    }

    @Test
    void naoExisteComoPassarConfigDivergenteAUmaFabricaComQc() {
        // as fabricas que recebem QC usam o config do proprio QC: nao tem parametro de config
        Set<String> comQc = Set.of("prepare", "preCommit", "commit", "decide");
        int visitadas = 0;
        for (Method m : HotStuffProposalMessage.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) continue;
            boolean temQc = false;
            int configs = 0;
            for (Class<?> t : m.getParameterTypes()) {
                if (t == HotStuffConsensusConfig.class) configs++;
                if (t == HotStuffQuorumCertificate.class) temQc = true;
            }
            if (comQc.contains(m.getName())) {
                visitadas++;
                assertTrue(temQc, m.getName() + " deve receber QC");
                assertEquals(0, configs, m.getName() + " nao pode aceitar config alem do QC");
            }
            // nenhuma fabrica publica aceita QC e config ao mesmo tempo
            assertFalse(temQc && configs > 0, m.getName() + " aceita QC e config separados");
        }
        assertEquals(4, visitadas);
        // a unica fabrica com config explicito e a sem QC, com exatamente um config
        int semQc = 0;
        for (Method m : HotStuffProposalMessage.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> t : m.getParameterTypes()) {
                if (t == HotStuffConsensusConfig.class) {
                    assertEquals("prepareWithoutHighQc", m.getName());
                    semQc++;
                }
            }
        }
        assertEquals(1, semQc);
    }

    @Test
    void mensagemDeQcSoRecebeQcOuConfigSemQc() {
        // mesma fragilidade? newView/decide so recebem o QC (tamanho = qc.getSize()); config so existe sem QC
        for (Method m : HotStuffQcMessage.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) continue;
            boolean temQc = false;
            boolean temConfig = false;
            for (Class<?> t : m.getParameterTypes()) {
                if (t == HotStuffQuorumCertificate.class) temQc = true;
                if (t == HotStuffConsensusConfig.class) temConfig = true;
            }
            assertFalse(temQc && temConfig, m.getName() + " aceita QC e config separados");
            if (m.getName().equals("decide") || m.getName().equals("newView")) {
                assertFalse(temConfig, m.getName());
            }
        }
    }

    @Test
    void acessores() {
        HotStuffBlock b = block();
        Node leader = new StubNode(1);
        HotStuffQuorumCertificate high = qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL);
        HotStuffProposalMessage p = HotStuffProposalMessage.prepare(5, leader, b, high, 4);
        assertEquals(HotStuffProposalMessage.Phase.PREPARE, p.getPhase());
        assertEquals(5, p.getView());
        assertSame(leader, p.getSender());
        assertSame(b, p.getBlock());
        assertTrue(p.hasQc());
        assertSame(high, p.getQc().orElseThrow());
        HotStuffProposalMessage sem = HotStuffProposalMessage.prepareWithoutHighQc(0, leader, b, IND, 4);
        assertFalse(sem.hasQc());
        assertTrue(sem.getQc().isEmpty());
    }

    @Test
    void propostaNaoTemConstrutorPublico() {
        assertEquals(0, HotStuffProposalMessage.class.getConstructors().length);
        for (Constructor<?> c : HotStuffProposalMessage.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(c.getModifiers()));
        }
    }

    @Test
    void faseDePropostaTemAsQuatroFases() {
        List<String> nomes = new ArrayList<>();
        for (HotStuffProposalMessage.Phase p : HotStuffProposalMessage.Phase.values()) nomes.add(p.name());
        assertEquals(List.of("PREPARE", "PRE_COMMIT", "COMMIT", "DECIDE"), nomes);
    }

    // ---------- invariantes: fase do QC por tipo de proposta ----------

    @Test
    void preCommitExigeQcDeFasePrepare() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, new StubNode(0), b, qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, new StubNode(0), b, qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, new StubNode(0), b, null));
        assertEquals(PREPARE, HotStuffProposalMessage.preCommit(1, new StubNode(0), b, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL)).getQc().orElseThrow().getPhase());
    }

    @Test
    void commitExigeQcDeFasePreCommit() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, new StubNode(0), b, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, new StubNode(0), b, qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, new StubNode(0), b, null));
        assertEquals(PRE_COMMIT, HotStuffProposalMessage.commit(1, new StubNode(0), b, qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)).getQc().orElseThrow().getPhase());
    }

    @Test
    void decideExigeQcDeFaseCommit() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, new StubNode(0), b, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, new StubNode(0), b, qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, new StubNode(0), b, null));
        assertEquals(COMMIT, HotStuffProposalMessage.decide(1, new StubNode(0), b, qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)).getQc().orElseThrow().getPhase());
    }

    @Test
    void prepareAceitaHighQcDeFasePrepareOuNada() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, new StubNode(0), b, qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL), 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, new StubNode(0), b, qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL), 4));
        // ausencia nao passa pela fabrica com QC: nulo nao e "nenhum" silencioso
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, new StubNode(0), b, null, 4));
        HotStuffProposalMessage ok = HotStuffProposalMessage.prepare(1, new StubNode(0), b, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL), 4);
        assertTrue(ok.hasQc());
        assertTrue(HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(0), b, IND, 4).getQc().isEmpty());
    }

    @Test
    void prepareComHighQcPodeProporBlocoDiferenteDoQc() {
        // o bloco novo estende o do highQC (a conferir com o artigo); a camada de mensagem nao o impoe
        HotStuffBlock antigo = block();
        HotStuffBlock novo = new HotStuffBlock(BLOCK_SIZE, 1, 1.0, null, antigo);
        HotStuffProposalMessage p = HotStuffProposalMessage.prepare(2, new StubNode(2), novo,
                qc(PREPARE, 1, antigo, 4, SignatureMode.INDIVIDUAL), 4);
        assertSame(novo, p.getBlock());
    }

    // ---------- invariantes: view, bloco, remetente, config ----------

    @Test
    void propostaRecusaQcDeVisaoFutura() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(2, new StubNode(0), b, qc(PREPARE, 3, b, 4, SignatureMode.INDIVIDUAL), 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(2, new StubNode(0), b, qc(PREPARE, 3, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(2, new StubNode(0), b, qc(PRE_COMMIT, 3, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(2, new StubNode(0), b, qc(COMMIT, 3, b, 4, SignatureMode.INDIVIDUAL)));
        // view == qc.view e view > qc.view sao coerentes
        assertEquals(3, HotStuffProposalMessage.preCommit(3, new StubNode(0), b, qc(PREPARE, 3, b, 4, SignatureMode.INDIVIDUAL)).getView());
        assertEquals(5, HotStuffProposalMessage.prepare(5, new StubNode(0), b, qc(PREPARE, 3, b, 4, SignatureMode.INDIVIDUAL), 4).getView());
    }

    @Test
    void propostaRecusaViewNegativa() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(-1, new StubNode(0), b, IND, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(-1, new StubNode(0), b, qc(PREPARE, 0, b, 4, SignatureMode.INDIVIDUAL), 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(-1, new StubNode(0), b, qc(PREPARE, 0, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(-1, new StubNode(0), b, qc(PRE_COMMIT, 0, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(-1, new StubNode(0), b, qc(COMMIT, 0, b, 4, SignatureMode.INDIVIDUAL)));
    }

    @Test
    void fasesEncadeadasExigemOMesmoBlocoDoQc() {
        HotStuffBlock b = block();
        HotStuffBlock outro = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, new StubNode(0), outro, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, new StubNode(0), outro, qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, new StubNode(0), outro, qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
    }

    @Test
    void propostaRecusaRemetenteBlocoEConfigNulos() {
        HotStuffBlock b = block();
        HotStuffQuorumCertificate p = qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL);
        HotStuffQuorumCertificate pc = qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL);
        HotStuffQuorumCertificate c = qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL);
        // remetente
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, null, b, p, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(1, null, b, IND, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, null, b, p));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, null, b, pc));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, null, b, c));
        // bloco
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, new StubNode(0), null, p, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(1, new StubNode(0), null, IND, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, new StubNode(0), null, p));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, new StubNode(0), null, pc));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, new StubNode(0), null, c));
        // config: so a fabrica sem QC o recebe; nas demais ele vem do QC (nao nulo por construcao)
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(1, new StubNode(0), b, null, 4));
    }

    @Test
    void propostaRecusaRemetenteForaDeZeroAteN() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(4), b, IND, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(-1), b, IND, 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, new StubNode(4), b, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL), 4));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.preCommit(1, new StubNode(4), b, qc(PREPARE, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.commit(1, new StubNode(-1), b, qc(PRE_COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.decide(1, new StubNode(7), b, qc(COMMIT, 1, b, 4, SignatureMode.INDIVIDUAL)));
        // n maior aceita o mesmo id
        assertEquals(4, HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(4), b, IND, 7).getSender().getNodeID());
    }

    @Test
    void propostaRecusaNMenorQue4EnDivergenteDoQc() {
        HotStuffBlock b = block();
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepareWithoutHighQc(0, new StubNode(0), b, IND, 3));
        assertThrows(IllegalArgumentException.class, () -> HotStuffProposalMessage.prepare(1, new StubNode(0), b, qc(PREPARE, 1, b, 7, SignatureMode.INDIVIDUAL), 4));
    }
}
