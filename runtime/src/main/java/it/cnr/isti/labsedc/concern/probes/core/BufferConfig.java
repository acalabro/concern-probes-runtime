package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BufferConfig {
    public boolean enabled = true;
    public int maxSizeMB = 50;
    public int maxAgeHours = 24;
    public int retryIntervalSeconds = 15;
}
