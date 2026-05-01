package it.cnr.isti.labsedc.concern.probes.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import it.cnr.isti.labsedc.concern.probes.core.ProbeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class ProbeConfigStore {

    private static final Logger      log  = LoggerFactory.getLogger(ProbeConfigStore.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path dir;

    public ProbeConfigStore(String dir) throws IOException {
        this.dir = Path.of(dir);
        Files.createDirectories(this.dir);
    }

    public List<ProbeDefinition> loadAll() throws IOException {
        List<ProbeDefinition> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".yaml") && !n.endsWith(".yml")) continue;
                try {
                    ProbeDefinition def = YAML.readValue(p.toFile(), ProbeDefinition.class);
                    if (def.id == null) def.id = n.replaceAll("\\.ya?ml$", "");
                    out.add(def);
                } catch (Exception e) {
                    log.warn("skipping invalid probe file {}: {}", n, e.getMessage());
                }
            }
        }
        return out;
    }

    public void save(ProbeDefinition def) throws IOException {
        if (def.id == null || def.id.isBlank())
            def.id = (def.probeType != null ? def.probeType : "probe") + "-"
                     + UUID.randomUUID().toString().substring(0, 8);
        sanitise(def.id);
        YAML.writerWithDefaultPrettyPrinter().writeValue(pathFor(def.id).toFile(), def);
    }

    public boolean delete(String id) throws IOException {
        sanitise(id);
        return Files.deleteIfExists(pathFor(id));
    }

    private Path pathFor(String id) { return dir.resolve(id + ".yaml"); }

    private void sanitise(String id) {
        if (id == null || id.matches(".*[/\\\\\\s].*"))
            throw new IllegalArgumentException("invalid probe id: " + id);
    }
}
