package jabs.ledgerdata.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.HotStuffConsensusConfig.SignatureMode;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.p2p.AbstractP2PConnections;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tamanhos de voto e QC por SignatureMode, sem simulador (nos stub). */
class HotStuffDataTest {

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

    private static HotStuffBlock genesis() {
        return new HotStuffBlock(1000, 0, 0.0, null, null);
    }

    private static List<Node> voters(int k) {
        List<Node> l = new ArrayList<>();
        for (int i = 0; i < k; i++) l.add(new StubNode(i));
        return l;
    }

    /** QC com o quorum 2f+1 de uma rede de n nos. */
    private static int qcSize(SignatureMode m, int n) {
        return new HotStuffQuorumCertificate(HotStuffVote.Phase.PREPARE, 1, genesis(),
                voters(HotStuffConsensusConfig.quorumSize(n)), n, cfg(m)).getSize();
    }

    private static int bitmap(int n) {
        return (n + 7) / 8;
    }

    /** Faixa de governanca (4, 16, 64, 128) mais validacao (400, 800). */
    private static final int[] NS = {4, 16, 64, 128, 400, 800};

    @Test
    void tamanhoDoVotoPorModo() {
        HotStuffBlock b = genesis();
        HotStuffVote ind = new HotStuffVote(new StubNode(0), HotStuffVote.Phase.PREPARE, 1, b, cfg(SignatureMode.INDIVIDUAL));
        HotStuffVote agg = new HotStuffVote(new StubNode(0), HotStuffVote.Phase.PREPARE, 1, b, cfg(SignatureMode.AGGREGATED));
        assertEquals(32 + 10 + 64, ind.getSize());
        assertEquals(32 + 10 + 96, agg.getSize());
        assertEquals(HotStuffVote.Phase.PREPARE, ind.getPhase());
        assertEquals(1, ind.getView());
        assertEquals(b, ind.getBlock());
    }

    @Test
    void fasesDeVotoNaoIncluemNewViewNemDecide() {
        List<String> nomes = new ArrayList<>();
        for (HotStuffVote.Phase p : HotStuffVote.Phase.values()) nomes.add(p.name());
        assertEquals(List.of("PREPARE", "PRE_COMMIT", "COMMIT"), nomes);
    }

    @Test
    void votoRecusaFaseNulaViewNegativaBlocoOuConfigNulos() {
        HotStuffBlock b = genesis();
        HotStuffConsensusConfig c = cfg(SignatureMode.INDIVIDUAL);
        assertThrows(IllegalArgumentException.class, () -> new HotStuffVote(new StubNode(0), null, 1, b, c));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffVote(new StubNode(0), HotStuffVote.Phase.PREPARE, -1, b, c));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffVote(new StubNode(0), HotStuffVote.Phase.PREPARE, 1, null, c));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffVote(new StubNode(0), HotStuffVote.Phase.PREPARE, 1, b, null));
    }

    private static HotStuffQuorumCertificate qc(HotStuffVote.Phase ph, long view, int n, int k, SignatureMode m) {
        return new HotStuffQuorumCertificate(ph, view, genesis(), voters(k), n, cfg(m));
    }

    private static HotStuffQuorumCertificate qcQuorum(HotStuffVote.Phase ph, long view, int n) {
        return qc(ph, view, n, HotStuffConsensusConfig.quorumSize(n), SignatureMode.INDIVIDUAL);
    }

    private static HotStuffQuorumCertificate qcQuorum2(int n) {
        return qc(PREPARE, 1, n, HotStuffConsensusConfig.quorumSize(n), SignatureMode.AGGREGATED);
    }

    private static final HotStuffVote.Phase PREPARE = HotStuffVote.Phase.PREPARE;
    private static final HotStuffVote.Phase PRE_COMMIT = HotStuffVote.Phase.PRE_COMMIT;
    private static final HotStuffVote.Phase COMMIT = HotStuffVote.Phase.COMMIT;

    @Test
    void mensagemDeQcTemOTamanhoDoQcENaoDeVoto() {
        for (SignatureMode m : SignatureMode.values()) {
            HotStuffQuorumCertificate commit = qc(COMMIT, 3, 7, 5, m);
            HotStuffQuorumCertificate prepare = qc(PREPARE, 3, 7, 5, m);
            HotStuffQcMessage decide = HotStuffQcMessage.decide(new StubNode(0), 3, commit);
            HotStuffQcMessage nv = HotStuffQcMessage.newView(new StubNode(0), 3, prepare);
            assertEquals(commit.getSize(), decide.getSize());
            assertEquals(commit.getSize(), HotStuffQcMessage.computeSize(commit));
            assertEquals(prepare.getSize(), nv.getSize());
            assertEquals(HotStuffQcMessage.Type.DECIDE, decide.getType());
            assertEquals(HotStuffQcMessage.Type.NEW_VIEW, nv.getType());
            assertEquals(3, decide.getView());
            assertTrue(decide.hasQc());
            assertEquals(commit, decide.getQc().orElseThrow());
        }
    }

    @Test
    void mensagemDeQcIndividualCresceComQuorumEDiferenteDoVoto() {
        HotStuffConsensusConfig c = cfg(SignatureMode.INDIVIDUAL);
        int q = HotStuffConsensusConfig.quorumSize(10);
        HotStuffQcMessage decide = HotStuffQcMessage.decide(new StubNode(0), 1, qcQuorum(COMMIT, 1, 10));
        assertEquals(32 + 10 + bitmap(10) + q * 64, decide.getSize());
        assertTrue(decide.getSize() > HotStuffVote.computeSize(genesis(), c));
    }

    // ---- regra (1): DECIDE exige commitQC ----
    @Test
    void decideRecusaQcQueNaoECommit() {
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(new StubNode(0), 1, qcQuorum(PREPARE, 1, 4)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(new StubNode(0), 1, qcQuorum(PRE_COMMIT, 1, 4)));
        assertEquals(COMMIT, HotStuffQcMessage.decide(new StubNode(0), 1, qcQuorum(COMMIT, 1, 4)).getQc().orElseThrow().getPhase());
    }

    // ---- regra (2): NEW_VIEW carrega prepareQC; ausencia explicita ----
    @Test
    void newViewRecusaQcQueNaoEPrepare() {
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(new StubNode(0), 1, qcQuorum(PRE_COMMIT, 1, 4)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(new StubNode(0), 1, qcQuorum(COMMIT, 1, 4)));
    }

    @Test
    void newViewSemQcEExplicitoEPequeno() {
        HotStuffConsensusConfig c = cfg(SignatureMode.INDIVIDUAL);
        HotStuffQcMessage m = HotStuffQcMessage.newViewWithoutPrepareQc(new StubNode(2), 0, 4, c);
        assertEquals(HotStuffQcMessage.Type.NEW_VIEW, m.getType());
        assertFalse(m.hasQc());
        assertTrue(m.getQc().isEmpty());
        assertEquals(c.getMessageOverheadBytes(), m.getSize());
        assertEquals(c.getMessageOverheadBytes(), HotStuffQcMessage.computeSizeWithoutQc(c));
    }

    @Test
    void newViewSemQcRecusaConfigNuloNMenorQue4ERemetenteForaDaRede() {
        HotStuffConsensusConfig c = cfg(SignatureMode.INDIVIDUAL);
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newViewWithoutPrepareQc(new StubNode(0), 0, 4, null));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newViewWithoutPrepareQc(new StubNode(0), 0, 3, c));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newViewWithoutPrepareQc(new StubNode(4), 0, 4, c));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.computeSizeWithoutQc(null));
    }

    // ---- regra (3): visao da mensagem >= visao do QC ----
    @Test
    void mensagemRecusaQcDeVisaoFutura() {
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(new StubNode(0), 2, qcQuorum(COMMIT, 3, 4)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(new StubNode(0), 2, qcQuorum(PREPARE, 3, 4)));
        // view maior que a do QC e coerente (QC antigo; ex.: visoes expiradas depois do prepareQC)
        assertEquals(5, HotStuffQcMessage.newView(new StubNode(0), 5, qcQuorum(PREPARE, 3, 4)).getView());
        assertEquals(3, HotStuffQcMessage.newView(new StubNode(0), 3, qcQuorum(PREPARE, 3, 4)).getView());
    }

    @Test
    void mensagemDeQcRecusaQcSenderNulosEViewNegativa() {
        HotStuffQuorumCertificate prepare = qcQuorum(PREPARE, 1, 4);
        HotStuffQuorumCertificate commit = qcQuorum(COMMIT, 1, 4);
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(new StubNode(0), 1, null));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(new StubNode(0), 1, null));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(null, 1, prepare));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(null, 1, commit));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(new StubNode(0), -1, prepare));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(new StubNode(0), -1, commit));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.computeSize(null));
    }

    @Test
    void mensagemDeQcRecusaRemetenteForaDeZeroAteN() {
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.decide(new StubNode(4), 1, qcQuorum(COMMIT, 1, 4)));
        assertThrows(IllegalArgumentException.class, () -> HotStuffQcMessage.newView(new StubNode(-1), 1, qcQuorum(PREPARE, 1, 4)));
    }

    @Test
    void mensagemDeQcNaoTemConstrutorPublico() {
        assertEquals(0, HotStuffQcMessage.class.getConstructors().length);
    }

    @Test
    void tamanhoDoVotoNaoDependeDeN() {
        HotStuffBlock b = genesis();
        assertEquals(new HotStuffVote(new StubNode(0), HotStuffVote.Phase.COMMIT, 5, b, cfg(SignatureMode.INDIVIDUAL)).getSize(),
                new HotStuffVote(new StubNode(99), HotStuffVote.Phase.COMMIT, 9, b, cfg(SignatureMode.INDIVIDUAL)).getSize());
    }

    @Test
    void qcIndividualCresceComAsAssinaturasEOBitmap() {
        int prev = -1;
        for (int n : NS) {
            int q = HotStuffConsensusConfig.quorumSize(n);
            int s = qcSize(SignatureMode.INDIVIDUAL, n);
            assertEquals(32 + 10 + bitmap(n) + q * 64, s, "n=" + n);
            assertTrue(s > prev, "n=" + n);
            prev = s;
        }
    }

    @Test
    void qcAgregadoCrescePeloBitmapApenas() {
        int prev = -1;
        for (int n : NS) {
            int s = qcSize(SignatureMode.AGGREGATED, n);
            assertEquals(32 + 10 + bitmap(n) + 96, s, "n=" + n);
            assertTrue(s > prev, "n=" + n);
            prev = s;
        }
        // so o bitmap varia: n=4 -> 1 B, n=800 -> 100 B
        assertEquals(99, qcSize(SignatureMode.AGGREGATED, 800) - qcSize(SignatureMode.AGGREGATED, 4));
    }

    @Test
    void bitmapEIgualNosDoisModosEAVantagemDoAgregadoESoDaAssinatura() {
        for (int n : NS) {
            int q = HotStuffConsensusConfig.quorumSize(n);
            int ind = qcSize(SignatureMode.INDIVIDUAL, n);
            int agg = qcSize(SignatureMode.AGGREGATED, n);
            assertEquals(q * 64 - 96, ind - agg, "diferenca = so assinaturas, n=" + n);
            assertTrue(agg < ind, "n=" + n);
        }
    }

    @Test
    void bitmapEOTetoDeNDividoPor8EmNNaoMultiploDeOito() {
        int[][] casos = {{4, 1}, {7, 1}, {8, 1}, {9, 2}, {10, 2}, {13, 2}, {17, 3}, {100, 13}, {401, 51}, {799, 100}};
        for (int[] c : casos) {
            assertEquals(c[1], bitmap(c[0]));
            assertEquals(c[1], cfg(SignatureMode.INDIVIDUAL).voterIdentifiersSize(c[0]), "n=" + c[0]);
            assertEquals(c[1], cfg(SignatureMode.AGGREGATED).voterIdentifiersSize(c[0]), "n=" + c[0]);
            assertEquals(32 + 10 + c[1] + 96, qcSize(SignatureMode.AGGREGATED, c[0]), "n=" + c[0]);
        }
    }

    @Test
    void bitmapVemDoNDoQcENaoDaQuantidadeDeVotantes() {
        // AGGREGATED: a assinatura e constante, entao a diferenca de tamanho e so o bitmap (apesar de 5 vs 533 votantes)
        HotStuffQuorumCertificate n8 = qcQuorum2(8);
        HotStuffQuorumCertificate n800 = qcQuorum2(800);
        assertEquals(99, n800.getSize() - n8.getSize());
        assertEquals(800, n800.getNetworkSize());
    }

    // ---- regra (4): invariantes do QC ----
    @Test
    void qcRecusaVotanteForaDeZeroAteNENInvalido() {
        // 5 votantes (ids 0..4) em n=4: id 4 fora de [0,4)
        assertThrows(IllegalArgumentException.class, () -> qc(PREPARE, 1, 4, 5, SignatureMode.INDIVIDUAL));
        // n < 4 (config)
        assertThrows(IllegalArgumentException.class, () -> qc(PREPARE, 1, 3, 3, SignatureMode.INDIVIDUAL));
        // id negativo
        List<Node> neg = new ArrayList<>(voters(2));
        neg.add(new StubNode(-1));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffQuorumCertificate(
                PREPARE, 1, genesis(), neg, 4, cfg(SignatureMode.INDIVIDUAL)));
        // votante nulo
        List<Node> comNulo = new ArrayList<>(voters(2));
        comNulo.add(null);
        assertThrows(IllegalArgumentException.class, () -> new HotStuffQuorumCertificate(
                PREPARE, 1, genesis(), comNulo, 4, cfg(SignatureMode.INDIVIDUAL)));
    }

    @Test
    void qcRecusaVotantesDuplicadosPorIdOuVazios() {
        List<Node> dup = voters(3);
        dup.add(dup.get(0));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffQuorumCertificate(
                PREPARE, 1, genesis(), dup, 4, cfg(SignatureMode.INDIVIDUAL)));
        // outro objeto, mesmo id: tambem e duplicata
        List<Node> mesmoId = voters(3);
        mesmoId.add(new StubNode(1));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffQuorumCertificate(
                PREPARE, 1, genesis(), mesmoId, 4, cfg(SignatureMode.INDIVIDUAL)));
        assertThrows(IllegalArgumentException.class, () -> qc(PREPARE, 1, 4, 0, SignatureMode.INDIVIDUAL));
    }

    @Test
    void qcSemQuorumNaoPodeExistir() {
        for (int n : new int[] {4, 7, 10, 13, 16, 64}) {
            int q = HotStuffConsensusConfig.quorumSize(n);
            final int nn = n;
            assertThrows(IllegalArgumentException.class, () -> qc(PREPARE, 1, nn, q - 1, SignatureMode.INDIVIDUAL), "n=" + n);
            assertEquals(q, qc(PREPARE, 1, n, q, SignatureMode.INDIVIDUAL).getVoterCount(), "n=" + n);
            assertEquals(n, qc(PREPARE, 1, n, n, SignatureMode.AGGREGATED).getVoterCount(), "todos votaram, n=" + n);
        }
        // computeSize tambem recusa (tamanho de QC sem quorum nao esta definido)
        assertThrows(IllegalArgumentException.class, () -> HotStuffQuorumCertificate.computeSize(
                genesis(), voters(2), 4, cfg(SignatureMode.INDIVIDUAL)));
    }

    @Test
    void qcRecusaFaseBlocoOuViewInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> qc(null, 1, 4, 3, SignatureMode.INDIVIDUAL));
        assertThrows(IllegalArgumentException.class, () -> qc(PREPARE, -1, 4, 3, SignatureMode.INDIVIDUAL));
        assertThrows(IllegalArgumentException.class, () -> new HotStuffQuorumCertificate(
                PREPARE, 1, null, voters(3), 4, cfg(SignatureMode.INDIVIDUAL)));
    }

    @Test
    void votoRecusaVotanteNulo() {
        assertThrows(IllegalArgumentException.class, () -> new HotStuffVote(null, PREPARE, 1, genesis(), cfg(SignatureMode.INDIVIDUAL)));
    }

    @Test
    void qcGuardaCopiaImutavelDosVotantes() {
        List<Node> v = voters(3);
        HotStuffQuorumCertificate qc = new HotStuffQuorumCertificate(
                HotStuffVote.Phase.COMMIT, 2, genesis(), v, 4, cfg(SignatureMode.INDIVIDUAL));
        v.clear();
        assertEquals(3, qc.getVoterCount());
        assertThrows(UnsupportedOperationException.class, () -> qc.getVoters().clear());
    }

    @Test
    void blocoEncadeiaPaiEAlturaETxs() {
        HotStuffBlock g = genesis();
        Set<HotStuffTx> txs = new HashSet<>();
        txs.add(new HotStuffTx(512, 32));
        txs.add(new HotStuffTx(512, 32));
        HotStuffBlockWithTx b = new HotStuffBlockWithTx(2000, 1, 1.0, null, g, txs);
        assertEquals(g, b.getParent());
        assertEquals(1, b.getHeight());
        assertEquals(2, b.getTransactionCount());
        assertEquals(32, b.getHash().getSize());
    }
}
