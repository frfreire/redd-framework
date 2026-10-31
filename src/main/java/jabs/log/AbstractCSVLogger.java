package jabs.log;

import de.siegmar.fastcsv.writer.CsvWriter;
import jabs.network.node.nodes.Node;
import jabs.simulator.event.Event;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class AbstractCSVLogger extends AbstractLogger {
    protected CsvWriter loggerCSV;
    // Underlying writer used for flush operations (CsvWriter doesn't expose flush)
    protected Writer underlyingWriter;
    // Asynchronous buffer for row writes to reduce synchronous I/O during simulation
    private final ConcurrentLinkedQueue<String[]> csvRowBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flusherRunning = new AtomicBoolean(false);
    private Thread csvFlusherThread = null;
    private static final int CSV_FLUSH_BATCH = 128;
    private static final long CSV_FLUSH_INTERVAL_MS = 250;

    @Override
    public void initialLog() {
        loggerCSV.writeComment(this.csvStartingComment());
        loggerCSV.writeRow(this.csvHeaderOutput());
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

        if (this.csvOutputConditionFinalPerNode()) {
            for (Object node: this.scenario.getNetwork().getAllNodes()) {
                loggerCSV.writeRow(this.csvNodeOutput(((Node) node)));
            }
        }
        loggerCSV.close();
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
        this.loggerCSV = CsvWriter.builder().build(writer);
        this.underlyingWriter = writer;
        startFlusherIfNeeded();
    }

    /**
     * creates an abstract CSV logger
     * @param path this is output path of CSV file
     */
    public AbstractCSVLogger(Path path) throws IOException {
        path.toFile().getParentFile().mkdirs();
        Writer fileWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        this.loggerCSV = CsvWriter.builder().build(fileWriter);
        this.underlyingWriter = fileWriter;
        startFlusherIfNeeded();
    }

    private void bufferRow(String[] row) {
        if (row == null) return;
        csvRowBuffer.add(row);
        if (csvRowBuffer.size() >= CSV_FLUSH_BATCH) {
            flushBuffer();
        }
    }

    private synchronized void startFlusherIfNeeded() {
        if (flusherRunning.get()) return;
        flusherRunning.set(true);
        csvFlusherThread = new Thread(() -> {
            while (flusherRunning.get() || !csvRowBuffer.isEmpty()) {
                try {
                    flushBuffer();
                    TimeUnit.MILLISECONDS.sleep(CSV_FLUSH_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "csv-flusher");
        csvFlusherThread.setDaemon(true);
        csvFlusherThread.start();
    }

    private synchronized void stopFlusherAndFlush() {
        flusherRunning.set(false);
        if (csvFlusherThread != null) {
            try {
                csvFlusherThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        flushBuffer();
    }

    private void flushBuffer() {
        List<String[]> batch = new ArrayList<>(CSV_FLUSH_BATCH);
        for (int i = 0; i < CSV_FLUSH_BATCH; i++) {
            String[] row = csvRowBuffer.poll();
            if (row == null) break;
            batch.add(row);
        }
        if (!batch.isEmpty()) {
            for (String[] r : batch) {
                loggerCSV.writeRow(r);
            }
            try {
                if (underlyingWriter != null) underlyingWriter.flush();
            } catch (Exception ignored) {}
        }
    }
}
