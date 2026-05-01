package it.cnr.isti.labsedc.concern.probes.api;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.org.slf4j.internal.LoggerFactory;

import io.javalin.Javalin;
import it.cnr.isti.labsedc.concern.probes.core.ProbeDefinition;
import it.cnr.isti.labsedc.concern.probes.core.ProbeManager;
import it.cnr.isti.labsedc.concern.probes.core.ProbeRuntime;
import it.cnr.isti.labsedc.concern.probes.source.SourceRegistry;

public class ProbesController {

    private static final Logger      log  = LoggerFactory.getLogger(ProbesController.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final ProbeManager        manager;
    private final SourceRegistry      sources;
    private final ProbeExportController exportCtrl;

    public ProbesController(ProbeManager manager, SourceRegistry sources) {
        this.manager    = manager;
        this.sources    = sources;
        // Percorso del fat-jar edge: configurabile via env var EDGE_JAR_PATH,
        // default nella directory di lavoro del processo.
        String edgeJar  = System.getenv().getOrDefault("EDGE_JAR_PATH", "concern-probe-edge.jar");
        this.exportCtrl = new ProbeExportController(manager, edgeJar);
    }

    public void register(Javalin app) {
        app.get   ("/api/probes",              ctx -> ctx.json(manager.listStatus()));
        app.post  ("/api/probes",              this::create);
        app.get   ("/api/probes/{id}",         this::get);
        app.put   ("/api/probes/{id}",         this::update);
        app.delete("/api/probes/{id}",         this::delete);
        app.post  ("/api/probes/{id}/start",   this::start);
        app.post  ("/api/probes/{id}/stop",    this::stop);
        app.get   ("/api/sources",             ctx -> ctx.json(sources.all().keySet()));
        // Export edge package
        exportCtrl.register(app);
    }

    private void get(Context ctx) {
        ProbeRuntime rt = manager.get(ctx.pathParam("id"));
        if (rt == null) { ctx.status(404).json(Map.of("error", "not found")); return; }
        ctx.json(Map.of("status", manager.statusOf(rt.getDefinition().id), "definition", rt.getDefinition()));
    }

    private void create(Context ctx) {
        try {
            ProbeDefinition def = parse(ctx);
            ProbeRuntime rt = manager.createOrUpdate(def);
            if (def.autoStart) {
				manager.start(def.id);
			}
            ctx.status(201).json(manager.statusOf(rt.getDefinition().id));
        } catch (Exception e) { log.warn("create: {}", e.getMessage()); ctx.status(400).json(Map.of("error", e.getMessage())); }
    }

    private void update(Context ctx) {
        try {
            ProbeDefinition def = parse(ctx);
            def.id = ctx.pathParam("id");
            ProbeRuntime rt = manager.createOrUpdate(def);
            if (def.autoStart) {
				manager.start(def.id);
			}
            ctx.json(manager.statusOf(rt.getDefinition().id));
        } catch (Exception e) { ctx.status(400).json(Map.of("error", e.getMessage())); }
    }

    private void delete(Context ctx) {
        try { manager.delete(ctx.pathParam("id")); ctx.status(204); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", e.getMessage())); }
    }

    private void start(Context ctx) {
        try { manager.start(ctx.pathParam("id")); ctx.json(manager.statusOf(ctx.pathParam("id"))); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", e.getMessage())); }
    }

    private void stop(Context ctx) {
        try { manager.stop(ctx.pathParam("id")); ctx.json(manager.statusOf(ctx.pathParam("id"))); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", e.getMessage())); }
    }

    private ProbeDefinition parse(Context ctx) throws Exception {
        String ct = ctx.header("Content-Type");
        if (ct != null && (ct.contains("yaml") || ct.contains("yml"))) {
			return YAML.readValue(ctx.body(), ProbeDefinition.class);
		}
        return ctx.bodyAsClass(ProbeDefinition.class);
    }
}
