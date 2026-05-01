package it.cnr.isti.labsedc.concern.probes.source;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.org.slf4j.internal.LoggerFactory;

import it.cnr.isti.labsedc.concern.probes.core.ConcernConfigurableProbe;

/**
 * Polls a URL periodically and emits an event per response.
 * Params: url (required), intervalMs (5000), method ("GET"), body, headers (map),
 *         responseFormat ("json"|"text"), jsonPath (dotted path into JSON response),
 *         timeoutMs (10000).
 */
public class HttpPollSource implements SourceAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String type() { return "http-poll"; }

    @Override
    public SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> p) {
        String url = (String) p.get("url");
        if (url == null) {
			throw new IllegalArgumentException("http-poll: 'url' required");
		}
        long   interval = num(p.get("intervalMs"), 5000L);
        String method   = str(p.get("method"), "GET").toUpperCase();
        String body     = (String) p.get("body");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) p.getOrDefault("headers", Map.of());
        String format   = str(p.get("responseFormat"), "json").toLowerCase();
        String jsonPath = (String) p.get("jsonPath");
        long   timeout  = num(p.get("timeoutMs"), 10_000L);
        return new Runner(probe, url, interval, method, body, headers, format, jsonPath, timeout);
    }

    private static long   num(Object o, long   d) { return o instanceof Number n ? n.longValue() : d; }
    private static String str(Object o, String d) { return o instanceof String s ? s : d; }

    private static class Runner implements SourceRunner {
        private static final Logger log = LoggerFactory.getLogger(Runner.class);
        private final ConcernConfigurableProbe probe;
        private final String url, method, body, format, jsonPath;
        private final Map<String, String> headers;
        private final HttpClient client;
        private final Duration timeout;
        private final long intervalMs;
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "http-poll-src"); t.setDaemon(true); return t;
        });

        Runner(ConcernConfigurableProbe probe, String url, long intervalMs,
               String method, String body, Map<String, String> headers,
               String format, String jsonPath, long timeoutMs) {
            this.probe = probe; this.url = url; this.intervalMs = intervalMs;
            this.method = method; this.body = body; this.headers = headers;
            this.format = format; this.jsonPath = jsonPath;
            this.timeout = Duration.ofMillis(timeoutMs);
            this.client  = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        }

        @Override public void start() {
            log.info("[{}] http-poll starting url={} every {}ms", probe.getDefinition().name, url, intervalMs);
            exec.scheduleAtFixedRate(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void tick() {
            try {
                var reqBuilder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
                headers.forEach(reqBuilder::header);
                var publisher = body != null
                        ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody();
                reqBuilder.method(method, publisher);
                var resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    log.warn("[{}] http-poll non-2xx {}", probe.getDefinition().name, resp.statusCode());
                    return;
                }
                probe.ingestFromSource(toPayload(resp.body()), "http-poll");
            } catch (Throwable t) {
                log.warn("http-poll error: {}", t.getMessage());
            }
        }

        private Map<String, Object> toPayload(String bodyStr) {
            Map<String, Object> out = new LinkedHashMap<>();
            if ("text".equals(format)) { out.put("value", bodyStr); return out; }
            try {
                JsonNode root = MAPPER.readTree(bodyStr);
                if (jsonPath != null && !jsonPath.isBlank()) {
                    JsonNode node = root;
                    for (String part : jsonPath.split("\\.")) {
						node = node == null ? null : node.get(part);
					}
                    out.put("value", node == null ? null : node.isValueNode() ? node.asText() : node.toString());
                } else {
                    out = MAPPER.convertValue(root, LinkedHashMap.class);
                }
            } catch (Exception e) { out.put("value", bodyStr); out.put("_parseError", e.getMessage()); }
            return out;
        }

        @Override public void stop() { exec.shutdownNow(); }
    }
}
