package it.cnr.isti.labsedc.concern.probes.source;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Replays a CSV file row by row, one event per record.
 * Params: path (required), hasHeader (true), delimiter (","), loop (false),
 *         perRowDelayMs (0), columnFilter (optional — emits only that column as "value").
 */
public class CsvFileSource implements SourceAdapter {

    @Override public String type() { return "csv-file"; }

    @Override
    public SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> p) {
        String path  = (String) p.get("path");
        if (path == null) throw new IllegalArgumentException("csv-file: 'path' required");
        boolean header  = bool(p.get("hasHeader"), true);
        String  delim   = str(p.get("delimiter"), ",");
        boolean loop    = bool(p.get("loop"), false);
        long    delay   = num(p.get("perRowDelayMs"), 0L);
        String  colFilt = (String) p.get("columnFilter");
        return new Runner(probe, path, header, delim, loop, delay, colFilt);
    }

    private static boolean bool(Object o, boolean d) { return o instanceof Boolean b ? b : d; }
    private static String  str (Object o, String  d) { return o instanceof String  s ? s : d; }
    private static long    num (Object o, long    d) { return o instanceof Number  n ? n.longValue() : d; }

    private static class Runner implements SourceRunner {
        private static final Logger log = LoggerFactory.getLogger(Runner.class);
        private final ConcernConfigurableProbe probe;
        private final String path;
        private final boolean hasHeader, loop;
        private final String delim;
        private final long perRowDelayMs;
        private final String columnFilter;
        private volatile boolean stopped;
        private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "csv-src"); t.setDaemon(true); return t;
        });

        Runner(ConcernConfigurableProbe p, String path, boolean hasHeader, String delim,
               boolean loop, long delay, String colFilt) {
            this.probe = p; this.path = path; this.hasHeader = hasHeader;
            this.delim = delim; this.loop = loop; this.perRowDelayMs = delay; this.columnFilter = colFilt;
        }

        @Override public void start() {
            log.info("[{}] csv-file starting path={} loop={}", probe.getDefinition().name, path, loop);
            exec.submit(this::run);
        }

        private void run() {
            do {
                try (BufferedReader br = Files.newBufferedReader(Path.of(path))) {
                    String[] headers = null;
                    String line; int row = 0;
                    while (!stopped && (line = br.readLine()) != null) {
                        if (line.isBlank()) continue;
                        String[] parts = line.split(delim, -1);
                        if (hasHeader && row == 0) { headers = parts; row++; continue; }
                        probe.ingestFromSource(toPayload(parts, headers), "csv-file");
                        row++;
                        if (perRowDelayMs > 0) Thread.sleep(perRowDelayMs);
                    }
                } catch (InterruptedException ie) { return; }
                catch (Exception e) {
                    log.warn("csv error: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            } while (!stopped && loop);
        }

        private Map<String, Object> toPayload(String[] parts, String[] headers) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (columnFilter != null && headers != null) {
                for (int i = 0; i < headers.length && i < parts.length; i++) {
                    if (columnFilter.equals(headers[i])) { m.put("value", parts[i]); return m; }
                }
                return m;
            }
            if (headers != null) {
                for (int i = 0; i < headers.length && i < parts.length; i++) m.put(headers[i], parts[i]);
            } else {
                for (int i = 0; i < parts.length; i++) m.put("col" + i, parts[i]);
            }
            return m;
        }

        @Override public void stop() { stopped = true; exec.shutdownNow(); }
    }
}
