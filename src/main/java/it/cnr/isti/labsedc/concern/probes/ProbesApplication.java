package it.cnr.isti.labsedc.concern.probes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import it.cnr.isti.labsedc.concern.probes.api.*;
import it.cnr.isti.labsedc.concern.probes.buffer.*;
import it.cnr.isti.labsedc.concern.probes.core.*;
import it.cnr.isti.labsedc.concern.probes.persist.ProbeConfigStore;
import it.cnr.isti.labsedc.concern.probes.source.SourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.*;

/**
 * Entry point. Run:
 *   java -jar concern-probes-runtime.jar [config/application.yml]
 *
 * Env var overrides (12-factor):
 *   PROBE_NODE_ID, HTTP_PORT, PROBES_DIR, BUFFER_DB_PATH, ADMIN_TOKEN
 */
public class ProbesApplication {

    private static final Logger log = LoggerFactory.getLogger(ProbesApplication.class);

    public static void main(String[] args) throws Exception {
        String cfgPath = args.length > 0 ? args[0]
                : System.getenv().getOrDefault("APP_CONFIG", "config/application.yml");

        AppConfig cfg = loadConfig(cfgPath);
        applyEnv(cfg);

        log.info("Concern Probes Runtime starting — node={} port={}", cfg.nodeId, cfg.httpPort);

        OfflineBuffer    buffer  = createBuffer(cfg);
        ProbeConfigStore store   = new ProbeConfigStore(cfg.probesDir);
        SourceRegistry   sources = new SourceRegistry();
        ProbeManager     manager = new ProbeManager(cfg.nodeId, buffer, store, sources, cfg.retryIntervalSeconds);
        manager.bootstrap();

        Javalin app = Javalin.create(jcfg -> {
            if (cfg.uiEnabled) {
                jcfg.staticFiles.add(sc -> {
                    sc.hostedPath = "/ui";
                    sc.directory  = "/static";
                    sc.location   = Location.CLASSPATH;
                });
            }
        });

        // auth BEFORE endpoints
        new AuthFilter(cfg.adminToken).register(app);
        new ProbesController(manager, sources).register(app);
        new IngestController(manager).register(app);
        new HealthController(manager, cfg.nodeId).register(app);
        app.get("/", ctx -> ctx.redirect(cfg.uiEnabled ? "/ui/" : "/health"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down...");
            try { manager.shutdown(); } catch (Exception ignored) {}
            try { app.stop(); }         catch (Exception ignored) {}
            try { buffer.close(); }     catch (Exception ignored) {}
        }, "shutdown"));

        app.start(cfg.httpPort);
        log.info("listening on :{}", cfg.httpPort);
    }

    private static AppConfig loadConfig(String path) throws Exception {
        if (Files.exists(Path.of(path))) {
            return new ObjectMapper(new YAMLFactory()).readValue(Path.of(path).toFile(), AppConfig.class);
        }
        log.warn("config {} not found — using defaults", path);
        return new AppConfig();
    }

    private static void applyEnv(AppConfig cfg) throws Exception {
        String v;
        if ((v = System.getenv("PROBE_NODE_ID"))  != null) cfg.nodeId        = v;
        if ((v = System.getenv("HTTP_PORT"))       != null) cfg.httpPort      = Integer.parseInt(v);
        if ((v = System.getenv("PROBES_DIR"))      != null) cfg.probesDir     = v;
        if ((v = System.getenv("BUFFER_DB_PATH"))  != null) cfg.bufferDbPath  = v;
        if ((v = System.getenv("ADMIN_TOKEN"))     != null) cfg.adminToken    = v;
        // MySQL coords (for future source adapters)
        if ((v = System.getenv("MYSQL_HOST"))      != null) cfg.mysqlHost     = v;
        if ((v = System.getenv("MYSQL_PORT"))      != null) cfg.mysqlPort     = Integer.parseInt(v);
        if ((v = System.getenv("MYSQL_DATABASE"))  != null) cfg.mysqlDatabase = v;
        if ((v = System.getenv("MYSQL_USER"))      != null) cfg.mysqlUser     = v;
        if ((v = System.getenv("MYSQL_PASSWORD"))  != null) cfg.mysqlPassword = v;
        if (cfg.nodeId == null || cfg.nodeId.isBlank())
            cfg.nodeId = InetAddress.getLocalHost().getHostName();
    }

    private static OfflineBuffer createBuffer(AppConfig cfg) {
        try {
            Path p = Path.of(cfg.bufferDbPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            return new SqliteBuffer(cfg.bufferDbPath);
        } catch (Exception e) {
            log.error("SQLite buffer failed ({}), using noop", e.getMessage());
            return new NoopBuffer();
        }
    }
}
