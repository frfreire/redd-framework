package jabs.consensus.algorithm.hotstuff;

import jabs.network.message.HotStuffProposalMessage.Phase;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LeaderCore + ReplicaCore (TreeBlockAccess real) em memoria: os dois nucleos se entendem no caminho honesto. */
class LeaderReplicaIntegrationTest {

    @ParameterizedTest
    @ValueSource(ints = {4, 7})
    void caminhoHonestoDeUmaVisaoFinalizaOMesmoBlocoEmTodasAsReplicas(int n) {
        HonestHarness h = new HonestHarness(n);
        h.runView(0);
        assertEquals(List.of(Phase.PREPARE, Phase.PRE_COMMIT, Phase.COMMIT, Phase.DECIDE),
                h.emitted.stream().map(LeaderAction::phase).toList(), "uma proposta por fase, na ordem");
        HotStuffBlock b = h.proposed.get(0);
        assertSame(h.genesis, b.getFixedParent());
        for (HonestHarness.Rep r : h.reps) {
            assertEquals(List.of(b), r.finalized, "replica " + r.id);
            assertSame(b, r.state.getHighestFinalized().get());
            assertEquals(1L, r.state.getCurrentView(), "avanca para v+1 no DECIDE");
            assertSame(b, r.state.getLockedQc().get().getBlock());
            assertSame(b, r.state.getPrepareQc().get().getBlock());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7})
    void variasVisoesComRotacaoFormamUmaCadeiaEOsNucleosSeEntendemSemDescarte(int n) {
        HonestHarness h = new HonestHarness(n);
        int visoes = 2 * n + 1; // todos os nos lideram ao menos duas vezes
        h.runViews(visoes);
        assertEquals(visoes, h.proposed.size());
        assertEquals(visoes, h.proposerCalls, "BlockProposer chamado uma vez por visao");
        for (int v = 0; v < visoes; v++) {
            HotStuffBlock esperadoPai = v == 0 ? h.genesis : h.proposed.get(v - 1);
            assertSame(esperadoPai, h.proposed.get(v).getFixedParent(), "visao " + v + " estende a anterior");
            assertEquals(v + 1, h.proposed.get(v).getHeight());
            assertEquals(v, h.emitted.get(4 * v).view(), "4 difusoes por visao, na ordem das visoes");
        }
        for (HonestHarness.Rep r : h.reps) {
            assertEquals(h.proposed, r.finalized, "replica " + r.id + ": mesma cadeia finalizada, na ordem");
            assertEquals((long) visoes, r.state.getCurrentView());
        }
        // (viii) o que a replica faz com a proposta do lider honesto e aceito sem NENHUM descarte
        assertEquals(0, h.replicaDiscards());
        for (HonestHarness.Rep r : h.reps) {
            for (DiscardReason d : DiscardReason.values()) {
                assertEquals(0, r.discards.get(d), "replica " + r.id + " " + d);
            }
            assertEquals(0, r.core.pendingSize());
        }
        // do lado do lider so existem os atrasados esperados: f votos tardios por fase e f NEW_VIEW tardios por visao (a inicial inclusive)
        int f = (n - 1) / 3;
        long tardiosVotos = 0, tardiosNv = 0, outros = 0;
        for (DiscardCounters d : h.leaderDiscards) {
            tardiosVotos += d.get(DiscardReason.LATE_VOTE);
            tardiosNv += d.get(DiscardReason.UNNEEDED_NEW_VIEW);
            outros += d.total() - d.get(DiscardReason.LATE_VOTE) - d.get(DiscardReason.UNNEEDED_NEW_VIEW);
        }
        assertEquals((long) visoes * 3 * f, tardiosVotos);
        assertEquals((long) visoes * (n - (2 * f + 1)), tardiosNv);
        assertEquals(0, outros);
    }

    @Test
    void liderRotacionaConformeViewLeaders() {
        HonestHarness h = new HonestHarness(4);
        h.runViews(6);
        for (int v = 0; v < 6; v++) {
            int l = h.leaders.leaderOf(v);
            assertTrue(h.leaderCores[l].hasStarted(v), "visao " + v + " iniciada pelo lider " + l);
            for (int o = 0; o < 4; o++) {
                if (o != l) {
                    assertTrue(!h.leaderCores[o].hasStarted(v), "no " + o + " nao lidera a visao " + v);
                }
            }
        }
    }

    @Test
    void comFVotantesMudosOCaminhoAindaFinalizaComQuorumExato() {
        HonestHarness h = new HonestHarness(7); // f = 2, quorum 5
        h.muted.add(5);
        h.muted.add(6);
        h.runView(0);
        for (HonestHarness.Rep r : h.reps) {
            assertEquals(1, r.finalized.size(), "replica " + r.id);
        }
        assertEquals(0, h.replicaDiscards());
    }

    @Test
    void comMaisDeFVotantesMudosOLiderNaoAvanca() {
        HonestHarness h = new HonestHarness(7);
        h.muted.add(4);
        h.muted.add(5);
        h.muted.add(6); // f+1 mudos: so 4 votos < 5
        h.runView(0);
        assertEquals(List.of(Phase.PREPARE), h.emitted.stream().map(LeaderAction::phase).toList());
        for (HonestHarness.Rep r : h.reps) {
            assertTrue(r.finalized.isEmpty());
        }
    }
}
