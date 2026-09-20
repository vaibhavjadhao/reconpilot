package in.reconpilot.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Development-only ingestion trigger.
 *
 * <p>It accepts a server-side path, which is fine on a laptop and unacceptable
 * in production: an attacker could name any file the process can read. The
 * production route is a streamed multipart upload or an object-store key, which
 * is a later step.
 */
@RestController
public class IngestionController {

    private final IngestionService ingestion;
    private final JdbcTemplate jdbc;

    public IngestionController(IngestionService ingestion, JdbcTemplate jdbc) {
        this.ingestion = ingestion;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/ingest")
    public IngestionResult ingest(@RequestParam String path,
                                  @RequestParam(required = false) String tenant) throws IOException {
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("Not readable: " + path);
        }
        return ingestion.ingest(tenantId(tenant == null ? "dev" : tenant), file);
    }

    /** Finds or creates a tenant by name, so local runs need no fixtures. */
    private UUID tenantId(String name) {
        List<UUID> found = jdbc.query("SELECT id FROM tenant WHERE name = ?",
                (rs, i) -> rs.getObject(1, UUID.class), name);
        if (!found.isEmpty()) return found.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", id, name);
        return id;
    }
}
