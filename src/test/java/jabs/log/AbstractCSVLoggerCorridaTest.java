package jabs.log;

import jabs.simulator.event.Event;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tarefa LOGGER-csv-flushBuffer-corrida (HS-CB fase 2, achado B1): a thread principal (bufferRow, lote de 128) e a
 * thread "csv-flusher" (a cada CSV_FLUSH_INTERVAL_MS) chamavam flushBuffer() ao mesmo tempo sobre o MESMO CsvWriter.
 *
 * <p>O teste NAO depende de sorte: o Writer de destino e instrumentado. Cada chamada write/flush e lenta de proposito
 * (espera ativa de ~20 us) e conta quantas threads estao DENTRO do writer ao mesmo tempo; sobreposicao de duas
 * threads e o defeito, detectado diretamente. Alem disso o conteudo final e conferido linha a linha (N linhas, na
 * ordem de enfileiramento, sem linha truncada ou entrelacada).
 */
class AbstractCSVLoggerCorridaTest {

    /** Evento de teste: so carrega a linha que o logger deve escrever. */
    private record LinhaEvento(String[] linha) implements Event {
        @Override
        public void execute() {
        }
    }

    /** Logger minimo: toda linha de evento (antes do evento) e escrita; nada depois; sem linha por no. */
    private static final class LoggerDeLinhas extends AbstractCSVLogger {
        LoggerDeLinhas(Writer w, long intervaloMs) {
            super(w, intervaloMs);
        }

        @Override
        protected String csvStartingComment() {
            return "teste";
        }

        @Override
        protected boolean csvOutputConditionBeforeEvent(Event event) {
            return true;
        }

        @Override
        protected boolean csvOutputConditionAfterEvent(Event event) {
            return false;
        }

        @Override
        protected boolean csvOutputConditionFinalPerNode() {
            return false;
        }

        @Override
        protected String[] csvHeaderOutput() {
            return new String[]{"seq", "carga"};
        }

        @Override
        protected String[] csvEventOutput(Event event) {
            return ((LinhaEvento) event).linha();
        }
    }

    /**
     * Base dos writers do teste (rodada extra, criterio 8): REJEITA qualquer escrita/flush depois de close() (lanca
     * IOException e conta em {@code escritasAposClose}, porque a falha lancada numa thread de fundo depois do
     * finalLog() nao chega a ninguem) e guarda um instantaneo do texto no instante do close(). Assim, linha que ficou
     * na fila e so foi escrita depois do fechamento nao "aparece" no conteudo: e perda detectavel.
     */
    private abstract static class WriterRastreado extends Writer {
        final StringBuilder texto = new StringBuilder();
        final AtomicInteger escritasAposClose = new AtomicInteger();
        volatile boolean fechado;
        private volatile String textoNoClose;

        final void anexar(char[] cbuf, int off, int len) throws IOException {
            if (fechado) {
                escritasAposClose.incrementAndGet();
                throw new IOException("escrita apos close()");
            }
            synchronized (texto) {
                texto.append(cbuf, off, len);
            }
        }

        final void exigirAberto() throws IOException {
            if (fechado) {
                escritasAposClose.incrementAndGet();
                throw new IOException("flush apos close()");
            }
        }

        @Override
        public void close() {
            synchronized (texto) {
                if (!fechado) {
                    textoNoClose = texto.toString();
                    fechado = true;
                }
            }
        }

        /** Tudo o que chegou ao writer ate o instante do close() (nao inclui escrita posterior). */
        String conteudoAteOClose() {
            assertNotNull(textoNoClose, "close() nunca foi chamado no writer");
            return textoNoClose;
        }

        String conteudo() {
            synchronized (texto) {
                return texto.toString();
            }
        }
    }

    /** Writer sem espera nem falha: so rastreia. */
    private static final class WriterSimples extends WriterRastreado {
        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            anexar(cbuf, off, len);
        }

        @Override
        public void flush() throws IOException {
            exigirAberto();
        }
    }

    /**
     * Writer instrumentado: acumula o texto, conta threads simultaneamente dentro de write/flush e pode falhar no
     * flush. A espera e ativa (nao dorme) para ser curta e previsivel.
     */
    private static final class WriterInstrumentado extends WriterRastreado {
        private final AtomicInteger dentro = new AtomicInteger();
        private final AtomicInteger maxSimultaneas = new AtomicInteger();
        private final long esperaNs;
        private final boolean falhaNoFlush;

        WriterInstrumentado(long esperaNs, boolean falhaNoFlush) {
            this.esperaNs = esperaNs;
            this.falhaNoFlush = falhaNoFlush;
        }

        private void entrar() {
            int n = dentro.incrementAndGet();
            maxSimultaneas.accumulateAndGet(n, Math::max);
            long fim = System.nanoTime() + esperaNs;
            while (System.nanoTime() < fim) {
                Thread.onSpinWait();
            }
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            entrar();
            try {
                anexar(cbuf, off, len);
            } finally {
                dentro.decrementAndGet();
            }
        }

        @Override
        public void flush() throws IOException {
            entrar();
            try {
                exigirAberto();
                if (falhaNoFlush) throw new IOException("falha simulada no flush");
            } finally {
                dentro.decrementAndGet();
            }
        }
    }

    private static void enfileirar(AbstractCSVLogger logger, int n) {
        for (int i = 0; i < n; i++) {
            logger.logBeforeEachEvent(new LinhaEvento(new String[]{Integer.toString(i), "carga-" + i}));
        }
    }

    /** Confere: comentario, cabecalho e exatamente n linhas "i,carga-i" na ordem, todas com 2 colunas. */
    private static void conferirConteudo(String conteudo, int n) {
        String[] linhas = conteudo.split("\r\n", -1);
        // comentario, cabecalho, n linhas, e o resto vazio apos o ultimo terminador
        assertEquals(n + 3, linhas.length, "numero de linhas (comentario + cabecalho + dados + vazio final)");
        assertEquals("seq,carga", linhas[1], "cabecalho");
        assertEquals("", linhas[n + 2], "deve terminar em fim de linha");
        for (int i = 0; i < n; i++) {
            assertEquals(i + ",carga-" + i, linhas[i + 2], "linha " + i + " (ordem de enfileiramento / truncada / entrelacada)");
        }
    }

    /** Conta as linhas de dados ja escritas no texto (descontando comentario, cabecalho e o vazio final). */
    private static int linhasDeDados(String conteudo) {
        return Math.max(0, conteudo.split("\r\n", -1).length - 3);
    }

    /**
     * Fechamento correto: o writer foi fechado, NENHUMA escrita/flush veio depois do close(), e as n linhas ja
     * estavam no writer NO INSTANTE do close() (nao basta estarem la depois: depois do close() e perda).
     */
    private static void conferirFechamento(WriterRastreado w, int n) {
        assertTrue(w.fechado, "writer deve ser fechado");
        assertEquals(0, w.escritasAposClose.get(), "escrita/flush depois do close() (linha nao drenada antes do fechamento)");
        conferirConteudo(w.conteudoAteOClose(), n);
    }

    @RepeatedTest(10)
    void escritaConcorrenteNaoSobrepoeNemTrocaOrdem() throws IOException {
        int n = 3000;
        WriterInstrumentado w = new WriterInstrumentado(20_000, false);
        // intervalo de 1 ms: o flusher compete com a thread principal (lote de 128) o tempo todo
        LoggerDeLinhas logger = new LoggerDeLinhas(w, 1);
        logger.initialLog();
        enfileirar(logger, n);
        logger.finalLog();

        assertEquals(1, w.maxSimultaneas.get(), "duas threads dentro do mesmo writer ao mesmo tempo");
        conferirFechamento(w, n);
    }

    /**
     * Ordem: o flusher gira sem pausa (intervalo 0) e a thread principal enfileira sem pausa; se o poll do lote ficar
     * FORA da secao critica da escrita, um lote retirado antes pode ser escrito depois do seguinte. Muitas rodadas
     * por repeticao, porque a inversao e rara por rodada.
     */
    @RepeatedTest(10)
    void ordemDeEnfileiramentoSobFlusherContinuo() throws IOException {
        int n = 2000;
        for (int rodada = 0; rodada < 15; rodada++) {
            WriterInstrumentado w = new WriterInstrumentado(2_000, false);
            LoggerDeLinhas logger = new LoggerDeLinhas(w, 0);
            logger.initialLog();
            enfileirar(logger, n);
            logger.finalLog();
            assertEquals(1, w.maxSimultaneas.get(), "rodada " + rodada + ": duas threads no writer");
            conferirFechamento(w, n);
        }
    }

    @Test
    void fechamentoNaoPerdeLinhasComIntervaloPadraoEQuantidadeNaoMultiplaDoLote() throws IOException {
        int n = 1000; // 7 lotes de 128 + 104
        WriterSimples w = new WriterSimples();
        LoggerDeLinhas logger = new LoggerDeLinhas(w, 250);
        logger.initialLog();
        enfileirar(logger, n);
        logger.finalLog();
        conferirFechamento(w, n);
    }

    @Test
    void fechamentoNaoEsperaTimeoutDoJoinDoFlusher() throws IOException {
        // Armadilha: se flushBuffer() usar o MESMO monitor que stopFlusherAndFlush() segura durante o join, o flusher
        // bloqueia na entrada e o join so termina por timeout (1 s). 50 linhas (< lote) ficam para o flusher.
        int n = 50;
        WriterSimples w = new WriterSimples();
        LoggerDeLinhas logger = new LoggerDeLinhas(w, 100);
        logger.initialLog();
        // deixa o flusher entrar no sleep de 100 ms; as linhas chegam logo depois
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        enfileirar(logger, n);
        long t0 = System.nanoTime();
        logger.finalLog();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 600, "finalLog demorou " + ms + " ms (join com timeout de 1 s?)");
        conferirFechamento(w, n);
    }

    /**
     * Writer para o cenario "o join de 1 s expira com o flusher ainda dentro do writer": a primeira chamada
     * write/flush feita pela thread "csv-flusher" depois de armado dorme {@code bloqueioMs} (DENTRO do writer, ou
     * seja, dentro de flushBuffer e segurando o lock). Registra quantas threads estao dentro ao mesmo tempo, o
     * instante em que o flusher saiu do bloqueio e a ordem dos textos escritos.
     */
    private static final class WriterQueBloqueiaOFlusher extends WriterRastreado {
        private final AtomicInteger dentro = new AtomicInteger();
        private final AtomicInteger maxSimultaneas = new AtomicInteger();
        private final CountDownLatch flusherDentro = new CountDownLatch(1);
        private final AtomicBoolean armado = new AtomicBoolean();
        private final AtomicBoolean jaBloqueou = new AtomicBoolean();
        private final long bloqueioMs;
        private volatile long fimDoBloqueioNs;

        WriterQueBloqueiaOFlusher(long bloqueioMs) {
            this.bloqueioMs = bloqueioMs;
        }

        private void entrar() {
            int n = dentro.incrementAndGet();
            maxSimultaneas.accumulateAndGet(n, Math::max);
            if (armado.get()
                    && "csv-flusher".equals(Thread.currentThread().getName())
                    && jaBloqueou.compareAndSet(false, true)) {
                flusherDentro.countDown();
                try {
                    Thread.sleep(bloqueioMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                fimDoBloqueioNs = System.nanoTime();
            }
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            entrar();
            try {
                anexar(cbuf, off, len);
            } finally {
                dentro.decrementAndGet();
            }
        }

        @Override
        public void flush() throws IOException {
            entrar();
            try {
                exigirAberto();
            } finally {
                dentro.decrementAndGet();
            }
        }
    }

    /**
     * Caminho que os demais testes nao exercitam (achado do critic, criterio 8): o flusher fica DENTRO do writer
     * (lento) mais tempo que o join de 1 s de stopFlusherAndFlush(); o join expira e o drain final roda enquanto o
     * flusher ainda esta em flushBuffer. Sem exclusao mutua no drain final, duas threads entram no writer ao mesmo
     * tempo e as linhas restantes sao escritas antes das que o flusher ja havia retirado da fila (reordenacao).
     *
     * <p>Duracao: ~1,4 s (bloqueio do flusher, dormindo, sem consumir CPU).
     */
    @Test
    void drainFinalSobFlusherAindaDentroDoWriterAposTimeoutDoJoin() throws Exception {
        int antes = 50;  // enfileiradas antes: o flusher as retira e entra (lento) no writer
        int depois = 30; // enfileiradas com o flusher dentro do writer: so o drain final pode retira-las
        int n = antes + depois; // < lote de 128: a thread principal nunca chama flushBuffer por lote
        WriterQueBloqueiaOFlusher w = new WriterQueBloqueiaOFlusher(1400);
        LoggerDeLinhas logger = new LoggerDeLinhas(w, 5);
        logger.initialLog(); // comentario + cabecalho, escritos pela thread principal (flusher ainda desarmado)
        w.armado.set(true);
        enfileirar(logger, antes);
        // o flusher retira o lote, entra no writer e dorme 1,4 s segurando o lock
        assertTrue(w.flusherDentro.await(5, TimeUnit.SECONDS), "o flusher deveria ter entrado no writer");
        for (int i = antes; i < n; i++) {
            logger.logBeforeEachEvent(new LinhaEvento(new String[]{Integer.toString(i), "carga-" + i}));
        }
        long t0 = System.nanoTime();
        logger.finalLog();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // pre-condicao do cenario: no instante em que o join expirou (t0 + 1 s) o flusher AINDA estava no writer
        assertTrue(w.fimDoBloqueioNs - t0 > 1_000_000_000L,
                "o flusher ja tinha saido do writer antes do join expirar: cenario nao exercitado");
        assertTrue(ms >= 1000, "finalLog terminou em " + ms + " ms, antes do timeout do join (1 s)");
        assertEquals(1, w.maxSimultaneas.get(),
                "duas threads dentro do writer ao mesmo tempo (drain final concorreu com o flusher)");
        conferirFechamento(w, n);
    }

    /**
     * Rodada extra (criterio 8): prova que o drain explicito do fechamento (stopFlusherAndFlush) e NECESSARIO.
     * Aqui o flusher NAO consegue esvaziar a fila sozinho: o intervalo e de 60 s e ele esta dormindo (estado
     * TIMED_WAITING, confirmado pelo teste) no instante em que as linhas sao enfileiradas e no fechamento; o join de
     * 1 s expira e a fila ainda tem as 50 linhas (< lote de 128: a thread principal tambem nunca as escreve).
     * So o drain explicito (flushBuffer(MAX_VALUE) em stopFlusherAndFlush) as leva ao writer, e tem de ser ANTES do
     * close(): o writer rejeita escrita depois dele e o conteudo e conferido no instante do close().
     * Sem o drain (mutacao M3 de scripts/jabs/mutar-logger-lock.py) as linhas ficam na fila, o close() acontece sem
     * elas e este teste falha.
     *
     * <p>Duracao: ~1 s (o join do fechamento espera o timeout porque o flusher dorme).
     */
    @Test
    void soODrainExplicitoDoFechamentoRecuperaLinhasQueOFlusherNaoRetira() throws Exception {
        int n = 50;
        WriterSimples w = new WriterSimples();
        LoggerDeLinhas logger = new LoggerDeLinhas(w, 60_000);
        Thread flusher = threadDoFlusher(logger);
        try {
            logger.initialLog();
            // o flusher faz a 1a passada (fila vazia) e dorme 60 s: so entao as linhas sao enfileiradas
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (flusher.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime() < limite, "o flusher nao entrou em espera (estado " + flusher.getState() + ")");
                Thread.onSpinWait();
            }
            enfileirar(logger, n);
            assertEquals(0, linhasDeDados(w.conteudo()), "pre-condicao: nenhuma linha de dados deveria ter chegado ao writer");
            assertEquals(Thread.State.TIMED_WAITING, flusher.getState(), "pre-condicao: flusher deveria seguir dormindo");

            logger.finalLog();
        } finally {
            // limpeza: acorda o flusher (dormindo ate 60 s) para ele terminar; se sobrar linha na fila, ele a escreve
            // DEPOIS do close() e o writer a rejeita (contada em escritasAposClose)
            flusher.interrupt();
            flusher.join(5000);
        }
        assertTrue(!flusher.isAlive(), "o flusher deveria terminar apos o fechamento");
        conferirFechamento(w, n);
    }

    private static Thread threadDoFlusher(AbstractCSVLogger logger) throws ReflectiveOperationException {
        Field f = AbstractCSVLogger.class.getDeclaredField("csvFlusherThread");
        f.setAccessible(true);
        Thread t = (Thread) f.get(logger);
        assertNotNull(t, "o flusher deveria ter sido iniciado pelo construtor");
        return t;
    }

    @Test
    void falhaDeFlushNaoEEngolidaEEhRelancadaNoFechamento() {
        WriterInstrumentado w = new WriterInstrumentado(0, true);
        LoggerDeLinhas logger = new LoggerDeLinhas(w, 250);
        logger.initialLog();
        enfileirar(logger, 10);
        IOException e = assertThrows(IOException.class, logger::finalLog);
        assertNotNull(e.getCause(), "a causa original (a primeira falha) deve acompanhar");
        assertTrue(e.getCause().getMessage().contains("falha simulada no flush"), String.valueOf(e.getCause()));
        assertTrue(w.fechado, "mesmo com falha o writer e fechado");
    }
}
