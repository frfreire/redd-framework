package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote.Phase;
import jabs.network.node.nodes.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VoteAggregator: quorum exato, votante distinto, baldes por (fase, visao, bloco), formacao unica, determinismo. */
class VoteAggregatorTest {

    private static final int[] NS = {4, 7, 10, 16, 64, 128};
    private final HotStuffConsensusConfig cfg = Fixtures.cfg();
    private final HotStuffBlock b = new HotStuffBlock(1000, 1, 0.0, null, null);
    private final HotStuffBlock other = new HotStuffBlock(1000, 1, 0.0, null, null);

    private static List<Integer> ids(HotStuffQuorumCertificate qc) {
        List<Integer> l = new ArrayList<>();
        for (Node v : qc.getVoters()) l.add(v.getNodeID());
        return l;
    }

    @Test
    void quorumExatoQMenosUmNaoFormaQForma() {
        for (int n : NS) {
            int q = HotStuffConsensusConfig.quorumSize(n);
            VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
            for (int i = 0; i < q - 1; i++) {
                assertTrue(agg.add(Fixtures.vote(i, Phase.PREPARE, 3, b)));
                assertTrue(agg.tryFormQc(Phase.PREPARE, 3, b, cfg, n).isEmpty(), "n=" + n + " i=" + i);
            }
            assertEquals(q - 1, agg.distinctVoters(Phase.PREPARE, 3, b));
            assertTrue(agg.add(Fixtures.vote(q - 1, Phase.PREPARE, 3, b)));
            Optional<HotStuffQuorumCertificate> qc = agg.tryFormQc(Phase.PREPARE, 3, b, cfg, n);
            assertTrue(qc.isPresent(), "n=" + n);
            assertEquals(q, qc.get().getVoterCount());
            assertEquals(Phase.PREPARE, qc.get().getPhase());
            assertEquals(3, qc.get().getView());
            assertSame(b, qc.get().getBlock());
            assertEquals(n, qc.get().getNetworkSize());
        }
    }

    @Test
    void doisFNaoBasta() {
        for (int n : NS) {
            int f = HotStuffConsensusConfig.maxFaultyNodes(n);
            VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
            for (int i = 0; i < 2 * f; i++) agg.add(Fixtures.vote(i, Phase.COMMIT, 1, b));
            assertTrue(agg.tryFormQc(Phase.COMMIT, 1, b, cfg, n).isEmpty(), "n=" + n);
        }
    }

    @Test
    void quorumEmN4N7N10EhOEsperado() {
        assertEquals(3, HotStuffConsensusConfig.quorumSize(4));
        assertEquals(5, HotStuffConsensusConfig.quorumSize(7));
        assertEquals(7, HotStuffConsensusConfig.quorumSize(10));
    }

    @Test
    void votoRepetidoNaoContaDuasVezes() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        assertTrue(agg.add(Fixtures.vote(0, Phase.PREPARE, 1, b)));
        assertFalse(agg.add(Fixtures.vote(0, Phase.PREPARE, 1, b)));
        assertFalse(agg.add(Fixtures.vote(0, Phase.PREPARE, 1, b))); // outro objeto Node, mesmo id
        assertTrue(agg.add(Fixtures.vote(1, Phase.PREPARE, 1, b)));
        assertEquals(2, agg.distinctVoters(Phase.PREPARE, 1, b));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isEmpty()); // 2 < 3
        // repetir muitas vezes ate "passar" de 3 votos totais nao forma QC
        for (int i = 0; i < 10; i++) agg.add(Fixtures.vote(1, Phase.PREPARE, 1, b));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isEmpty());
        agg.add(Fixtures.vote(2, Phase.PREPARE, 1, b));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isPresent());
    }

    @Test
    void votoDeOutraFaseVisaoOuBlocoNaoConta() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        agg.add(Fixtures.vote(0, Phase.PREPARE, 1, b));
        agg.add(Fixtures.vote(1, Phase.PREPARE, 1, b));
        agg.add(Fixtures.vote(2, Phase.PRE_COMMIT, 1, b)); // outra fase
        agg.add(Fixtures.vote(3, Phase.PREPARE, 2, b));    // outra visao
        agg.add(Fixtures.vote(3, Phase.PREPARE, 1, other)); // outro bloco
        assertEquals(2, agg.distinctVoters(Phase.PREPARE, 1, b));
        assertEquals(1, agg.distinctVoters(Phase.PRE_COMMIT, 1, b));
        assertEquals(1, agg.distinctVoters(Phase.PREPARE, 2, b));
        assertEquals(1, agg.distinctVoters(Phase.PREPARE, 1, other));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isEmpty());
        assertTrue(agg.tryFormQc(Phase.PRE_COMMIT, 1, b, cfg, 4).isEmpty());
        // o terceiro voto certo completa o balde certo, e so ele
        agg.add(Fixtures.vote(2, Phase.PREPARE, 1, b));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isPresent());
        assertTrue(agg.tryFormQc(Phase.PRE_COMMIT, 1, b, cfg, 4).isEmpty());
    }

    @Test
    void consultaDeBaldeSemVotosEhVazia() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        assertEquals(0, agg.distinctVoters(Phase.COMMIT, 9, b));
        assertTrue(agg.tryFormQc(Phase.COMMIT, 9, b, cfg, 4).isEmpty());
    }

    @Test
    void tryFormQcFormaUmaSoVezPorBalde() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        for (int i = 0; i < 3; i++) agg.add(Fixtures.vote(i, Phase.PREPARE, 1, b));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isPresent());
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isEmpty());
        agg.add(Fixtures.vote(3, Phase.PREPARE, 1, b)); // voto extra depois do QC
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).isEmpty());
        // balde vizinho continua independente
        for (int i = 0; i < 3; i++) agg.add(Fixtures.vote(i, Phase.PRE_COMMIT, 1, b));
        assertTrue(agg.tryFormQc(Phase.PRE_COMMIT, 1, b, cfg, 4).isPresent());
        assertTrue(agg.tryFormQc(Phase.PRE_COMMIT, 1, b, cfg, 4).isEmpty());
    }

    @Test
    void votosAdiantadosSaoAcumuladosSemPropostaPrevia() {
        // o acumulador nunca recebe a proposta: votos chegam "antes" e valem
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        for (int i = 0; i < 5; i++) assertTrue(agg.add(Fixtures.vote(i, Phase.PREPARE, 40, b)));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 40, b, cfg, 7).isPresent());
    }

    @Test
    void qcTemExatamenteOsPrimeirosQuorumVotantesEmOrdemDeInsercao() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        int[] ordem = {3, 0, 2, 1}; // n=4, q=3; 4 votos antes de pedir
        for (int id : ordem) agg.add(Fixtures.vote(id, Phase.PREPARE, 1, b));
        HotStuffQuorumCertificate qc = agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).orElseThrow();
        assertEquals(List.of(3, 0, 2), ids(qc));
    }

    @Test
    void mesmaSequenciaDeVotosGeraMesmoQcEMesmaOrdem() {
        for (int n : NS) {
            int q = HotStuffConsensusConfig.quorumSize(n);
            List<Integer> seq = new ArrayList<>();
            for (int i = 0; i < n; i++) seq.add((n - 1 - i + n / 3) % n); // permutacao fixa (rotacao+inversao) de 0..n-1
            List<HotStuffQuorumCertificate> qcs = new ArrayList<>();
            for (int rodada = 0; rodada < 2; rodada++) {
                VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
                for (int id : seq) agg.add(Fixtures.vote(id, Phase.COMMIT, 2, b));
                Optional<HotStuffQuorumCertificate> qc = agg.tryFormQc(Phase.COMMIT, 2, b, cfg, n);
                assertTrue(qc.isPresent(), "n=" + n);
                qcs.add(qc.get());
            }
            assertEquals(ids(qcs.get(0)), ids(qcs.get(1)), "n=" + n);
            assertEquals(qcs.get(0).getSize(), qcs.get(1).getSize());
            assertEquals(q, qcs.get(0).getVoterCount());
        }
    }

    @Test
    void qcNaoDependeDeQuandoTryFormQcFoiChamado() {
        VoteAggregator<HotStuffBlock> cedo = new VoteAggregator<>();
        VoteAggregator<HotStuffBlock> tarde = new VoteAggregator<>();
        for (int i = 0; i < 4; i++) {
            cedo.add(Fixtures.vote(i, Phase.PREPARE, 1, b));
            tarde.add(Fixtures.vote(i, Phase.PREPARE, 1, b));
            if (i == 2) {
                HotStuffQuorumCertificate qc = cedo.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).orElseThrow();
                assertEquals(List.of(0, 1, 2), ids(qc));
            }
        }
        assertEquals(List.of(0, 1, 2), ids(tarde.tryFormQc(Phase.PREPARE, 1, b, cfg, 4).orElseThrow()));
    }

    @Test
    void combinacoesInvalidasLancam() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        assertThrows(IllegalArgumentException.class, () -> agg.add(null));
        assertThrows(IllegalArgumentException.class, () -> agg.distinctVoters(null, 1, b));
        assertThrows(IllegalArgumentException.class, () -> agg.distinctVoters(Phase.PREPARE, 1, null));
        assertThrows(IllegalArgumentException.class, () -> agg.tryFormQc(null, 1, b, cfg, 4));
        assertThrows(IllegalArgumentException.class, () -> agg.tryFormQc(Phase.PREPARE, 1, null, cfg, 4));
        assertThrows(IllegalArgumentException.class, () -> agg.tryFormQc(Phase.PREPARE, 1, b, null, 4));
        assertThrows(IllegalArgumentException.class, () -> agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 3)); // n < 4
        assertThrows(IllegalArgumentException.class, () -> agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 0));
    }

    @Test
    void votanteForaDeRangeEmTryFormQcLancaENaoMarcaFormado() {
        VoteAggregator<HotStuffBlock> agg = new VoteAggregator<>();
        agg.add(Fixtures.vote(0, Phase.PREPARE, 1, b));
        agg.add(Fixtures.vote(1, Phase.PREPARE, 1, b));
        agg.add(Fixtures.vote(9, Phase.PREPARE, 1, b)); // id 9 em rede n=4
        assertThrows(IllegalArgumentException.class, () -> agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 4));
        // com n=10 o mesmo balde e valido (q=7 nao e atingido: 3 votos)
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 10).isEmpty());
        for (int i = 2; i < 6; i++) agg.add(Fixtures.vote(i, Phase.PREPARE, 1, b));
        assertTrue(agg.tryFormQc(Phase.PREPARE, 1, b, cfg, 10).isPresent());
    }
}
