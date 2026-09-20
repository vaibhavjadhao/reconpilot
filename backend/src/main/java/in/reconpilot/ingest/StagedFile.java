package in.reconpilot.ingest;

import java.nio.file.Path;

/**
 * An uploaded file written to local staging, with its digest computed during
 * the same pass.
 *
 * @param path   where it now lives; the worker deletes it when finished
 * @param sha256 computed while copying, so the bytes are read once, not twice
 * @param bytes  size as received
 */
public record StagedFile(Path path, String sha256, long bytes) {}
