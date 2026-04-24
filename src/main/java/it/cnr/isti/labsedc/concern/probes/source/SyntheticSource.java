package it.cnr.isti.labsedc.concern.probes.source;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Emits synthetic events at fixed rate. Useful for wiring-up and smoke tests.
 * Params: intervalMs (default 1000), valueMin (default 0), valueMax (default 100).
 */
public class SyntheticSource implements SourceAdapter {

    @Override public String type() { return "synthetic"; }

    @Override
    public SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> p) {
        long   interval = num(p.get("intervalMs"), 1000L);
        double min      = dbl(p.get("valueMin"),   0.0);
        double max      = dbl(p.get("valueMax"),   100.0);
        return new Runner(probe, interval, min, max);
    }

    private static long   num(Object o, long   d) { return o instanceof Number n ? n.longValue()   : d; }
    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }

    private static class Runner implements SourceRunner {
        private static final Logger log = LoggerFactory.getLogger(Runner.class);
        private final ConcernConfigurableProbe probe;
        private final long intervalMs;
        private final double min, max;
        private final Random rng = new Random();
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "synthetic-src"); t.setDaemon(true); return t;
        });

        Runner(ConcernConfigurableProbe probe, long intervalMs, double min, double max) {
            this.probe = probe; this.intervalMs = intervalMs; this.min = min; this.max = max;
        }

        @Override public void start() {
            log.info("[{}] synthetic source starting (every {}ms)", probe.getDefinition().name, intervalMs);
            exec.scheduleAtFixedRate(() -> {
                try {
                    double v = min + rng.nextDouble() * (max - min);
                    probe.ingestFromSource(Map.of("value", v, "ts", System.currentTimeMillis()), "synthetic");
                } catch (Throwable t) { log.warn("synthetic tick: {}", t.getMessage()); }
            }, 0, intervalMs, TimeUnit.MILLISECONDS);
        }

        @Override public void stop() { exec.shutdownNow(); }
    }
}
