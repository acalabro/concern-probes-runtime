package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceConfig {
    public String type;
    public Map<String, Object> config = new HashMap<>();
}
