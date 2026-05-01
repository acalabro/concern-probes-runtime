package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestConfig {
    public boolean enabled = true;
    /** URL segment. Defaults to probeType value. */
    public String path;
    /** Bearer token required for this endpoint. Null = open. */
    public String authToken;
    /** Soft rate limit per second. 0 = unlimited. */
    public int rateLimitPerSecond = 0;
    /** passthrough (default): whole body → payload. template: apply eventTemplate.propertyPayload mapping. */
    public String payloadMode = "passthrough";
}
