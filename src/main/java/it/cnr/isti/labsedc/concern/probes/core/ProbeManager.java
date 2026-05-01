package it.cnr.isti.labsedc.concern.probes.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.org.slf4j.internal.LoggerFactory;

import it.cnr.isti.labsedc.concern.probes.buffer.OfflineBuffer;
import it.cnr.isti.labsedc.concern.probes.persist.ProbeConfigStore;
import it.cnr.isti.labsedc.concern.probes.source.SourceAdapter;
import it.cnr.isti.labsedc.concern.probes.source.SourceRegistry;

public class ProbeManager {

    private static final Logger log = LoggerFactory.getLogger(ProbeManager.class);

    private final String            nodeId;
    private final OfflineBuffer     buffer;
    private final ProbeConfigStore  store;
    private final SourceRegistry    sources;
    private final int               retryIntervalSec;

    private final ConcurrentMap<String, ProbeRuntime> byId   = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String>        byPath = new ConcurrentHashMap<>(); // ingest path → id

    private final ScheduledExecutorService flushExec;

    public ProbeManager(String nodeId, OfflineBuffer buffer, ProbeConfigStore store,
                        SourceRegistry sources, int retryIntervalSec) {
        this.nodeId           = nodeId;
        this.buffer           = buffer;
        this.store            = store;
        this.sources          = sources;
        this.retryIntervalSec = retryIntervalSec;
        AtomicInteger idx = new AtomicInteger();
        this.flushExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "buf-flush-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void bootstrap() {
        try {
            for (ProbeDefinition def : store.loadAll()) {
                try {
                    register(def);
                    if (def.autoStart) {
						start(def.id);
					}
                } catch (Exception e) {
                    log.error("bootstrap failed for {}: {}", def.id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("bootstrap error: {}", e.getMessage());
        }
        flushExec.scheduleAtFixedRate(this::flushAll,
                retryIntervalSec, retryIntervalSec, TimeUnit.SECONDS);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public ProbeRuntime createOrUpdate(ProbeDefinition def) throws Exception {
        if (def.id != null && byId.containsKey(def.id)) {
            stop(def.id);
            destroyInternal(def.id);
        }
        store.save(def);
        register(def);
        return byId.get(def.id);
    }

    public void start(String id) {
        ProbeRuntime rt = required(id);
        if (rt.getState() == ProbeRuntime.State.STOPPED) {
            // Source runner executors are shut down on stop() — must recreate.
            ProbeDefinition def = rt.getDefinition();
            destroyInternal(id);
            register(def);
            required(id).start();
        } else {
            rt.start();
        }
    }

    public void stop(String id) {
        required(id).stop();
    }

    public void delete(String id) throws Exception {
        stop(id);
        destroyInternal(id);
        store.delete(id);
    }

    public ProbeRuntime         get(String id)               { return byId.get(id); }
    public ProbeRuntime         getByIngestPath(String path) { String id = byPath.get(path); return id != null ? byId.get(id) : null; }
    public List<ProbeStatus>    listStatus()                 { return byId.values().stream().map(this::toStatus).toList(); }
    public ProbeStatus          statusOf(String id)          { ProbeRuntime rt = byId.get(id); return rt == null ? null : toStatus(rt); }

    // ── internals ────────────────────────────────────────────────────────────

    private void register(ProbeDefinition def) {
        ConcernConfigurableProbe probe = new ConcernConfigurableProbe(def, nodeId, buffer);
        SourceAdapter adapter = null;
        Map<String, Object> sourceParams = Map.of();
        if (def.source != null && def.source.type != null) {
            adapter = sources.getAdapter(def.source.type);
            if (adapter == null) {
				log.warn("unknown source type '{}' for probe {}", def.source.type, def.id);
			}
            sourceParams = def.source.config != null ? def.source.config : Map.of();
        }
        ProbeRuntime rt = new ProbeRuntime(def, probe, adapter, sourceParams);
        byId.put(def.id, rt);
        if (def.ingest != null && def.ingest.enabled) {
            byPath.put(def.ingest.path != null ? def.ingest.path : def.probeType, def.id);
        }
    }

    private void destroyInternal(String id) {
        ProbeRuntime rt = byId.remove(id);
        if (rt == null) {
			return;
		}
        rt.destroy();
        if (rt.getDefinition().ingest != null) {
            String p = rt.getDefinition().ingest.path != null
                    ? rt.getDefinition().ingest.path : rt.getDefinition().probeType;
            byPath.remove(p);
        }
    }

    private void flushAll() {
        for (ProbeRuntime rt : byId.values()) {
            if (rt.getState() == ProbeRuntime.State.RUNNING) {
                try { rt.getProbe().flushBuffer(); }
                catch (Exception e) { log.debug("flush error {}: {}", rt.getDefinition().id, e.getMessage()); }
            }
        }
    }

    private ProbeRuntime required(String id) {
        ProbeRuntime rt = byId.get(id);
        if (rt == null) {
			throw new NoSuchElementException("no probe: " + id);
		}
        return rt;
    }

    private ProbeStatus toStatus(ProbeRuntime rt) {
        ProbeDefinition d = rt.getDefinition();
        ProbeStatus s = new ProbeStatus();
        s.id            = d.id;
        s.name          = d.name;
        s.probeType     = d.probeType;
        s.state         = rt.getState().name();
        s.brokerUrl     = d.broker != null ? d.broker.url : null;
        s.ingestEnabled = d.ingest != null && d.ingest.enabled;
        s.ingestPath    = s.ingestEnabled ? (d.ingest.path != null ? d.ingest.path : d.probeType) : null;
        s.sourceEnabled = d.source != null;
        s.sourceType    = d.source != null ? d.source.type : null;
        s.sentCount     = rt.getProbe().getSentCount();
        s.failedCount   = rt.getProbe().getFailedCount();
        s.bufferedCount = rt.getProbe().getBufferedCount();
        s.lastSuccessAt = rt.getProbe().getLastSuccessAt();
        s.lastError     = rt.getProbe().getLastError();
        return s;
    }

    public void shutdown() {
        flushExec.shutdownNow();
        new ArrayList<>(byId.keySet()).forEach(id -> { try { destroyInternal(id); } catch (Exception ignored) {} });
    }
}
