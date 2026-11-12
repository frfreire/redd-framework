package jabs.log;

import jabs.simulator.event.Event;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;

/**
 * Medicao (nao e teste JUnit): tempo de {@code finalLog()} de um AbstractCSVLogger, para comparar a baseline com a
 * versao com lock dedicado. Usa SO a API publica (construtor Writer), entao roda contra qualquer {@code classes/}.
 *
 * <p>Uso: {@code java -cp <classes>:target/test-classes:<deps> jabs.log.MedeFechamentoLogger [repeticoes]}.
 * Cenarios: (A) 0 linhas pendentes no fechamento; (B) 50 linhas (&lt; lote de 128) pendentes, flusher dormindo;
 * (C) 1000 linhas ja enfileiradas pela thread principal. Saida: mediana, p95 e maximo em ms, por cenario.
 */
public final class MedeFechamentoLogger {
    private record Linha(String[] v) implements Event {
        @Override
        public void execute() {
        }
    }

    private static final class L extends AbstractCSVLogger {
        L() {
            super(new StringWriter());
        }

        @Override
        protected String csvStartingComment() {
            return "medida";
        }

        @Override
        protected boolean csvOutputConditionBeforeEvent(Event e) {
            return true;
        }

        @Override
        protected boolean csvOutputConditionAfterEvent(Event e) {
            return false;
        }

        @Override
        protected boolean csvOutputConditionFinalPerNode() {
            return false;
        }

        @Override
        protected String[] csvHeaderOutput() {
            return new String[]{"a", "b"};
        }

        @Override
        protected String[] csvEventOutput(Event e) {
            return ((Linha) e).v();
        }
    }

    private static double fechar(int linhas, long pausaAntesMs) throws IOException, InterruptedException {
        L l = new L();
        l.initialLog();
        if (pausaAntesMs > 0) Thread.sleep(pausaAntesMs);
        for (int i = 0; i < linhas; i++) l.logBeforeEachEvent(new Linha(new String[]{"" + i, "x" + i}));
        long t0 = System.nanoTime();
        l.finalLog();
        return (System.nanoTime() - t0) / 1e6;
    }

    private static void relatar(String nome, double[] ms) {
        double[] s = ms.clone();
        Arrays.sort(s);
        System.out.printf("%-34s n=%d mediana=%.3f ms p95=%.3f ms max=%.3f ms%n", nome, s.length,
                s[s.length / 2], s[(int) Math.floor(s.length * 0.95)], s[s.length - 1]);
    }

    public static void main(String[] args) throws Exception {
        int rep = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        double[] a = new double[rep], b = new double[rep], c = new double[rep];
        for (int i = 0; i < rep; i++) {
            a[i] = fechar(0, 20);
            b[i] = fechar(50, 20);
            c[i] = fechar(1000, 0);
        }
        relatar("A: 0 pendentes", a);
        relatar("B: 50 pendentes (flusher dorme)", b);
        relatar("C: 1000 enfileiradas", c);
    }
}
