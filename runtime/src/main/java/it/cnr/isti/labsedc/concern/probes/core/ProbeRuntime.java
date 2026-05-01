package it.cnr.isti.labsedc.concern.probes.core;

import it.cnr.isti.labsedc.concern.probes.source.SourceAdapter;
import it.cnr.isti.labsedc.concern.probes.source.SourceRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Lifecycle wrapper for a probe + optional active source.
 *
 * A key design constraint: ExecutorService.shutdownNow() cannot be restarted,
 * so every call to start() creates a fresh SourceRunner from the adapter factory.
 * This makes stop/start work correctly any number of times.
 */
public class ProbeRuntime {

    private static final Logger log = LoggerFactory.getLogger(ProbeRuntime.class);

    public enum State { CREATED, RUNNING, STOPPED, ERROR }

    private final ProbeDefinition          def;
    private final ConcernConfigurableProbe probe;
    private final SourceAdapter            sourceAdapter;   // null if no source
    private final Map<String, Object>      sourceParams;

    private volatile State        state         = State.CREATED;
    private volatile SourceRunner currentRunner = null;

    public ProbeRuntime(ProbeDefinition def,
                        ConcernConfigurableProbe probe,
                        SourceAdapter sourceAdapter,
                        Map<String, Object> sourceParams) {
        this.def           = def;
        this.probe         = probe;
        this.sourceAdapter = sourceAdapter;
        this.sourceParams  = sourceParams != null ? sourceParams : Map.of();
    }

    public synchronized void start() {
        if (state == State.RUNNING) return;
        if (sourceAdapter != null) {
            try {
                currentRunner = sourceAdapter.create(probe, sourceParams);
                currentRunner.start();
            } catch (Exception e) {
                log.error("[{}] source start failed: {}", def.name, e.getMessage());
                state = State.ERROR;
                return;
            }
        }
        state = State.RUNNING;
        log.info("[{}] started", def.name);
    }

    public synchronized void stop() {
        if (state == State.STOPPED || state == State.CREATED) return;
        if (currentRunner != null) {
            try { currentRunner.stop(); } catch (Exception ignored) {}
            currentRunner = null;
        }
        state = State.STOPPED;
        log.info("[{}] stopped", def.name);
    }

    public synchronized void destroy() {
        stop();
        probe.close();
    }

    public ProbeDefinition          getDefinition()  { return def; }
    public ConcernConfigurableProbe getProbe()       { return probe; }
    public State                    getState()       { return state; }
    public void                     setState(State s){ state = s; }
}
