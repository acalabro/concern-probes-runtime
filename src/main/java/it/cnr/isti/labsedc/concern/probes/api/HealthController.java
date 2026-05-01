package it.cnr.isti.labsedc.concern.probes.api;

import java.util.Map;

import io.javalin.Javalin;
import it.cnr.isti.labsedc.concern.probes.core.ProbeManager;
import it.cnr.isti.labsedc.concern.probes.core.ProbeStatus;

public class HealthController {

    private final ProbeManager manager;
    private final String       nodeId;
    private final long         startedAt = System.currentTimeMillis();

    public HealthController(ProbeManager manager, String nodeId) {
        this.manager = manager; this.nodeId = nodeId;
    }

    public void register(Javalin app) {
        app.get("/health",  this::health);
        app.get("/metrics", this::metrics);
    }

    private void health(Context ctx) {
        String brokerUrl = System.getenv("BROKER_URL");
        ctx.json(Map.of(
                "status",    "ok",
                "nodeId",    nodeId,
                "uptimeMs",  System.currentTimeMillis() - startedAt,
                "probes",    manager.listStatus().size(),
                "brokerUrl", brokerUrl != null ? brokerUrl : "not set (use probe-level config)"
        ));
    }

    private void metrics(Context ctx) {
        StringBuilder sb = new StringBuilder();
        appendMetric(sb, "probe_events_sent_total",     "Events successfully delivered to broker",    "counter");
        for (ProbeStatus s : manager.listStatus()) {
			sb.append(String.format("probe_events_sent_total{probe=\"%s\",type=\"%s\"} %d%n", s.id, s.probeType, s.sentCount));
		}

        appendMetric(sb, "probe_events_failed_total",   "Delivery failures",                          "counter");
        for (ProbeStatus s : manager.listStatus()) {
			sb.append(String.format("probe_events_failed_total{probe=\"%s\"} %d%n", s.id, s.failedCount));
		}

        appendMetric(sb, "probe_events_buffered_total", "Events currently held in the offline buffer", "counter");
        for (ProbeStatus s : manager.listStatus()) {
			sb.append(String.format("probe_events_buffered_total{probe=\"%s\"} %d%n", s.id, s.bufferedCount));
		}

        ctx.contentType("text/plain; version=0.0.4").result(sb.toString());
    }

    private void appendMetric(StringBuilder sb, String name, String help, String type) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }
}
