package it.cnr.isti.labsedc.concern.probes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.javalin.Javalin;
import io.javalin.http.Context;
import it.cnr.isti.labsedc.concern.probes.core.ProbeDefinition;
import it.cnr.isti.labsedc.concern.probes.core.ProbeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.zip.*;

/**
 * Aggiunge l'endpoint GET /api/probes/{id}/export al runtime completo.
 *
 * Genera uno ZIP self-contained con tutto il necessario per eseguire
 * la probe su un nodo edge con solo Docker installato.
 *
 * Il fat-jar del mini-runtime edge (concern-probe-edge.jar) deve essere
 * presente nella directory configurata in edge.jar.path (default: ./concern-probe-edge.jar).
 *
 * Registrare in ProbesController.register(app):
 *   new ProbeExportController(manager, edgeJarPath).register(app);
 */
public class ProbeExportController {

    private static final Logger       log  = LoggerFactory.getLogger(ProbeExportController.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final ProbeManager manager;
    private final String       edgeJarPath;

    public ProbeExportController(ProbeManager manager, String edgeJarPath) {
        this.manager     = manager;
        this.edgeJarPath = edgeJarPath;
    }

    public void register(Javalin app) {
        app.get("/api/probes/{id}/export", this::export);
    }

    // -------------------------------------------------------------------------

    private void export(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        var rt = manager.get(id);
        if (rt == null) {
            ctx.status(404).json(Map.of("error", "probe not found: " + id));
            return;
        }

        File edgeJar = new File(edgeJarPath);
        if (!edgeJar.exists()) {
            log.error("Edge jar not found at: {}", edgeJarPath);
            ctx.status(503).json(Map.of("error",
                "Edge jar not found. Build concern-probe-edge first: cd edge && mvn package"));
            return;
        }

        ProbeDefinition def = rt.getDefinition();
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String zipName = "concern-probe-" + def.id + "-" + timestamp;

        byte[] zipBytes = buildZip(def, edgeJar);

        ctx.header("Content-Disposition", "attachment; filename=\"" + zipName + ".zip\"");
        ctx.header("Content-Length", String.valueOf(zipBytes.length));
        ctx.contentType("application/zip");
        ctx.result(zipBytes);

        log.info("Exported edge package for probe: {}", def.id);
    }

    // -------------------------------------------------------------------------

    private byte[] buildZip(ProbeDefinition def, File edgeJar) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            // 1. Dockerfile
            addText(zos, "Dockerfile", buildDockerfile());

            // 2. YAML della probe — serializzato con lo stesso ObjectMapper YAML del runtime
            //    Il file si chiama sempre "probe.yaml" per semplicità (il Dockerfile lo referenzia)
            addText(zos, "config/probes/probe.yaml", serializeProbeYaml(def));

            // 3. .env con BROKER_URL da editare
            addText(zos, ".env", buildEnv(def));

            // 4. docker-compose.yml
            addText(zos, "docker-compose.yml", buildDockerCompose());

            // 5. README
            addText(zos, "README.md", buildReadme(def));

            // 6. Fat-jar del mini-runtime edge
            addFile(zos, "concern-probe-edge.jar", edgeJar);
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------

    private String buildDockerfile() {
        return """
                FROM eclipse-temurin:21-jre-alpine
                RUN apk add --no-cache tini \\
                 && addgroup -S probe && adduser -S probe -G probe
                WORKDIR /app
                COPY concern-probe-edge.jar app.jar
                COPY config/ config/
                RUN mkdir -p /app/data && chown -R probe:probe /app
                USER probe
                ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
                ENTRYPOINT ["/sbin/tini","--"]
                CMD ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar config/probes/probe.yaml"]
                """;
    }

    private String buildEnv(ProbeDefinition def) {
        String currentBroker = (def.broker != null && def.broker.url != null)
                ? def.broker.url : "tcp://BROKER_HOST:61616";
        return "# ── Modifica BROKER_URL con l'indirizzo del broker ActiveMQ sull'edge ──\n" +
               "BROKER_URL=" + currentBroker + "\n" +
               "BROKER_USER=" + (def.broker != null && def.broker.username != null
                                 ? def.broker.username : "system") + "\n" +
               "BROKER_PASSWORD=" + (def.broker != null && def.broker.password != null
                                     ? def.broker.password : "manager") + "\n" +
               "PROBE_NODE_ID=edge-" + def.id + "\n";
    }

    private String buildDockerCompose() {
        return """
                services:
                  probe:
                    build: .
                    restart: unless-stopped
                    env_file: .env
                    environment:
                      - BROKER_URL=${BROKER_URL}
                      - BROKER_USER=${BROKER_USER}
                      - BROKER_PASSWORD=${BROKER_PASSWORD}
                      - PROBE_NODE_ID=${PROBE_NODE_ID}
                    volumes:
                      - probe-data:/app/data
                volumes:
                  probe-data: {}
                """;
    }

    private String buildReadme(ProbeDefinition def) {
        String sourceType = def.source != null ? def.source.type : "none";
        return "# Probe: " + def.name + " (`" + def.id + "`)\n\n" +
               "Pacchetto generato da concern-probes-runtime.\n" +
               "Contiene tutto il necessario per eseguire questa probe su un nodo edge con Docker.\n\n" +
               "## Prerequisiti\n" +
               "Docker + Docker Compose installati sul nodo edge.\n\n" +
               "## Deploy\n\n" +
               "```bash\n" +
               "# 1. Decomprimi\n" +
               "unzip concern-probe-" + def.id + "-*.zip\n" +
               "cd concern-probe-" + def.id + "-*/\n\n" +
               "# 2. Imposta il broker ActiveMQ raggiungibile da questo nodo\n" +
               "nano .env   # modifica BROKER_URL\n\n" +
               "# 3. Avvia\n" +
               "docker compose up --build -d\n\n" +
               "# 4. Verifica log\n" +
               "docker compose logs -f\n" +
               "```\n\n" +
               "## Note\n" +
               "- Solo `BROKER_URL` nel file `.env` deve essere modificato.\n" +
               "- Se la probe non è corretta, rigenerarla dal runtime principale e ri-scaricarla.\n" +
               "- Il mini-runtime non ha UI né REST API: produce eventi e li invia al broker.\n" +
               "- Se il broker è irraggiungibile, il mini-runtime ritenta con backoff esponenziale\n" +
               "  (2s → 4s → 8s … max 60s) senza perdere eventi.\n\n" +
               "## Dettagli probe\n" +
               "| Campo | Valore |\n" +
               "|---|---|\n" +
               "| ID | `" + def.id + "` |\n" +
               "| Tipo | `" + def.probeType + "` |\n" +
               "| Sorgente | `" + sourceType + "` |\n" +
               "| Topic | `" + (def.broker != null ? def.broker.topic : "?") + "` |\n";
    }

    private String serializeProbeYaml(ProbeDefinition def) throws IOException {
        return YAML.writerWithDefaultPrettyPrinter().writeValueAsString(def);
    }

    // -------------------------------------------------------------------------

    private void addText(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void addFile(ZipOutputStream zos, String name, File file) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        Files.copy(file.toPath(), zos);
        zos.closeEntry();
    }
}
