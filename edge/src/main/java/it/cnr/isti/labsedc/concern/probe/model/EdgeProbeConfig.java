package it.cnr.isti.labsedc.concern.probe.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Rappresentazione minimale del YAML della probe per il mini-runtime edge.
 * Usa gli stessi nomi di campo di ProbeDefinition del runtime completo,
 * deserializzata con Jackson (identica struttura YAML).
 *
 * I campi non necessari all'edge (ingest, buffer) vengono ignorati.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdgeProbeConfig {

    public String id;
    public String name;
    public String probeType;
    public boolean autoStart = true;

    public BrokerConfig    broker        = new BrokerConfig();
    public SourceConfig    source;
    public EventTemplate   eventTemplate = new EventTemplate();

    // ---------- nested ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BrokerConfig {
        public String  url;
        public String  topic             = "DROOLS-InstanceOne";
        public String  username          = "system";
        public String  password          = "manager";
        public boolean ssl               = false;
        public String  keyStore;
        public String  keyStorePassword;
        public String  trustStore;
        public String  trustStorePassword;
        public String  trustedPackages   =
            "it.cnr.isti.labsedc.concern,java.lang,java.util,javax.security";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceConfig {
        public String              type;
        public Map<String, Object> config = new HashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventTemplate {
        public String              name            = "ProbeEvent";
        public String              destinationId   = "Monitoring";
        public String              sessionId;
        public String              cepType         = "DROOLS";
        public String              dataField;
        public Map<String, String> propertyPayload = new HashMap<>();
    }
}
