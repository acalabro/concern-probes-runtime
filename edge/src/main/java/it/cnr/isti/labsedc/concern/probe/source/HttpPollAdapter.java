package it.cnr.isti.labsedc.concern.probe.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Interroga un URL periodicamente e produce un evento per risposta.
 * Params: url (required), intervalMs (5000), method ("GET"), body, headers (map),
 *         responseFormat ("json"|"text"), jsonPath (dot-notation), timeoutMs (10000).
 *
 * Usa java.net.http.HttpClient (JDK built-in) — identico a HttpPollSource del runtime.
 * Logica toPayload identica (incluso jsonPath dot-notation).
 */
public class HttpPollAdapter implements EdgeSourceAdapter {

    private static final Logger       log    = LoggerFactory.getLogger(HttpPollAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String              url;
    private long                intervalMs  = 5000L;
    private String              method      = "GET";
    private String              body;
    private Map<String, String> headers     = new LinkedHashMap<>();
    private String              format      = "json";
    private String              jsonPath;
    private Duration            timeout;

    private HttpClient httpClient;
    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(500);
    private volatile boolean stopped = false;
    private Thread pollThread;

    @Override public String type() { return "http-poll"; }

    @Override
    public void init(Map<String, Object> params) {
        if (params == null) return;
        url        = (String) params.get("url");
        if (url == null) throw new IllegalArgumentException("http-poll: 'url' required");
        intervalMs = num(params.get("intervalMs"), 5000L);
        method     = str(params.get("method"), "GET").toUpperCase();
        body       = (String) params.get("body");
        format     = str(params.get("responseFormat"), "json").toLowerCase();
        if (params.containsKey("jsonPath")) jsonPath = params.get("jsonPath").toString();
        long timeoutMs = num(params.get("timeoutMs"), 10_000L);
        timeout    = Duration.ofMillis(timeoutMs);

        @SuppressWarnings("unchecked")
        Map<String, String> hdrs = (Map<String, String>) params.getOrDefault("headers", Map.of());
        headers.putAll(hdrs);

        httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();

        pollThread = new Thread(this::pollLoop, "http-poll-edge-src");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        while (!stopped) {
            try {
                Thread.sleep(intervalMs);
                tick();
            } catch (InterruptedException ie) { return; }
        }
    }

    private void tick() {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(timeout);
            headers.forEach(reqBuilder::header);
            HttpRequest.BodyPublisher publisher = body != null
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();
            reqBuilder.method(method, publisher);

            HttpResponse<String> resp = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() / 100 != 2) {
                log.warn("[http-poll] non-2xx {}", resp.statusCode());
                return;
            }
            queue.put(toPayload(resp.body()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.warn("http-poll error: {}", t.getMessage());
        }
    }

    /** Identico a HttpPollSource.toPayload() del runtime completo. */
    private Map<String, Object> toPayload(String bodyStr) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("text".equals(format)) { out.put("value", bodyStr); return out; }
        try {
            JsonNode root = MAPPER.readTree(bodyStr);
            if (jsonPath != null && !jsonPath.isBlank()) {
                JsonNode node = root;
                for (String part : jsonPath.split("\\."))
                    node = node == null ? null : node.get(part);
                out.put("value", node == null ? null
                        : node.isValueNode() ? node.asText() : node.toString());
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = MAPPER.convertValue(root, LinkedHashMap.class);
                out.putAll(m);
            }
        } catch (Exception e) { out.put("value", bodyStr); out.put("_parseError", e.getMessage()); }
        return out;
    }

    @Override
    public Map<String, Object> next() throws InterruptedException {
        return queue.take();
    }

    @Override
    public void close() {
        stopped = true;
        if (pollThread != null) pollThread.interrupt();
    }

    private long   num(Object o, long   d) { return o instanceof Number n ? n.longValue() : d; }
    private String str(Object o, String d) { return o instanceof String  s ? s : d; }
}
