package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LeaderCore isolado: quorum exato, descartes contados, uma proposta por fase e visao, highQC, NEW_VIEW. */
class LeaderCoreTest {
    private final HotStuffConsensusConfig cfg = Fixtures.cfg();
    private final HotStuffBlock genesis = new HotStuffBlock(0, 0, 0.0, null, null);
    private final DiscardCounters discards = new DiscardCounters();
    private final List<Object[]> proposerCalls = new ArrayList<>();
    private LeaderCore core;
    private int n;

    /** Monta o LeaderCore do no {@code ownId} numa rede de n nos. */
    private void monta(int n, int ownId) {
        this.n = n;
        this.core = new LeaderCore(n, ownId, 0L, new ViewLeaders(cfg, n), cfg, genesis,
                (view, parent) -> {
                    proposerCalls.add(new Object[] {view, parent});
                    return new HotStuffBlock(1000, parent.getHeight() + 1, 0.0, null, parent);
                },
                new VoteAggregator<>(), new NewViewAggregator(), discards);
    }

    private static int f(int n) { return HotStuffConsensusConfig.maxFaultyNodes(n); }
    private static int q(int n) { return HotStuffConsensusConfig.quorumSize(n); }

    /**
     * Faz o que as replicas fariam no start (2b-2): n-f NEW_VIEW sem QC ao lider da visao inicial (0). O PREPARE sai
     * so no ultimo; devolve o bloco proposto.
     */
    private HotStuffBlock iniciaVisaoInicial() {
        List<LeaderAction> acoes = new ArrayList<>();
        for (int id = 0; id < q(n); id++) {
            List<LeaderAction> a = core.onNewView(0, id, Optional.empty());
            if (id < q(n) - 1) {
                assertTrue(a.isEmpty(), "n=" + n + ": PREPARE antes de n-f NEW_VIEW (id " + id + ")");
            }
            acoes.addAll(a);
        }
        assertEquals(1, acoes.size());
        assertEquals(Phase.PREPARE, acoes.get(0).phase());
        assertTrue(acoes.get(0).qc().isEmpty(), "visao inicial: highQC = bottom");
        assertSame(genesis, acoes.get(0).block().getFixedParent(), "visao inicial: pai = genesis");
        return acoes.get(0).block();
    }

    private List<LeaderAction> votos(HotStuffVote.Phase fase, long view, HotStuffBlock b, int de, int ate) {
        List<LeaderAction> out = new ArrayList<>();
        for (int id = de; id < ate; id++) {
            out.addAll(core.onVote(Fixtures.vote(id, fase, view, b)));
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 10})
    void quorumExato2fNaoFormaQcE2fMais1Forma(int n) {
        monta(n, 0);
        HotStuffBlock b = iniciaVisaoInicial();
        for (HotStuffVote.Phase fase : HotStuffVote.Phase.values()) {
            List<LeaderAction> antes = votos(fase, 0, b, 0, 2 * f(n));
            assertTrue(antes.isEmpty(), "n=" + n + " fase " + fase + ": 2f votos nao formam QC");
            List<LeaderAction> depois = votos(fase, 0, b, 2 * f(n), 2 * f(n) + 1);
            assertEquals(1, depois.size(), "n=" + n + " fase " + fase + ": 2f+1 formam QC");
            LeaderAction a = depois.get(0);
            assertEquals(LeaderCore.nextPhase(fase), a.phase());
            assertEquals(q(n), a.qc().get().getVoterCount());
            assertEquals(fase, a.qc().get().getPhase());
            assertSame(b, a.qc().get().getBlock());
        }
        assertEquals(0, discards.total());
    }

    @Test
    void responsivoAvancaNoVotoQueCompletaOQuorumSemEsperarOsDemais() {
        monta(7, 0);
        HotStuffBlock b = iniciaVisaoInicial();
        assertEquals(1, votos(HotStuffVote.Phase.PREPARE, 0, b, 0, q(7)).size()); // 5 de 7, os 2 restantes nao chegaram
    }

    @Test
    void votosAMaisDepoisDoQuorumSaoContadosComoTardiosEnaoRepropoem() {
        monta(4, 0);
        HotStuffBlock b = iniciaVisaoInicial();
        assertEquals(1, votos(HotStuffVote.Phase.PREPARE, 0, b, 0, 4).size());
        assertEquals(1, discards.get(DiscardReason.LATE_VOTE));
        assertEquals(1, discards.total());
    }

    @Test
    void liderNaoProporDuasVezesCadaFaseNemADeVisaoInicial() {
        monta(4, 0);
        HotStuffBlock b = iniciaVisaoInicial();
        assertEquals(1, proposerCalls.size(), "BlockProposer chamado uma vez");
        assertSame(b, core.curProposal(0).get());
        List<LeaderAction> todas = new ArrayList<>();
        for (HotStuffVote.Phase fase : HotStuffVote.Phase.values()) {
            // ate 3 rodadas de votos de todos, com repeticao: o QC so forma uma vez
            todas.addAll(votos(fase, 0, b, 0, 4));
            todas.addAll(votos(fase, 0, b, 0, 4));
        }
        assertEquals(3, todas.size());
        assertEquals(List.of(Phase.PRE_COMMIT, Phase.COMMIT, Phase.DECIDE),
                todas.stream().map(LeaderAction::phase).toList());
        assertEquals(1, proposerCalls.size());
        // NEW_VIEW tardio para a visao ja iniciada nao reabre a proposta
        assertTrue(core.onNewView(0, 3, Optional.empty()).isEmpty()); // o 4o remetente distinto, ja tardio
        assertEquals(1, discards.get(DiscardReason.UNNEEDED_NEW_VIEW));
        assertEquals(1, proposerCalls.size());
    }

    @Test
    void votoDeBlocoVisaoOuFaseErradosEDescartadoEContado() {
        monta(4, 0);
        HotStuffBlock b = iniciaVisaoInicial();
        HotStuffBlock outro = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 0, outro)).isEmpty());
        assertEquals(1, discards.get(DiscardReason.VOTE_WRONG_BLOCK));
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.COMMIT, 0, b)).isEmpty());
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PRE_COMMIT, 0, b)).isEmpty());
        assertEquals(2, discards.get(DiscardReason.VOTE_PHASE_OUT_OF_ORDER));
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 4, b)).isEmpty()); // lidera a 4, nao iniciou
        assertEquals(1, discards.get(DiscardReason.VOTE_VIEW_NOT_STARTED));
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 1, b)).isEmpty()); // visao 1 e do no 1
        assertEquals(1, discards.get(DiscardReason.NOT_VIEW_LEADER));
        assertTrue(core.onVote(Fixtures.vote(9, HotStuffVote.Phase.PREPARE, 0, b)).isEmpty());
        assertEquals(1, discards.get(DiscardReason.SENDER_OUT_OF_RANGE));
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 0, b)).isEmpty());
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 0, b)).isEmpty());
        assertEquals(1, discards.get(DiscardReason.DUPLICATE_VOTE));
        assertEquals(7, discards.total(), "nada some em silencio: 1+2+1+1+1+1");
        // nenhum voto descartado contou para o quorum: com 1 voto valido ainda faltam 2
        assertTrue(core.onVote(Fixtures.vote(2, HotStuffVote.Phase.PREPARE, 0, b)).isEmpty());
        assertEquals(1, core.onVote(Fixtures.vote(3, HotStuffVote.Phase.PREPARE, 0, b)).size());
    }

    @Test
    void votoRepetidoNaoCompletaQuorum() {
        monta(4, 0);
        HotStuffBlock b = iniciaVisaoInicial();
        for (int i = 0; i < 5; i++) {
            assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 0, b)).isEmpty());
        }
        assertEquals(4, discards.get(DiscardReason.DUPLICATE_VOTE));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 10})
    void newViewInsuficienteNaoPropoeESuficientePropoe(int n) {
        monta(n, 1); // lidera as visoes 1, 1+n, ...
        long alvo = 1;
        for (int id = 0; id < 2 * f(n); id++) {
            assertTrue(core.onNewView(alvo, id, Optional.empty()).isEmpty(), "n=" + n + " id " + id);
        }
        assertFalse(core.hasStarted(alvo));
        assertEquals(0, proposerCalls.size());
        List<LeaderAction> a = core.onNewView(alvo, 2 * f(n), Optional.empty());
        assertEquals(1, a.size());
        assertEquals(Phase.PREPARE, a.get(0).phase());
        assertTrue(a.get(0).qc().isEmpty(), "todos bottom: highQC e bottom");
        assertSame(genesis, a.get(0).block().getFixedParent());
        assertEquals(0, discards.total());
    }

    @Test
    void newViewDuplicadoNaoCompletaQuorumEForaDeLugarEContado() {
        monta(4, 1);
        for (int i = 0; i < 4; i++) {
            assertTrue(core.onNewView(1, 0, Optional.empty()).isEmpty());
        }
        assertEquals(3, discards.get(DiscardReason.DUPLICATE_NEW_VIEW));
        assertTrue(core.onNewView(2, 0, Optional.empty()).isEmpty()); // visao 2 e do no 2
        assertEquals(1, discards.get(DiscardReason.NOT_VIEW_LEADER));
        assertTrue(core.onNewView(1, 8, Optional.empty()).isEmpty());
        assertEquals(1, discards.get(DiscardReason.SENDER_OUT_OF_RANGE));
        HotStuffBlock x = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        assertTrue(core.onNewView(1, 1, Optional.of(Fixtures.qc(HotStuffVote.Phase.COMMIT, 0, x))).isEmpty());
        assertTrue(core.onNewView(1, 1, Optional.of(Fixtures.qc(HotStuffVote.Phase.PREPARE, 1, x))).isEmpty()); // view >= alvo
        assertTrue(core.onNewView(1, 1, Optional.of(Fixtures.qc(HotStuffVote.Phase.PREPARE, 0, x, 7))).isEmpty()); // outro n
        assertEquals(3, discards.get(DiscardReason.INCOHERENT_QC));
        assertEquals(3 + 1 + 1 + 3, discards.total());
    }

    @Test
    void highQcMaiorVenceEOBlocoProspostoOEstende() {
        monta(4, 1);
        HotStuffBlock x = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        HotStuffBlock y = new HotStuffBlock(1000, 2, 0.0, null, x);
        HotStuffQuorumCertificate qx = Fixtures.qc(HotStuffVote.Phase.PREPARE, 0, x);
        HotStuffQuorumCertificate qy = Fixtures.qc(HotStuffVote.Phase.PREPARE, 3, y);
        // o alvo e a visao 5 (lider = 5 mod 4 = 1)
        assertTrue(core.onNewView(5, 0, Optional.of(qy)).isEmpty());
        assertTrue(core.onNewView(5, 2, Optional.of(qx)).isEmpty());
        List<LeaderAction> a = core.onNewView(5, 3, Optional.empty());
        assertEquals(1, a.size());
        assertSame(qy, a.get(0).qc().get(), "highQC = o de maior visao, independente da ordem de chegada");
        assertSame(y, a.get(0).block().getFixedParent(), "curProposal estende o bloco do highQC");
        assertEquals(y.getHeight() + 1, a.get(0).block().getHeight());
        assertSame(y, proposerCalls.get(0)[1]);
        assertEquals(5L, proposerCalls.get(0)[0]);
    }

    @Test
    void highQcDeMesmaVisaoDesempataPeloPrimeiroRecebido() {
        monta(4, 1);
        HotStuffBlock x = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        HotStuffBlock x2 = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        HotStuffQuorumCertificate q1 = Fixtures.qc(HotStuffVote.Phase.PREPARE, 2, x);
        HotStuffQuorumCertificate q2 = Fixtures.qc(HotStuffVote.Phase.PREPARE, 2, x2);
        core.onNewView(5, 0, Optional.of(q1));
        core.onNewView(5, 2, Optional.of(q2));
        assertSame(q1, core.onNewView(5, 3, Optional.empty()).get(0).qc().get());
    }

    @Test
    void newViewTardioNaoMudaOHighQcDaProposta() {
        monta(4, 1);
        HotStuffBlock x = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        core.onNewView(1, 0, Optional.empty());
        core.onNewView(1, 2, Optional.empty());
        LeaderAction p = core.onNewView(1, 3, Optional.empty()).get(0);
        assertTrue(core.onNewView(1, 1, Optional.of(Fixtures.qc(HotStuffVote.Phase.PREPARE, 0, x))).isEmpty());
        assertEquals(1, discards.get(DiscardReason.UNNEEDED_NEW_VIEW));
        assertSame(p.block(), core.curProposal(1).get());
    }

    // ---- visao inicial: mesmo caminho das demais (Algoritmo 2, linhas 4-5: n-f new-view, "special" da visao 0) ----

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 10})
    void liderDaVisaoInicialNaoProporAntesDoQuorumDeNewViewIniciais(int n) {
        monta(n, 0);
        assertFalse(core.hasStarted(0), "nao ha atalho: sem NEW_VIEW nao ha PREPARE");
        for (int id = 0; id < 2 * f(n); id++) { // 2f = n-f-1 NEW_VIEW: um a menos que o quorum
            assertTrue(core.onNewView(0, id, Optional.empty()).isEmpty(), "n=" + n + " id " + id);
        }
        assertFalse(core.hasStarted(0));
        assertEquals(0, proposerCalls.size());
        assertTrue(core.curProposal(0).isEmpty());
        // votos antes de a visao iniciar nao a iniciam nem contam
        assertTrue(core.onVote(Fixtures.vote(1, HotStuffVote.Phase.PREPARE, 0, genesis)).isEmpty());
        assertEquals(1, discards.get(DiscardReason.VOTE_VIEW_NOT_STARTED));
        List<LeaderAction> a = core.onNewView(0, 2 * f(n), Optional.empty()); // o n-f-esimo
        assertEquals(1, a.size());
        assertEquals(Phase.PREPARE, a.get(0).phase());
        assertEquals(0L, a.get(0).view());
        assertEquals(1, proposerCalls.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 10})
    void newViewIniciaisRepetidosDoMesmoRemetenteSaoContadosENaoDuplicamOQuorum(int n) {
        monta(n, 0);
        // n-f-1 remetentes distintos, cada um enviando 3 vezes: contados como duplicados, nao propoe
        for (int id = 0; id < 2 * f(n); id++) {
            for (int k = 0; k < 3; k++) {
                assertTrue(core.onNewView(0, id, Optional.empty()).isEmpty());
            }
        }
        assertFalse(core.hasStarted(0));
        assertEquals(2L * 2 * f(n), discards.get(DiscardReason.DUPLICATE_NEW_VIEW));
        assertEquals(2L * 2 * f(n), discards.total(), "nada some em silencio");
        assertEquals(1, core.onNewView(0, 2 * f(n), Optional.empty()).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 10})
    void maisDeNMenosFNewViewIniciaisPropoeUmaVezEOsExcedentesSaoContados(int n) {
        monta(n, 0);
        int propostas = 0;
        for (int id = 0; id < n; id++) { // todas as n replicas enviam
            propostas += core.onNewView(0, id, Optional.empty()).size();
        }
        assertEquals(1, propostas, "uma unica proposta, no n-f-esimo");
        assertEquals(1, proposerCalls.size());
        assertEquals(n - q(n), discards.get(DiscardReason.UNNEEDED_NEW_VIEW), "os f restantes chegaram tarde");
        assertEquals(n - q(n), discards.total());
    }

    @Test
    void newViewInicialComQcEIncoerente() {
        monta(4, 0);
        HotStuffBlock x = new HotStuffBlock(1000, 1, 0.0, null, genesis);
        // na visao inicial so existe bottom; QC de visao 0 tambem seria >= alvo, e de visao menor nao existe
        assertTrue(core.onNewView(0, 1, Optional.of(Fixtures.qc(HotStuffVote.Phase.PREPARE, 0, x))).isEmpty());
        assertEquals(1, discards.get(DiscardReason.INCOHERENT_QC));
        assertFalse(core.hasStarted(0));
    }

    @Test
    void visaoInicialSoIniciaQuemAlidera() {
        monta(4, 2);
        for (int id = 0; id < 4; id++) {
            assertTrue(core.onNewView(0, id, Optional.empty()).isEmpty());
        }
        assertEquals(4, discards.get(DiscardReason.NOT_VIEW_LEADER));
        assertEquals(0, proposerCalls.size());
        assertFalse(core.hasStarted(0));
    }

    @Test
    void proposerQueViolaOContratoFalhaAlto() {
        LeaderCore ruim = new LeaderCore(4, 0, 0L, new ViewLeaders(cfg, 4), cfg, genesis,
                (view, parent) -> new HotStuffBlock(1000, 5, 0.0, null, parent),
                new VoteAggregator<>(), new NewViewAggregator(), new DiscardCounters());
        assertTrue(ruim.onNewView(0, 0, Optional.empty()).isEmpty());
        assertTrue(ruim.onNewView(0, 1, Optional.empty()).isEmpty());
        assertThrows(IllegalStateException.class, () -> ruim.onNewView(0, 2, Optional.empty()));
    }

    // ---- defesa em profundidade: o nucleo recusa o QC abaixo do quorum mesmo se o agregador o devolvesse ----

    /** Agregador DEFEITUOSO: forma QC valido (com 2f+1 votantes de mentira) na primeira consulta, sem quorum de votos. */
    private static final class AgregadorPrematuro extends VoteAggregator<HotStuffBlock> {
        int consultasTryFormQc = 0;

        @Override
        public Optional<HotStuffQuorumCertificate> tryFormQc(HotStuffVote.Phase phase, long view, HotStuffBlock block,
                                                             HotStuffConsensusConfig config, int n) {
            consultasTryFormQc++;
            return Optional.of(Fixtures.qc(phase, view, block, n));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 10})
    void nucleoRecusaFormarFaseSeguinteAbaixoDoQuorumMesmoComAgregadorPrematuro(int n) {
        AgregadorPrematuro defeituoso = new AgregadorPrematuro();
        LeaderCore c = new LeaderCore(n, 0, 0L, new ViewLeaders(cfg, n), cfg, genesis,
                (view, parent) -> new HotStuffBlock(1000, parent.getHeight() + 1, 0.0, null, parent),
                defeituoso, new NewViewAggregator(), discards);
        HotStuffBlock b = null;
        for (int id = 0; id < q(n); id++) {
            List<LeaderAction> a = c.onNewView(0, id, Optional.empty());
            if (!a.isEmpty()) b = a.get(0).block();
        }
        for (HotStuffVote.Phase fase : new HotStuffVote.Phase[] {HotStuffVote.Phase.PREPARE}) {
            for (int id = 0; id < 2 * f(n); id++) { // 2f votos: abaixo de n-f
                assertTrue(c.onVote(Fixtures.vote(id, fase, 0, b)).isEmpty(),
                        "n=" + n + " voto " + id + ": o nucleo nao pode avancar com " + (id + 1) + " < " + q(n) + " votos");
            }
        }
        assertEquals(0, defeituoso.consultasTryFormQc, "nem sequer consulta o agregador abaixo do quorum");
        // no voto n-f o nucleo consulta e avanca (o agregador defeituoso tambem concordaria)
        List<LeaderAction> a = c.onVote(Fixtures.vote(2 * f(n), HotStuffVote.Phase.PREPARE, 0, b));
        assertEquals(1, a.size());
        assertEquals(Phase.PRE_COMMIT, a.get(0).phase());
        assertEquals(1, defeituoso.consultasTryFormQc);
    }

    @Test
    void entradasInvalidasFalhamAlto() {
        monta(4, 0);
        assertThrows(IllegalArgumentException.class, () -> core.onVote(null));
        assertThrows(IllegalArgumentException.class, () -> core.onNewView(-1, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> core.onNewView(0, 0, null));
        ViewLeaders l = new ViewLeaders(cfg, 4);
        assertThrows(IllegalArgumentException.class, () -> new LeaderCore(4, 4, 0L, l, cfg, genesis,
                (v, p) -> p, new VoteAggregator<>(), new NewViewAggregator(), discards));
        assertThrows(IllegalArgumentException.class, () -> new LeaderCore(3, 0, 0L, l, cfg, genesis,
                (v, p) -> p, new VoteAggregator<>(), new NewViewAggregator(), discards));
        assertThrows(IllegalArgumentException.class, () -> new LeaderCore(4, 0, 0L, l, cfg, genesis,
                null, new VoteAggregator<>(), new NewViewAggregator(), discards));
    }
}
