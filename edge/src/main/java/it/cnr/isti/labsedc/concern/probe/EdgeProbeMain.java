package it.cnr.isti.labsedc.concern.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import it.cnr.isti.labsedc.concern.probe.jms.EdgePublisher;
import it.cnr.isti.labsedc.concern.probe.model.EdgeProbeConfig;
import it.cnr.isti.labsedc.concern.probe.source.*;
import it.cnr.isti.labsedc.concern.probe.util.EdgeEventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.util.Map;

/**
 * Entry point del mini-runtime edge.
 *
 * Usage:
 *   java -jar concern-probe-edge.jar [probe.yaml]
 *
 * Variabili d'ambiente (sovrascrivono il YAML):
 *   BROKER_URL       → broker ActiveMQ
 *   BROKER_USER      → username broker
 *   BROKER_PASSWORD  → password broker
 *   PROBE_NODE_ID    → identità fisica del nodo (→ senderID di ogni evento)
 *
 * Usa Jackson + jackson-dataformat-yaml per deserializzare il YAML,
 * identico al runtime completo (ProbeConfigStore).
 */
public class EdgeProbeMain {

    private static final Logger log = LoggerFactory.getLogger(EdgeProbeMain.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) throws Exception {

        // --- 1. Carica il YAML della probe ---
        String yamlPath = args.length > 0 ? args[0] : "probe.yaml";
        EdgeProbeConfig config = YAML.readValue(new File(yamlPath), EdgeProbeConfig.class);
        log.info("Loaded probe: id={} type={}", config.id, config.probeType);

        // --- 2. Override da env var (identico ad applyEnv() di ProbesApplication) ---
        String brokerUrl = System.getenv("BROKER_URL");
        if (brokerUrl != null && !brokerUrl.isBlank()) {
            log.info("BROKER_URL overridden from env: {}", brokerUrl);
            config.broker.url = brokerUrl;
        }
        String brokerUser = System.getenv("BROKER_USER");
        if (brokerUser != null) config.broker.username = brokerUser;
        String brokerPass = System.getenv("BROKER_PASSWORD");
        if (brokerPass != null) config.broker.password = brokerPass;

        // NODE_ID: env var > hostname > "edge-node"
        String nodeId = System.getenv("PROBE_NODE_ID");
        if (nodeId == null || nodeId.isBlank()) {
            try { nodeId = InetAddress.getLocalHost().getHostName(); }
            catch (Exception e) { nodeId = "edge-node"; }
        }
        log.info("Node ID: {}", nodeId);

        // --- 3. Verifica source ---
        if (config.source == null || config.source.type == null) {
            log.error("No source defined in probe YAML. Exiting.");
            System.exit(1);
        }

        // --- 4. Istanzia source adapter ---
        EdgeSourceAdapter adapter = createAdapter(config.source.type);
        adapter.init(config.source.config);
        log.info("Source adapter: {}", adapter.type());

        // --- 5. Publisher JMS ---
        EdgePublisher publisher = new EdgePublisher(config.broker, config.name);
        EdgeEventBuilder builder = new EdgeEventBuilder(config, nodeId);

        // --- 6. Shutdown hook ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down probe {}...", config.id);
            adapter.close();
            publisher.close();
        }, "edge-shutdown"));

        // --- 7. Loop principale ---
        log.info("Probe {} started. Sending to {} → topic={}",
                 config.id, config.broker.url, config.broker.topic);

        while (!Thread.currentThread().isInterrupted()) {
            Map<String, Object> payload = adapter.next();
            if (payload == null) {
                log.info("Source exhausted, probe {} exiting.", config.id);
                break;
            }
            ConcernBaseEvent<String> event = builder.build(payload, adapter.type());
            publisher.send(event);
        }

        adapter.close();
        publisher.close();
    }

    private static EdgeSourceAdapter createAdapter(String type) {
        return switch (type.toLowerCase()) {
            case "synthetic"  -> new SyntheticAdapter();
            case "csv-file"   -> new CsvFileAdapter();
            case "tail-file"  -> new TailFileAdapter();
            case "http-poll"  -> new HttpPollAdapter();
            default -> throw new IllegalArgumentException("Unknown source type: " + type);
        };
    }
}
