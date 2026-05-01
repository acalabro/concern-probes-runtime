package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProbeDefinition {
    public String id;
    public String name;
    /** Logical type name — goes into property.probeType. Replaces ConcernXxxProbe class name. */
    public String probeType;
    public String description;
    public BrokerConfig broker;
    /** Optional active data source (csv-file, synthetic, tail-file, http-poll). */
    public SourceConfig source;
    /** Optional HTTP ingest endpoint. */
    public IngestConfig ingest;
    /** How to build the event from raw payload. */
    public EventTemplate eventTemplate;
    /** Offline SQLite buffer behaviour. */
    public BufferConfig buffer;
    /** Auto-start on runtime boot. */
    public boolean autoStart = true;
}
