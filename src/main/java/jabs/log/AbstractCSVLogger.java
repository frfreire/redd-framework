package jabs.log;

import de.siegmar.fastcsv.writer.CsvWriter;
import jabs.network.node.nodes.Node;
import jabs.simulator.event.Event;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

public abstract class AbstractCSVLogger extends AbstractLogger {
    protected CsvWriter loggerCSV;
    // Underlying writer used for flush operations (CsvWriter doesn't expose flush)
    protected Writer underlyingWriter;
    // Asynchronous buffer for row writes to reduce synchronous I/O during simulation
    private final ConcurrentLinkedQueue<String[]> csvRowBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flusherRunning = new AtomicBoolean(false);
    private Thread csvFlusherThread = null;
    // Dedicated lock for poll + write + flush (never the monitor of 'this': stopFlusherAndFlush joins the flusher)
    private final Object writeLock = new Object();
    // First write/flush failure, rethrown by finalLog() (never swallowed)
    private final AtomicReference<Exception> firstFailure = new AtomicReference<>();
    private static final int CSV_FLUSH_BATCH = 128;
    private static final long CSV_FLUSH_INTERVAL_MS = 250;
    private final long csvFlushIntervalMs;

    @Override
    public void initialLog() {
        synchronized (writeLock) {
            loggerCSV.writeComment(this.csvStartingComment());
            loggerCSV.writeRow(this.csvHeaderOutput());
        }
        startFlusherIfNeeded();
    }

    @Override
    public void logBeforeEachEvent(Event event) {
        if (this.csvOutputConditionBeforeEvent(event)) {
            bufferRow(this.csvEventOutput(event));
        }
    }

    @Override
    public void logAfterEachEvent(Event event) {
        if (this.csvOutputConditionAfterEvent(event)) {
            bufferRow(this.csvEventOutput(event));
        }
    }

    @Override
    public void finalLog() throws IOException {
        // Flush and stop background flusher before final writes
        stopFlusherAndFlush();

        try {
            if (this.csvOutputConditionFinalPerNode()) {
                for (Object node: this.scenario.getNetwork().getAllNodes()) {
                    synchronized (writeLock) {
                        loggerCSV.writeRow(this.csvNodeOutput(((Node) node)));
                    }
                }
            }
        } finally {
            closeAndRethrowFirstFailure();
        }
    }

    private void closeAndRethrowFirstFailure() throws IOException {
        Exception failure = firstFailure.get();
        try {
            synchronized (writeLock) {
                loggerCSV.close();
            }
        } catch (IOException | UncheckedIOException e) {
            if (failure == null) throw e;
            failure.addSuppressed(e);
        }
        if (failure != null) {
            throw new IOException("falha ao escrever o CSV do logger (primeira falha na causa): " + failure.getMessage(), failure);
        }
    }

    /**
     * Adds the Starting Comment to the output CSV file
     * @return the string to be written at the start of csv file
     */
    abstract protected String csvStartingComment();

    /**
     * If return value is true, then one line will be written to the csv output.
     * this run before event execution.
     * @return true if one line should be written to the output CSV file.
     */
    abstract protected boolean csvOutputConditionBeforeEvent(Event event);

    /**
     * If return value is true, then one line will be written to the csv output.
     * this run after event execution.
     * @return true if one line should be written to the output CSV file.
     */
    abstract protected boolean csvOutputConditionAfterEvent(Event event);

    /**
     * If true, at the end of the simulation for each node a single line output
     * would be printed which is determined by the csvNodeOutput() function
     * @return true one line per each node will be written to csv output
     */
    abstract protected boolean csvOutputConditionFinalPerNode();

    /**
     * First line of CSV file which has the header information
     * @return list of header names to output
     */
    abstract protected String[] csvHeaderOutput();

    /**
     * If the CSV Output is true the returned value of this function will
     * be written to csv output file
     * @return list of strings to output
     */
    abstract protected String[] csvEventOutput(Event event);

    /**
     * If csvOutputConditionFinalPerNode() is true the returned value of this
     * function will be written to csv output file
     * @return list of strings to output
     */
    protected String[] csvNodeOutput(Node node) {
        return new String[0];
    };

    /**
     * creates an abstract CSV logger
     * @param writer this is output CSV of the logger
     */
    public AbstractCSVLogger(Writer writer) {
        this(writer, CSV_FLUSH_INTERVAL_MS);
    }

    /**
     * Package-private (tests): same as {@link #AbstractCSVLogger(Writer)} with a custom interval for the
     * background flusher thread, so that a test can force the main thread and the flusher to compete.
     * The production constructors always use {@link #CSV_FLUSH_INTERVAL_MS}.
     */
    AbstractCSVLogger(Writer writer, long flushIntervalMs) {
        this.csvFlushIntervalMs = flushIntervalMs;
        this.loggerCSV = CsvWriter.builder().build(writer);
        this.underlyingWriter = writer;
        startFlusherIfNeeded();
    }

    /**
     * creates an abstract CSV logger
     * @param path this is output path of CSV file
     */
    public AbstractCSVLogger(Path path) throws IOException {
        this(openWriter(path), CSV_FLUSH_INTERVAL_MS);
    }

    private static Writer openWriter(Path path) throws IOException {
        path.toFile().getParentFile().mkdirs();
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private void bufferRow(String[] row) {
        if (row == null) return;
        csvRowBuffer.add(row);
        if (csvRowBuffer.size() >= CSV_FLUSH_BATCH) {
            flushBuffer(CSV_FLUSH_BATCH);
        }
    }

    private synchronized void startFlusherIfNeeded() {
        if (flusherRunning.get()) return;
        flusherRunning.set(true);
        csvFlusherThread = new Thread(() -> {
            while (flusherRunning.get() || !csvRowBuffer.isEmpty()) {
                try {
                    flushBuffer(CSV_FLUSH_BATCH);
                    TimeUnit.MILLISECONDS.sleep(csvFlushIntervalMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "csv-flusher");
        csvFlusherThread.setDaemon(true);
        csvFlusherThread.start();
    }

    /**
     * Deliberately NOT synchronized on {@code this}: the flusher thread must be able to enter
     * {@link #flushBuffer(int)} while this method waits in {@code join}. Only {@link #writeLock} is taken
     * (inside flushBuffer), never while joining.
     */
    private void stopFlusherAndFlush() {
        flusherRunning.set(false);
        Thread flusher = csvFlusherThread;
        if (flusher != null) {
            try {
                flusher.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // Drain EVERYTHING still queued (not only one batch): nothing enqueued may be lost at close.
        flushBuffer(Integer.MAX_VALUE);
    }

    /**
     * Polls up to {@code max} rows and writes them. The poll and the writes are ONE critical section under
     * {@link #writeLock}: the main thread (batch of 128) and the flusher thread both come here, and the queue is FIFO,
     * so the CSV keeps the enqueue order and the (not thread-safe) {@link CsvWriter} has a single writer at a time.
     * A write/flush failure is not swallowed: the first one is kept and rethrown by {@link #finalLog()}.
     */
    private void flushBuffer(int max) {
        synchronized (writeLock) {
            boolean wrote = false;
            for (int i = 0; i < max; i++) {
                String[] row = csvRowBuffer.poll();
                if (row == null) break;
                try {
                    loggerCSV.writeRow(row);
                    wrote = true;
                } catch (UncheckedIOException e) {
                    recordFailure(e);
                }
            }
            if (wrote) {
                try {
                    if (underlyingWriter != null) underlyingWriter.flush();
                } catch (IOException e) {
                    recordFailure(e);
                }
            }
        }
    }

    private void recordFailure(Exception e) {
        if (firstFailure.compareAndSet(null, e)) {
            System.err.println("AbstractCSVLogger: falha ao escrever o CSV (sera relancada em finalLog): " + e);
        }
    }
}
