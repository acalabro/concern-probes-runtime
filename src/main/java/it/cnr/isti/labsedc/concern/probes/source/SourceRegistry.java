package it.cnr.isti.labsedc.concern.probes.source;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;
import it.cnr.isti.labsedc.concern.probes.core.SourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

public class SourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);
    private final Map<String, SourceAdapter> byType = new HashMap<>();

    public SourceRegistry() {
        for (SourceAdapter a : ServiceLoader.load(SourceAdapter.class)) {
            byType.put(a.type(), a);
            log.info("source adapter registered: {}", a.type());
        }
    }

    public SourceRunner create(SourceConfig cfg, ConcernConfigurableProbe probe) {
        if (cfg == null || cfg.type == null) return null;
        SourceAdapter a = byType.get(cfg.type);
        if (a == null) throw new IllegalArgumentException("unknown source type: " + cfg.type);
        return a.create(probe, cfg.config != null ? cfg.config : Map.of());
    }

    public SourceAdapter getAdapter(String type) { return byType.get(type); }
    public Map<String, SourceAdapter> all() { return byType; }
}
