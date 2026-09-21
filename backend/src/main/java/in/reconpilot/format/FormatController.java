package in.reconpilot.format;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/formats")
public class FormatController {

    /** Enough rows to judge units and coded values; far fewer than a file has. */
    private static final int ROWS_TO_READ = 20;

    private final FormatMappingService mappings;
    private final JdbcTemplate jdbc;

    public FormatController(FormatMappingService mappings, JdbcTemplate jdbc) {
        this.mappings = mappings;
        this.jdbc = jdbc;
    }

    /**
     * Works out how to read an unfamiliar file.
     *
     * <p>A multipart upload, not a server path. An earlier version of this
     * endpoint took {@code ?path=} and was the same defect D4 recorded for
     * ingestion: an authenticated caller could name any file the process can
     * read, and a remote user has no files on the server to name anyway.
     *
     * <p>Unlike ingestion this never stages the file. It reads the header and
     * {@value #ROWS_TO_READ} rows from the stream and stops, so discovery costs
     * the same on a 2 GB file as on a 2 KB one and leaves nothing on disk.
     */
    @PostMapping(value = "/discover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FormatMapping discover(@RequestPart("file") MultipartFile file,
                                  @RequestParam(required = false) String label) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }

        List<String> header;
        List<Map<String, String>> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new IllegalArgumentException("The file has no header row.");
            }
            header = List.of(headerLine.split(",", -1));

            String line;
            while (rows.size() < ROWS_TO_READ && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cells = line.split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size() && c < cells.length; c++) {
                    row.put(header.get(c), cells[c]);
                }
                rows.add(row);
            }
        }

        String name = label != null ? label
                : Objects.requireNonNullElse(file.getOriginalFilename(), "uploaded file");
        return mappings.findOrDiscover(name, header, rows);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT source_label, status, amount_unit, confidence, model,
                       validation_notes, created_at
                  FROM format_mapping ORDER BY created_at DESC LIMIT 50
                """);
    }
}
