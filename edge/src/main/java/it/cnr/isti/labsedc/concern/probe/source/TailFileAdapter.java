package it.cnr.isti.labsedc.concern.probe.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Segue un file crescente (tail -F) o fa polling su file a riga singola.
 * Params: path (required), mode ("tail"|"poll", default "tail"),
 *         pollIntervalMs (1000), fromBeginning (false).
 * Logica identica a TailFileSource del runtime completo.
 */
public class TailFileAdapter implements EdgeSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(TailFileAdapter.class);

    private String  path;
    private String  mode      = "tail";
    private long    pollMs    = 1000L;
    private boolean fromStart = false;

    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(500);
    private volatile boolean stopped = false;
    private Thread readerThread;

    @Override public String type() { return "tail-file"; }

    @Override
    public void init(Map<String, Object> params) {
        if (params == null) return;
        path      = (String) params.getOrDefault("path", "");
        if (path.isBlank()) throw new IllegalArgumentException("tail-file: 'path' required");
        mode      = params.getOrDefault("mode", "tail").toString();
        pollMs    = ((Number) params.getOrDefault("pollIntervalMs", 1000L)).longValue();
        fromStart = params.getOrDefault("fromBeginning", false) instanceof Boolean b && b;

        readerThread = new Thread("poll".equals(mode) ? this::pollLoop : this::tailLoop,
                                  "tail-edge-src");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Identico a TailFileSource.pollLoop() */
    private void pollLoop() {
        while (!stopped) {
            try {
                String content = Files.readString(Path.of(path)).trim();
                if (!content.isEmpty())
                    queue.put(Map.of("value", content, "ts", System.currentTimeMillis()));
                Thread.sleep(pollMs);
            } catch (InterruptedException ie) { return; }
            catch (Exception e) { log.warn("poll: {}", e.getMessage()); sleep(pollMs); }
        }
    }

    /** Identico a TailFileSource.tailLoop() */
    private void tailLoop() {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            if (!fromStart) raf.seek(raf.length());
            while (!stopped) {
                String line = raf.readLine();
                if (line == null) { Thread.sleep(pollMs); continue; }
                if (!line.isBlank())
                    queue.put(Map.of("value", line, "ts", System.currentTimeMillis()));
            }
        } catch (InterruptedException ie) {
            // normal stop
        } catch (Exception e) { log.warn("tail: {}", e.getMessage()); }
    }

    @Override
    public Map<String, Object> next() throws InterruptedException {
        return queue.take(); // blocca finché disponibile
    }

    @Override
    public void close() {
        stopped = true;
        if (readerThread != null) readerThread.interrupt();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
