package in.reconpilot.ingest;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Writes an upload to local disk without ever holding it in memory.
 *
 * <h2>Why staging exists at all</h2>
 *
 * Spring's multipart handling spools the upload to a temporary file and
 * <b>deletes it when the request completes</b>. Ingestion is asynchronous, so
 * the request completes long before the worker starts reading -- the file would
 * vanish underneath it. Copying to our own staging directory gives the file a
 * lifetime we control rather than one tied to an HTTP request.
 *
 * <h2>Why the hash is computed here</h2>
 *
 * The bytes have to be read once to copy them. {@link DigestInputStream}
 * computes SHA-256 as they pass, so idempotency costs nothing extra. Hashing
 * afterwards would read a 2 GB file a second time for no reason.
 */
@Service
public class FileStagingService {

    private static final Logger log = LoggerFactory.getLogger(FileStagingService.class);

    private final Path stagingDir;

    public FileStagingService(
            @Value("${reconpilot.ingest.staging-dir:${java.io.tmpdir}/reconpilot-staging}") String dir) {
        this.stagingDir = Path.of(dir);
    }

    @PostConstruct
    void ensureDirectory() throws IOException {
        Files.createDirectories(stagingDir);
        log.info("Ingestion staging directory: {}", stagingDir.toAbsolutePath());
    }

    /**
     * Streams the upload to disk, hashing as it goes.
     *
     * <p>{@link Files#copy} moves the data through a small buffer, so peak
     * memory is that buffer and not the file. A 2 GB upload costs the same
     * heap as a 2 KB one.
     */
    public StagedFile stage(InputStream upload, String originalName) throws IOException {
        Path target = stagingDir.resolve(UUID.randomUUID() + "-" + safeName(originalName));
        MessageDigest digest = sha256();

        long bytes;
        try (InputStream in = new DigestInputStream(upload, digest)) {
            bytes = Files.copy(in, target);
        }

        String hash = HexFormat.of().formatHex(digest.digest());
        log.info("Staged {} ({} bytes, sha256 {}) at {}", originalName, bytes, hash.substring(0, 12), target);
        return new StagedFile(target, hash, bytes);
    }

    /** Called when a batch finishes, successfully or not. */
    public void discard(Path staged) {
        try {
            if (staged != null && staged.startsWith(stagingDir)) {
                Files.deleteIfExists(staged);
            }
        } catch (IOException e) {
            // Losing a staged file is untidy, not incorrect: the batch row and
            // its rows are already durable. Log and move on.
            log.warn("Could not delete staged file {}", staged, e);
        }
    }

    /**
     * Strips any path information from the client-supplied name.
     *
     * <p>A filename arrives from the caller and is never trustworthy: a name
     * like {@code ../../etc/passwd} would otherwise let an upload escape the
     * staging directory. Only the final component is kept, and only safe
     * characters within it.
     */
    static String safeName(String original) {
        if (original == null || original.isBlank()) return "upload.csv";

        // A backslash is a separator on Windows and an ordinary character on
        // Unix, so a Windows-style name would otherwise arrive here as one
        // long filename with its separators intact. Normalising first makes
        // the behaviour the same wherever this runs.
        String base = original.replace('\\', '/');
        Path name = Path.of(base).getFileName();
        if (name == null) return "upload.csv";           // e.g. "/" or "../"

        String cleaned = name.toString().replaceAll("[^A-Za-z0-9._-]", "_");

        // Collapse any remaining parent-directory sequence. Not exploitable
        // once the path components are gone, but "the result never contains
        // .." is a far easier invariant to audit than "the .. left in this
        // one is harmless".
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_");
        }
        if (cleaned.isBlank() || cleaned.equals("_")) return "upload.csv";

        return cleaned.length() > 100 ? cleaned.substring(cleaned.length() - 100) : cleaned;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
