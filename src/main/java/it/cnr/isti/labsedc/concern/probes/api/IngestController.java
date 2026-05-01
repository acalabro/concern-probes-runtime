package it.cnr.isti.labsedc.concern.probes.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.org.slf4j.internal.LoggerFactory;

import io.javalin.Javalin;
import it.cnr.isti.labsedc.concern.probes.core.IngestResult;
import it.cnr.isti.labsedc.concern.probes.core.ProbeManager;
import it.cnr.isti.labsedc.concern.probes.core.ProbeRuntime;

/**
 * HTTP ingest endpoints. Any external caller (sensor, script, service) can POST a
 * JSON payload to POST /ingest/{probeName} to generate a ConcernBaseEvent and publish
 * it to the monitor via JMS — or buffer it locally if the broker is unreachable.
 */
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final ProbeManager manager;
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public IngestController(ProbeManager manager) { this.manager = manager; }

    public void register(Javalin app) {
        app.post("/ingest/{probeName}",       this::ingestOne);
        app.post("/ingest/{probeName}/batch", this::ingestBatch);
    }

    // ─── single event ──────────────────────────────────────────────────────

    private void ingestOne(Context ctx) {
        ProbeRuntime rt = resolve(ctx); 
        if ((rt == null) || !checkAuth(ctx, rt) || !checkRate(ctx, rt)) {
			return;
		}

        Map<String, Object> payload = readPayload(ctx); if (payload == null) {
			return;
		}
        IngestResult result = rt.getProbe().ingestHttp(payload);
        ctx.status(result.httpStatus()).json(resultMap(result));
    }

    // ─── batch ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void ingestBatch(Context ctx) {
        ProbeRuntime rt = resolve(ctx); 
        if ((rt == null) || !checkAuth(ctx, rt)) {
			return;
		}

        List<Map<String, Object>> items;
        try { items = ctx.bodyAsClass(List.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "body must be a JSON array")); return; }

        int sent = 0, buffered = 0, failed = 0;
        for (Map<String, Object> payload : items) {
            if (!checkRate(ctx, rt)) { failed++; continue; }
            switch (rt.getProbe().ingestHttp(payload).status()) {
                case SENT     -> sent++;
                case BUFFERED -> buffered++;
                case FAILED   -> failed++;
            }
        }
        ctx.status(202).json(Map.of(
                "received", items.size(), "sent", sent, "buffered", buffered, "failed", failed));
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private ProbeRuntime resolve(Context ctx) {
        String name = ctx.pathParam("probeName");
        ProbeRuntime rt = manager.getByIngestPath(name);
        if (rt == null) {
            ctx.status(404).json(Map.of("error", "no probe with ingest path: " + name)); return null;
        }
        if (rt.getDefinition().ingest == null || !rt.getDefinition().ingest.enabled) {
            ctx.status(404).json(Map.of("error", "ingest not enabled for: " + name)); return null;
        }
        return rt;
    }

    private boolean checkAuth(Context ctx, ProbeRuntime rt) {
        String expected = rt.getDefinition().ingest.authToken;
        if (expected == null || expected.isBlank()) {
			return true;
		}
        String auth = ctx.header("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || !expected.equals(auth.substring(7).trim())) {
            ctx.status(401).json(Map.of("error", "invalid or missing bearer token")); return false;
        }
        return true;
    }

    private boolean checkRate(Context ctx, ProbeRuntime rt) {
        int limit = rt.getDefinition().ingest.rateLimitPerSecond;
        if (limit <= 0) {
			return true;
		}
        RateLimiter rl = limiters.computeIfAbsent(rt.getDefinition().id, k -> new RateLimiter(limit));
        if (!rl.tryAcquire()) {
            ctx.status(429).json(Map.of("error", "rate limit exceeded (" + limit + "/s)")); return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(Context ctx) {
        try {
            Object body = ctx.bodyAsClass(Object.class);
            if (body instanceof Map) {
				return (Map<String, Object>) body;
			}
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("value", body);
            return wrap;
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON body")); return null;
        }
    }

    private Map<String, Object> resultMap(IngestResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",  r.status().name().toLowerCase());
        m.put("eventId", r.eventId() != null ? r.eventId() : "");
        if (r.error() != null) {
			m.put("error", r.error());
		}
        return m;
    }

    /** Simple per-second token bucket. */
    private static final class RateLimiter {
        private final int limit;
        private final AtomicInteger tokens;
        private volatile long windowStart = System.currentTimeMillis();

        RateLimiter(int limit) { this.limit = limit; this.tokens = new AtomicInteger(limit); }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 1000) { windowStart = now; tokens.set(limit); }
            return tokens.getAndDecrement() > 0;
        }
    }
}
