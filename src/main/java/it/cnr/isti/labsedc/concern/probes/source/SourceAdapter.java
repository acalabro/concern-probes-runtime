package it.cnr.isti.labsedc.concern.probes.source;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;
import java.util.Map;

public interface SourceAdapter {
    String type();
    SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> params);
}
