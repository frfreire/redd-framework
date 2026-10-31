package jabs.consensus.config;

import jabs.consensus.config.HotStuffConsensusConfig.LeaderPolicy;
import jabs.consensus.config.HotStuffConsensusConfig.SignatureMode;
import jabs.consensus.config.HotStuffConsensusConfig.VoterEncoding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Funcoes puras e validacao do HotStuffConsensusConfig (sem simulador). */
class HotStuffConsensusConfigTest {

    @Test
    void quorumExato() {
        int[][] casos = {{4, 1, 3}, {7, 2, 5}, {10, 3, 7}, {13, 4, 9}};
        for (int[] c : casos) {
            assertEquals(c[1], HotStuffConsensusConfig.maxFaultyNodes(c[0]), "f para n=" + c[0]);
            assertEquals(c[2], HotStuffConsensusConfig.quorumSize(c[0]), "quorum para n=" + c[0]);
        }
    }

    @Test
    void quorumComNNaoMultiploDeTresMaisUm() {
        // n=5,6 -> f=1, quorum 3; n=8,9 -> f=2, quorum 5
        assertEquals(3, HotStuffConsensusConfig.quorumSize(5));
        assertEquals(3, HotStuffConsensusConfig.quorumSize(6));
        assertEquals(5, HotStuffConsensusConfig.quorumSize(8));
        assertEquals(5, HotStuffConsensusConfig.quorumSize(9));
    }

    @Test
    void liderRotativo() {
        assertEquals(0, HotStuffConsensusConfig.leaderFor(0, 4, LeaderPolicy.ROTATING));
        assertEquals(3, HotStuffConsensusConfig.leaderFor(3, 4, LeaderPolicy.ROTATING));
        assertEquals(0, HotStuffConsensusConfig.leaderFor(4, 4, LeaderPolicy.ROTATING));
        assertEquals(2, HotStuffConsensusConfig.leaderFor(9, 7, LeaderPolicy.ROTATING));
        assertEquals((int) (10_000_000_007L % 10), HotStuffConsensusConfig.leaderFor(10_000_000_007L, 10, LeaderPolicy.ROTATING));
    }

    @Test
    void liderEstavel() {
        for (long v = 0; v < 50; v++) {
            assertEquals(0, HotStuffConsensusConfig.leaderFor(v, 7, LeaderPolicy.STABLE));
        }
    }

    @Test
    void liderDeterministico() {
        for (long v = 0; v < 30; v++) {
            assertEquals(HotStuffConsensusConfig.leaderFor(v, 10, LeaderPolicy.ROTATING),
                    HotStuffConsensusConfig.leaderFor(v, 10, LeaderPolicy.ROTATING));
        }
    }

    @Test
    void entradasInvalidasDasFuncoesPuras() {
        for (int n = -1; n < 4; n++) {
            final int nn = n;
            assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.quorumSize(nn));
            assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.maxFaultyNodes(nn));
            assertThrows(IllegalArgumentException.class,
                    () -> HotStuffConsensusConfig.leaderFor(0, nn, LeaderPolicy.ROTATING));
        }
        assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.leaderFor(-1, 4, LeaderPolicy.ROTATING));
        assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.leaderFor(0, 4, null));
    }

    /** Builder com todos os valores sem base escritos explicitamente (valores de teste, nao de pesquisa). */
    private static HotStuffConsensusConfig.Builder completo() {
        return HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0)
                .batchSize(HotStuffConsensusConfig.batchSizeFor(1024, 512))
                .individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96);
    }

    private static HotStuffConsensusConfig padrao() {
        return completo().build();
    }

    @Test
    void padroesComBaseSaoMantidos() {
        HotStuffConsensusConfig c = padrao();
        assertEquals(LeaderPolicy.ROTATING, c.getLeaderPolicy());
        assertEquals(SignatureMode.INDIVIDUAL, c.getSignatureMode());
        assertEquals(10, c.getMessageOverheadBytes());
        assertEquals(2048, c.getBatchSize());
    }

    @Test
    void batchSizeForDerivaDoSpecOficial() {
        assertEquals(2048, HotStuffConsensusConfig.batchSizeFor(1024, 512));
        assertEquals(1, HotStuffConsensusConfig.batchSizeFor(1, 1024));
        assertEquals(3, HotStuffConsensusConfig.batchSizeFor(1, 300)); // floor(1024/300)
        assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.batchSizeFor(0, 512));
        assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.batchSizeFor(1024, 0));
        assertThrows(IllegalArgumentException.class, () -> HotStuffConsensusConfig.batchSizeFor(1, 2048));
    }

    @Test
    void builderSemTimeoutFalha() {
        HotStuffConsensusConfig.Builder b = HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP)
                .batchSize(2048).individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96).aggregatedShareSizeBytes(96);
        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("viewTimeoutSeconds"));
    }

    @Test
    void builderSemLoteFalha() {
        HotStuffConsensusConfig.Builder b = HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0).individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96).aggregatedShareSizeBytes(96);
        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("batchSize"));
    }

    @Test
    void builderSemCodificacaoDeVotantesFalhaSemPadraoSilencioso() {
        HotStuffConsensusConfig.Builder b = HotStuffConsensusConfig.builder()
                .viewTimeoutSeconds(10.0).batchSize(2048).individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96).aggregatedShareSizeBytes(96);
        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("voterEncoding"));
        assertTrue(e.getMessage().contains("a definir com o humano"));
        assertEquals(List.of(VoterEncoding.BITMAP), List.of(VoterEncoding.values()));
    }

    @Test
    void tamanhoDoBitmapDeVotantesVemDeNEIgualNosDoisModos() {
        HotStuffConsensusConfig ind = padrao();
        HotStuffConsensusConfig agg = completo().signatureMode(SignatureMode.AGGREGATED).build();
        assertEquals(VoterEncoding.BITMAP, ind.getVoterEncoding());
        int[][] casos = {{4, 1}, {16, 2}, {64, 8}, {128, 16}, {400, 50}, {800, 100}, {9, 2}, {401, 51}};
        for (int[] k : casos) {
            assertEquals(k[1], ind.voterIdentifiersSize(k[0]), "n=" + k[0]);
            assertEquals(k[1], agg.voterIdentifiersSize(k[0]), "n=" + k[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> ind.voterIdentifiersSize(3));
    }

    @Test
    void builderVazioFalha() {
        assertThrows(IllegalStateException.class, () -> HotStuffConsensusConfig.builder().build());
        assertThrows(IllegalStateException.class,
                () -> HotStuffConsensusConfig.builder().viewTimeoutSeconds(10.0).build());
        assertThrows(IllegalStateException.class, () -> HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP).viewTimeoutSeconds(10.0).batchSize(2048).build());
        assertThrows(IllegalStateException.class, () -> HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0).batchSize(2048).individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96).build());
    }

    @Test
    void builderSobrescreveTudo() {
        HotStuffConsensusConfig c = HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP)
                .leaderPolicy(LeaderPolicy.STABLE).signatureMode(SignatureMode.AGGREGATED)
                .viewTimeoutSeconds(2.5).batchSize(50)
                .individualSignatureSizeBytes(71).aggregatedSignatureSizeBytes(48)
                .aggregatedShareSizeBytes(48).messageOverheadBytes(0).build();
        assertEquals(LeaderPolicy.STABLE, c.getLeaderPolicy());
        assertEquals(SignatureMode.AGGREGATED, c.getSignatureMode());
        assertEquals(2.5, c.getViewTimeoutSeconds());
        assertEquals(50, c.getBatchSize());
        assertEquals(71, c.getIndividualSignatureSizeBytes());
        assertEquals(48, c.getAggregatedSignatureSizeBytes());
        assertEquals(0, c.getMessageOverheadBytes());
    }

    @Test
    void configInvalida() {
        assertThrows(IllegalArgumentException.class, () -> completo().viewTimeoutSeconds(0).build());
        assertThrows(IllegalArgumentException.class, () -> completo().viewTimeoutSeconds(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> completo().viewTimeoutSeconds(Double.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> completo().viewTimeoutSeconds(Double.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class, () -> completo().batchSize(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> completo().individualSignatureSizeBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> completo().leaderPolicy(null).build());
        assertThrows(IllegalArgumentException.class, () -> completo().signatureMode(null).build());
        assertThrows(IllegalArgumentException.class, () -> completo().messageOverheadBytes(-1).build());
    }

    @Test
    void validacaoContraN() {
        HotStuffConsensusConfig c = padrao();
        assertThrows(IllegalArgumentException.class, () -> c.validateFor(3));
        c.validateFor(4);
    }

    @Test
    void assinaturaQcIndividualCresceAgregadaConstante() {
        HotStuffConsensusConfig c = padrao();
        assertEquals(3L * 64, c.qcSignatureSize(SignatureMode.INDIVIDUAL, HotStuffConsensusConfig.quorumSize(4)));
        assertEquals(9L * 64, c.qcSignatureSize(SignatureMode.INDIVIDUAL, HotStuffConsensusConfig.quorumSize(13)));
        assertEquals(c.qcSignatureSize(SignatureMode.AGGREGATED, 3), c.qcSignatureSize(SignatureMode.AGGREGATED, 67));
        assertEquals(c.getAggregatedSignatureSizeBytes(), c.qcSignatureSize(SignatureMode.AGGREGATED, 67));
        assertThrows(IllegalArgumentException.class, () -> c.qcSignatureSize(SignatureMode.INDIVIDUAL, 0));
    }
}
