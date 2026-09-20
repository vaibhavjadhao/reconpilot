package in.reconpilot.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests: no Spring, no database, no Docker. */
class FileStagingServiceTest {

    @TempDir Path tmp;
    private FileStagingService staging;

    @BeforeEach
    void setUp() throws Exception {
        staging = new FileStagingService(tmp.toString());
        staging.ensureDirectory();
    }

    @Nested
    @DisplayName("A client-supplied filename is never trusted")
    class FilenameSafety {

        /**
         * The name arrives from whoever made the request. Used unchecked, a
         * name like ../../etc/passwd would let an upload escape the staging
         * directory and overwrite something else.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "'../../../../etc/passwd',        etc_passwd,   false",
                "'/etc/shadow',                   shadow,       true",
                "'..\\..\\windows\\system32.dll', system32.dll, true",
                "'normal-file.csv',               normal-file.csv, true",
                "'weird name;rm -rf.csv',         weird_name_rm_-rf.csv, true",
        })
        void pathComponentsAreStripped(String input, String expected, boolean exact) {
            String safe = FileStagingService.safeName(input);
            assertFalse(safe.contains("/"), "no forward slash survives: " + safe);
            assertFalse(safe.contains(".."), "no parent traversal survives: " + safe);
            if (exact) assertEquals(expected, safe);
        }

        @Test
        void aMissingNameStillProducesSomethingUsable() {
            assertEquals("upload.csv", FileStagingService.safeName(null));
            assertEquals("upload.csv", FileStagingService.safeName("   "));
        }

        @Test
        void anAbsurdlyLongNameIsTruncated() {
            assertTrue(FileStagingService.safeName("x".repeat(5_000)).length() <= 100);
        }

        /** The end-to-end property: nothing lands outside staging. */
        @Test
        void aTraversingUploadStaysInsideTheStagingDirectory() throws Exception {
            StagedFile s = staging.stage(
                    new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                    "../../../../etc/evil.csv");

            assertTrue(s.path().toAbsolutePath().normalize()
                            .startsWith(tmp.toAbsolutePath().normalize()),
                    "staged at " + s.path() + ", outside " + tmp);
        }
    }

    @Nested
    @DisplayName("Staging streams and hashes in one pass")
    class StreamAndHash {

        @Test
        void theHashMatchesTheContent() throws Exception {
            // Known SHA-256 of "abc".
            StagedFile s = staging.stage(
                    new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), "a.csv");

            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    s.sha256());
            assertEquals(3, s.bytes());
            assertEquals("abc", Files.readString(s.path()));
        }

        @Test
        void identicalContentHashesIdenticallyDespiteDifferentNames() throws Exception {
            StagedFile a = staging.stage(new ByteArrayInputStream("same".getBytes()), "one.csv");
            StagedFile b = staging.stage(new ByteArrayInputStream("same".getBytes()), "two.csv");

            // Idempotency keys on content, not on filename.
            assertEquals(a.sha256(), b.sha256());
            assertNotEquals(a.path(), b.path(), "each upload gets its own file");
        }
    }

    @Nested
    @DisplayName("Discarding")
    class Discarding {

        @Test
        void removesTheStagedFile() throws Exception {
            StagedFile s = staging.stage(new ByteArrayInputStream("x".getBytes()), "a.csv");
            assertTrue(Files.exists(s.path()));

            staging.discard(s.path());
            assertFalse(Files.exists(s.path()), "a staged file left behind fills the disk over time");
        }

        @Test
        void isSafeToCallTwice() throws Exception {
            StagedFile s = staging.stage(new ByteArrayInputStream("x".getBytes()), "a.csv");
            staging.discard(s.path());
            assertDoesNotThrow(() -> staging.discard(s.path()));
        }

        /** Defence in depth: discard must never delete outside its own directory. */
        @Test
        void refusesToDeleteOutsideTheStagingDirectory() throws Exception {
            Path outsider = Files.createTempFile("not-ours", ".txt");
            staging.discard(outsider);

            assertTrue(Files.exists(outsider), "discard must not touch files it did not create");
            Files.deleteIfExists(outsider);
        }
    }
}
