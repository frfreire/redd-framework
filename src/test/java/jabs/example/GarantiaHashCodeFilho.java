package jabs.example;

import jabs.log.LinkLoadRecorder;
import jabs.scenario.ArqReddVotingScenario;

/**
 * Programa de apoio de {@code GarantiaHashCodeTest} (roda em JVM filha): liga o registro por CENARIO (sem passar por
 * {@code MVPComparison}), para provar que a imposicao tambem vale em {@code LinkLoadRecorder.initialLog}.
 * Uso: {@code GarantiaHashCodeFilho PREFIXO_SAIDA}.
 */
public final class GarantiaHashCodeFilho {
    private GarantiaHashCodeFilho() {
    }

    public static void main(String[] a) throws Exception {
        ArqReddVotingScenario s = new ArqReddVotingScenario("ARQ-garantia", 1L, 7, 20.0);
        s.setMaxFutureHorizon(1000L);
        s.AddNewLogger(new LinkLoadRecorder(a[0], "ARQ", 1L, 5.0));
        s.run();
        System.out.println("FILHO-CONCLUIDO");
    }
}
