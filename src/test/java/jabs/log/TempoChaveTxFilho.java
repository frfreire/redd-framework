package jabs.log;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.consensus.config.HotStuffConsensusConfig.VoterEncoding;
import jabs.scenario.HotStuffLANScenario;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Programa de apoio de {@code EnhancedLoggerChaveTxTest} (JVM filha com {@code -XX:hashCode=2}): roda a celula de
 * referencia reduzida (HotStuff ROTATING n=4, lote 500, tx 512 B, 15 s, semente 16346; valores de TESTE, nao de
 * pesquisa) e falha se passar de {@link #LIMITE_S}. Confere antes que o hash de identidade esta mesmo fixado em 2.
 */
public final class TempoChaveTxFilho {
    static final double LIMITE_S = 6.0;

    private TempoChaveTxFilho() {
    }

    public static void main(String[] a) throws Exception {
        String efetivo = GarantiaHashCode.efetivo();
        if (!GarantiaHashCode.VALOR_EXIGIDO.equals(efetivo)) {
            System.out.println("JVM filha sem -XX:hashCode=2 (efetivo=" + efetivo + ")");
            System.exit(3);
        }
        HotStuffConsensusConfig config = HotStuffConsensusConfig.builder()
                .voterEncoding(VoterEncoding.BITMAP).viewTimeoutSeconds(60.0).maxFutureViewHorizon(1000L)
                .batchSize(500).txSizeBytes(512).individualSignatureSizeBytes(64)
                .aggregatedSignatureSizeBytes(96).aggregatedShareSizeBytes(96).build();
        Path csv = Files.createTempFile("chave-tx-filho", ".csv");
        try {
            long t0 = System.nanoTime();
            HotStuffLANScenario sc = new HotStuffLANScenario("chave-tx-hash2", 16346L, 4, 15.0, config);
            sc.addMetricsLogger(csv.toString());
            sc.run();
            double s = (System.nanoTime() - t0) / 1e9;
            System.out.printf("parede=%.2f s blocos=%d%n", s, sc.getMetrics().getBlockCount());
            if (sc.getMetrics().getBlockCount() < 10) {
                System.out.println("poucos blocos: o teste nao mede o que deveria");
                System.exit(4);
            }
            if (s > LIMITE_S) {
                System.out.println("LENTO: " + s + " s > " + LIMITE_S + " s");
                System.exit(5);
            }
            System.out.println("CHAVE-TX-OK");
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
