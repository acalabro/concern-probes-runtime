package it.cnr.isti.labsedc.concern.probes.core;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceConfig {
    public String type;
    public Map<String, Object> config = new HashMap<>();
}
