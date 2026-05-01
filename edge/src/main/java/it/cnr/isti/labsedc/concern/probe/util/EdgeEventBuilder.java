package it.cnr.isti.labsedc.concern.probe.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.isti.labsedc.concern.cep.CepType;
import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import it.cnr.isti.labsedc.concern.probe.model.EdgeProbeConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Costruisce un ConcernBaseEvent<String> dal payload grezzo.
 *
 * Logica identica a EventBuilder.java del runtime completo:
 * - stessa struttura del campo "property" JSON
 * - stesso PlaceholderResolver per dataField e propertyPayload
 * - data non è mai null (il monitor chiama getData().getClass() senza null-check)
 * - fallback data = payload JSON serializzato
 */
public class EdgeEventBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EdgeProbeConfig config;
    private final String          nodeId;

    public EdgeEventBuilder(EdgeProbeConfig config, String nodeId) {
        this.config = config;
        this.nodeId = nodeId;
    }

    public ConcernBaseEvent<String> build(Map<String, Object> rawPayload, String sourceLabel) {
        long now = System.currentTimeMillis();

        Map<String, Object> payload = applyMapping(rawPayload);

        // property JSON — schema identico al runtime completo
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("schemaVersion", "1.0");
        property.put("probeType",     config.probeType);
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

        EdgeProbeConfig.EventTemplate tmpl = config.eventTemplate;
        EdgePlaceholderResolver r = new EdgePlaceholderResolver(payload);

        String eventName = r.resolve(tmpl != null ? tmpl.name        : "ProbeEvent");
        String dest      = r.resolve(tmpl != null ? tmpl.destinationId : "Monitoring");
        String sid       = (tmpl != null && tmpl.sessionId != null)
                           ? r.resolve(tmpl.sessionId) : UUID.randomUUID().toString();

        // data non può essere null — il monitor chiama getData().getClass() senza null-check
        String data = "";
        if (tmpl != null && tmpl.dataField != null) {
            String resolved = r.resolve(tmpl.dataField);
            data = resolved != null ? resolved : "";
        }
        if (data.isEmpty()) {
            try { data = MAPPER.writeValueAsString(payload); }
            catch (Exception ignored) { data = payload.toString(); }
        }

        CepType cep = CepType.DROOLS;
        if (tmpl != null && tmpl.cepType != null) {
            try { cep = CepType.valueOf(tmpl.cepType.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        return new ConcernBaseEvent<>(now, nodeId, dest, sid, "noChecksum",
                                     eventName, data, cep, false, propertyJson);
    }

    /** Applica il mapping propertyPayload se configurato (template mode). */
    private Map<String, Object> applyMapping(Map<String, Object> raw) {
        if (raw == null) raw = Map.of();
        EdgeProbeConfig.EventTemplate tmpl = config.eventTemplate;
        if (tmpl == null || tmpl.propertyPayload == null || tmpl.propertyPayload.isEmpty())
            return raw;
        EdgePlaceholderResolver r = new EdgePlaceholderResolver(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        tmpl.propertyPayload.forEach((k, v) -> out.put(k, r.resolve(v)));
        return out;
    }
}
