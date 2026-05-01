package it.cnr.isti.labsedc.concern.probes.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.cnr.isti.labsedc.concern.cep.CepType;
import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import it.cnr.isti.labsedc.concern.probes.util.PlaceholderResolver;

/**
 * Converts an arbitrary payload Map into a ConcernBaseEvent.
 * All probe-specific fields go into the 'property' JSON string:
 * <pre>
 * {
 *   "schemaVersion": "1.0",
 *   "probeType":  "ConcernXxx",
 *   "probeNode":  "edge-01",
 *   "producedAt": 1714000000000,
 *   "source":     "http-ingest"|"csv-file"|...,
 *   "payload":    { ...user fields... }
 * }
 * </pre>
 */
public class EventBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProbeDefinition def;
    private final String nodeId;

    public EventBuilder(ProbeDefinition def, String nodeId) {
        this.def = def;
        this.nodeId = nodeId;
    }

    public ConcernBaseEvent<String> build(Map<String, Object> rawPayload, String sourceLabel) {
        long now = System.currentTimeMillis();

        Map<String, Object> payload = applyMapping(rawPayload);

        Map<String, Object> property = new LinkedHashMap<>();
        property.put("schemaVersion", "1.0");
        property.put("probeType",     def.probeType);
        property.put("probeNode",     nodeId);
        property.put("producedAt",    now);
        property.put("source",        sourceLabel);
        property.put("payload",       payload);

        String propertyJson;
        try {
            propertyJson = MAPPER.writeValueAsString(property);
        } catch (JsonProcessingException e) {
            propertyJson = "{\"schemaVersion\":\"1.0\",\"_error\":\"serialization failed\"}";
        }

        PlaceholderResolver r = new PlaceholderResolver(payload);
        String eventName = r.resolve(def.eventTemplate != null ? def.eventTemplate.name       : "ProbeEvent");
        String dest      = r.resolve(def.eventTemplate != null ? def.eventTemplate.destinationId : "Monitoring");
        String sid       = def.eventTemplate != null && def.eventTemplate.sessionId != null
                           ? r.resolve(def.eventTemplate.sessionId) : UUID.randomUUID().toString();

        // data must never be null: MySQLStorageController calls getData().getClass() without null-check.
        // If dataField is not configured or the placeholder doesn't resolve, fall back to the
        // serialised payload JSON so the monitor always receives a non-null, meaningful string.
        String data = "";
        if (def.eventTemplate != null && def.eventTemplate.dataField != null) {
            String resolved = r.resolve(def.eventTemplate.dataField);
            data = resolved != null ? resolved : "";
        }
        if (data.isEmpty()) {
            // fallback: whole payload as JSON string
            try { data = MAPPER.writeValueAsString(payload); }
            catch (Exception ignored) { data = payload.toString(); }
        }

        CepType cep = CepType.DROOLS;
        if (def.eventTemplate != null && def.eventTemplate.cepType != null) {
            try { cep = CepType.valueOf(def.eventTemplate.cepType.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        return new ConcernBaseEvent<>(now, nodeId, dest, sid, "noChecksum", eventName,
                                     data, cep, false, propertyJson);
    }

    private Map<String, Object> applyMapping(Map<String, Object> raw) {
        if (raw == null) {
			raw = Map.of();
		}
        Map<String, String> tmpl = def.eventTemplate != null ? def.eventTemplate.propertyPayload : null;
        if (tmpl == null || tmpl.isEmpty()) {
			return raw;
		}
        PlaceholderResolver r = new PlaceholderResolver(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        tmpl.forEach((k, v) -> out.put(k, r.resolve(v)));
        return out;
    }
}
