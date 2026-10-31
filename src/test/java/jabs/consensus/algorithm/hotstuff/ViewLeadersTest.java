package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.HotStuffConsensusConfig.LeaderPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ViewLeaders: delega a HotStuffConsensusConfig.leaderFor; exatamente um lider por visao. */
class ViewLeadersTest {

    private static final int[] NS = {4, 7, 10, 16, 64, 128};

    private static HotStuffConsensusConfig cfg(LeaderPolicy p) {
        return HotStuffConsensusConfig.builder().leaderPolicy(p)
                .voterEncoding(HotStuffConsensusConfig.VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0).batchSize(2048)
                .individualSignatureSizeBytes(64).aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96).build();
    }

    @Test
    void rotativoConcordaComLeaderFor() {
        for (int n : NS) {
            ViewLeaders vl = new ViewLeaders(cfg(LeaderPolicy.ROTATING), n);
            for (long view = 0; view < 3L * n; view++) {
                int esperado = HotStuffConsensusConfig.leaderFor(view, n, LeaderPolicy.ROTATING);
                assertEquals(esperado, vl.leaderOf(view));
                int lideres = 0;
                for (int id = 0; id < n; id++) {
                    assertEquals(id == esperado, vl.isLeader(view, id));
                    if (vl.isLeader(view, id)) lideres++;
                }
                assertEquals(1, lideres, "n=" + n + " view=" + view);
            }
        }
    }

    @Test
    void estavelSempreONoZero() {
        ViewLeaders vl = new ViewLeaders(cfg(LeaderPolicy.STABLE), 7);
        for (long view = 0; view < 20; view++) {
            assertTrue(vl.isLeader(view, 0));
            assertFalse(vl.isLeader(view, 1));
        }
    }

    @Test
    void argumentosInvalidosLancam() {
        assertThrows(IllegalArgumentException.class, () -> new ViewLeaders(null, 4));
        assertThrows(IllegalArgumentException.class, () -> new ViewLeaders(cfg(LeaderPolicy.ROTATING), 3));
        ViewLeaders vl = new ViewLeaders(cfg(LeaderPolicy.ROTATING), 4);
        assertThrows(IllegalArgumentException.class, () -> vl.isLeader(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> vl.isLeader(0, -1));
        assertThrows(IllegalArgumentException.class, () -> vl.isLeader(0, 4));
        assertThrows(IllegalArgumentException.class, () -> vl.leaderOf(-1));
    }
}
