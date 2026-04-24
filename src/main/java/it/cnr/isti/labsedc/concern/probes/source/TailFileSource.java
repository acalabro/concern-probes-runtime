package it.cnr.isti.labsedc.concern.probes.source;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Follows a file (like tail -F) or polls a single-line file repeatedly.
 * Params: path (required), mode ("tail"|"poll", default "tail"),
 *         pollIntervalMs (1000), fromBeginning (false).
 */
public class TailFileSource implements SourceAdapter {

    @Override public String type() { return "tail-file"; }

    @Override
    public SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> p) {
        String path = (String) p.get("path");
        if (path == null) throw new IllegalArgumentException("tail-file: 'path' required");
        String mode     = p.getOrDefault("mode", "tail").toString();
        long   interval = ((Number) p.getOrDefault("pollIntervalMs", 1000L)).longValue();
        boolean fromStart = p.getOrDefault("fromBeginning", false) instanceof Boolean b && b;
        return new Runner(probe, path, mode, interval, fromStart);
    }

    private static class Runner implements SourceRunner {
        private static final Logger log = LoggerFactory.getLogger(Runner.class);
        private final ConcernConfigurableProbe probe;
        private final String path, mode;
        private final long pollMs;
        private final boolean fromStart;
        private volatile boolean stopped;
        private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tail-src"); t.setDaemon(true); return t;
        });

        Runner(ConcernConfigurableProbe p, String path, String mode, long pollMs, boolean fromStart) {
            this.probe = p; this.path = path; this.mode = mode; this.pollMs = pollMs; this.fromStart = fromStart;
        }

        @Override public void start() {
            log.info("[{}] tail-file starting path={} mode={}", probe.getDefinition().name, path, mode);
            exec.submit("poll".equals(mode) ? this::pollLoop : this::tailLoop);
        }

        private void pollLoop() {
            while (!stopped) {
                try {
                    String content = Files.readString(Path.of(path)).trim();
                    if (!content.isEmpty())
                        probe.ingestFromSource(Map.of("value", content, "ts", System.currentTimeMillis()), "tail-file");
                    Thread.sleep(pollMs);
                } catch (InterruptedException ie) { return; }
                catch (Exception e) { log.warn("poll: {}", e.getMessage()); sleep(pollMs); }
            }
        }

        private void tailLoop() {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                if (!fromStart) raf.seek(raf.length());
                while (!stopped) {
                    String line = raf.readLine();
                    if (line == null) { Thread.sleep(pollMs); continue; }
                    if (!line.isBlank())
                        probe.ingestFromSource(Map.of("value", line, "ts", System.currentTimeMillis()), "tail-file");
                }
            } catch (InterruptedException ie) {
                // normal stop
            } catch (Exception e) { log.warn("tail: {}", e.getMessage()); }
        }

        private void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        @Override public void stop() { stopped = true; exec.shutdownNow(); }
    }
}
