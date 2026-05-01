package it.cnr.isti.labsedc.concern.probe.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Riproduce un file CSV riga per riga.
 * Params: path (required), hasHeader (true), delimiter (","), loop (false),
 *         perRowDelayMs (0), columnFilter (optional).
 * Logica toPayload identica a CsvFileSource del runtime completo.
 */
public class CsvFileAdapter implements EdgeSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(CsvFileAdapter.class);

    private String  path;
    private boolean hasHeader     = true;
    private String  delimiter     = ",";
    private boolean loop          = false;
    private long    perRowDelayMs = 0L;
    private String  columnFilter;

    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(1000);
    private volatile boolean done    = false;
    private volatile boolean stopped = false;
    private Thread readerThread;

    @Override public String type() { return "csv-file"; }

    @Override
    public void init(Map<String, Object> params) {
        if (params == null) return;
        path          = (String) params.get("path");
        if (path == null) throw new IllegalArgumentException("csv-file: 'path' required");
        hasHeader     = bool(params.get("hasHeader"), true);
        delimiter     = str(params.get("delimiter"),  ",");
        loop          = bool(params.get("loop"),       false);
        perRowDelayMs = num(params.get("perRowDelayMs"), 0L);
        columnFilter  = (String) params.get("columnFilter");

        readerThread = new Thread(this::readLoop, "csv-edge-src");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        do {
            try (BufferedReader br = Files.newBufferedReader(Path.of(path))) {
                String[] headers = null;
                String line; int row = 0;
                while (!stopped && (line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] parts = line.split(delimiter, -1);
                    if (hasHeader && row == 0) { headers = parts; row++; continue; }
                    queue.put(toPayload(parts, headers));
                    row++;
                    if (perRowDelayMs > 0) Thread.sleep(perRowDelayMs);
                }
            } catch (InterruptedException ie) { return; }
            catch (Exception e) {
                log.warn("csv error: {}", e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        } while (!stopped && loop);
        done = true;
    }

    @Override
    public Map<String, Object> next() throws InterruptedException {
        while (true) {
            Map<String, Object> item = queue.poll();
            if (item != null) return item;
            if (done && queue.isEmpty()) return null;
            Thread.sleep(50);
        }
    }

    @Override
    public void close() {
        stopped = true;
        if (readerThread != null) readerThread.interrupt();
    }

    /** Identico a CsvFileSource.toPayload() del runtime completo. */
    private Map<String, Object> toPayload(String[] parts, String[] headers) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (columnFilter != null && headers != null) {
            for (int i = 0; i < headers.length && i < parts.length; i++) {
                if (columnFilter.equals(headers[i])) { m.put("value", parts[i]); return m; }
            }
            return m;
        }
        if (headers != null) {
            for (int i = 0; i < headers.length && i < parts.length; i++)
                m.put(headers[i], parts[i]);
        } else {
            for (int i = 0; i < parts.length; i++) m.put("col" + i, parts[i]);
        }
        return m;
    }

    private boolean bool(Object o, boolean d) { return o instanceof Boolean b ? b : d; }
    private String  str(Object o,  String  d) { return o instanceof String  s ? s : d; }
    private long    num(Object o,  long    d) { return o instanceof Number  n ? n.longValue() : d; }
}
