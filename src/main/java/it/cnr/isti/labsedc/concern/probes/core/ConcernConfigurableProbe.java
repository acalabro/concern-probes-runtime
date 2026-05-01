package it.cnr.isti.labsedc.concern.probes.core;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.org.slf4j.internal.LoggerFactory;

import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import it.cnr.isti.labsedc.concern.probes.buffer.OfflineBuffer;
import it.cnr.isti.labsedc.concern.probes.jms.ActiveMqPublisher;
import jakarta.jms.JMSException;

/**
 * Self-contained configurable probe. Owns its own JMS publisher — no shared
 * static state, no dependency on the upstream Concern codebase.
 */
public class ConcernConfigurableProbe {

    private static final Logger log = LoggerFactory.getLogger(ConcernConfigurableProbe.class);

    private final ProbeDefinition  def;
    private final ActiveMqPublisher publisher;
    private final EventBuilder      eventBuilder;
    private final OfflineBuffer     buffer;

    private final AtomicLong sentCount     = new AtomicLong();
    private final AtomicLong failedCount   = new AtomicLong();
    private final AtomicLong bufferedCount = new AtomicLong();
    private volatile long   lastSuccessAt = 0L;
    private volatile String lastError     = null;

    public ConcernConfigurableProbe(ProbeDefinition def, String nodeId, OfflineBuffer buffer) {
        this.def          = def;
        this.buffer       = buffer;
        this.eventBuilder = new EventBuilder(def, nodeId);
        this.publisher    = new ActiveMqPublisher(def.broker, def.name);
        // Connection is established lazily on the first send attempt.
        // If the broker is down, events go to the offline buffer automatically.
    }

    // ── public ingest API ────────────────────────────────────────────────────

    /** Called by the HTTP ingest endpoint (POST /ingest/{name}). */
    public IngestResult ingestHttp(Map<String, Object> payload) {
        return dispatch(eventBuilder.build(payload, "http-ingest"));
    }

    /** Called by a SourceRunner (csv, tail, synthetic, http-poll, …). */
    public IngestResult ingestFromSource(Map<String, Object> payload, String sourceLabel) {
        return dispatch(eventBuilder.build(payload, sourceLabel));
    }

    // ── delivery ─────────────────────────────────────────────────────────────

    private IngestResult dispatch(ConcernBaseEvent<String> event) {
        try {
            publisher.send(event);
            sentCount.incrementAndGet();
            lastSuccessAt = System.currentTimeMillis();
            lastError     = null;
            return IngestResult.sent(event.getSessionID());
        } catch (JMSException e) {
            failedCount.incrementAndGet();
            lastError = e.getMessage();
            log.warn("[{}] delivery failed: {}", def.name, lastError);
            return tryBuffer(event);
        }
    }

    private IngestResult tryBuffer(ConcernBaseEvent<String> event) {
        if (buffer == null || def.buffer == null || !def.buffer.enabled) {
            return IngestResult.failed(lastError);
        }
        try {
            buffer.enqueue(def.id, event);
            bufferedCount.incrementAndGet();
            return IngestResult.buffered(event.getSessionID());
        } catch (Exception e) {
            log.error("[{}] buffer enqueue failed: {}", def.name, e.getMessage());
            return IngestResult.failed(e.getMessage());
        }
    }

    /** Flush buffered events. Called periodically by ProbeManager. */
    public int flushBuffer() {
        if (buffer == null || def.buffer == null || !def.buffer.enabled) {
			return 0;
		}
        int flushed = 0;
        ConcernBaseEvent<?> e;
        while ((e = buffer.peek(def.id)) != null) {
            try {
                publisher.send(e);
                buffer.ack(def.id, e);
                sentCount.incrementAndGet();
                lastSuccessAt = System.currentTimeMillis();
                flushed++;
            } catch (JMSException ex) {
                lastError = ex.getMessage();
                break;
            }
        }
        if (flushed > 0) {
			log.info("[{}] flushed {} buffered events", def.name, flushed);
		}
        return flushed;
    }

    public void close() { publisher.close(); }

    // ── accessors ─────────────────────────────────────────────────────────────
    public ProbeDefinition getDefinition()  { return def; }
    public long  getSentCount()             { return sentCount.get(); }
    public long  getFailedCount()           { return failedCount.get(); }
    public long  getBufferedCount()         { return bufferedCount.get(); }
    public long  getLastSuccessAt()         { return lastSuccessAt; }
    public String getLastError()            { return lastError; }
}
