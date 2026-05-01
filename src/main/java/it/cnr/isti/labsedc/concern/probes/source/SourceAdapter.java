package it.cnr.isti.labsedc.concern.probes.source;

import java.util.Map;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;

public interface SourceAdapter {
    String type();
    SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> params);
}
