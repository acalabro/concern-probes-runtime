package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProbeStatus {
    public String  id;
    public String  name;
    public String  probeType;
    public String  state;
    public boolean ingestEnabled;
    public String  ingestPath;
    public boolean sourceEnabled;
    public String  sourceType;
    public long    sentCount;
    public long    failedCount;
    public long    bufferedCount;
    public long    lastSuccessAt;
    public String  lastError;
}
