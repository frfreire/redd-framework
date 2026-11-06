package jabs.example;

import jabs.log.AbstractLogger;
import jabs.log.LinkLoadRecorder;
import jabs.scenario.ArqReddVotingScenario;
import jabs.simulator.event.Event;

/**
 * Auxiliar de DIAGNOSTICO (NAO e teste JUnit), M1a-1 rodada 2. Roda o ARQ (7 nos, 30 s) com duas sondas de hash
 * (uma ANTES e uma DEPOIS do registrador na lista de loggers) e imprime {@code FASE tag SONDA=valor}: cada valor e
 * {@code identityHashCode(new Object())} da thread principal naquele ponto; procurar o valor na sequencia de
 * referencia ({@code SondaHash ref N}) da a posicao. Comparar "off" (so as sondas) contra "on" (com o registrador)
 * mostra em que FASE o registrador desloca a sequencia.
 *
 * <p>Uso: {@code SondaFases off|on|on-sem-t0}.
 */
public final class SondaFases {
    private SondaFases() { }

    private static final class Sonda extends AbstractLogger {
        private final String tag;
        private long eventos;
        private boolean depoisDeT0;

        Sonda(String tag) {
            this.tag = tag;
        }

        private void marca(String fase) {
            System.out.println("FASE " + tag + "." + fase + " SONDA=" + System.identityHashCode(new Object()));
        }

        @Override public void initialLog() {
            marca("initialLog");
        }

        @Override public void logBeforeEachEvent(Event event) {
            eventos++;
            if (eventos == 1) {
                marca("primeiroEvento");
            }
            if (!depoisDeT0 && scenario.getSimulator().peekEventTime() >= 10.0) {
                depoisDeT0 = true;
                marca("eventoEmT0");
            }
        }

        @Override public void logAfterEachEvent(Event event) { }

        @Override public void finalLog() {
            marca("finalLog");
        }
    }

    public static void main(String[] a) throws Exception {
        boolean on = !a[0].equals("off");
        ArqReddVotingScenario s = new ArqReddVotingScenario("ARQ-fases", 1L, 7, 30.0);
        s.AddNewLogger(new Sonda("A"));
        if (on) {
            s.AddNewLogger(new LinkLoadRecorder(System.getProperty("java.io.tmpdir") + "/sonda-fases", "ARQ", 1L, 10.0));
        }
        s.AddNewLogger(new Sonda("B"));
        System.out.println("FASE antesDoRun SONDA=" + System.identityHashCode(new Object()));
        s.run();
        System.out.println("FASE depoisDoRun SONDA=" + System.identityHashCode(new Object()));
    }
}
