package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@code posixSupported == false} fallback branches, which never run on the POSIX
 * filesystems CI executes on, by forcing the flag via the package-private test hook.
 */
class PosixFileHardeningFallbackTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void forcePosixUnsupported() {
        PosixFileHardening.setPosixSupportedForTesting(false);
    }

    @AfterEach
    void restorePosixDetection() {
        PosixFileHardening.restorePosixSupportedForTesting();
    }

    @Test
    void createDirectoriesFallsBackToPlainCreationWhenPosixIsUnsupported() throws IOException {
        Path nested = tempDir.resolve("a").resolve("b");

        PosixFileHardening.createDirectories(nested);

        assertTrue(Files.isDirectory(nested));
    }

    @Test
    void createFileFallsBackToPlainCreationWhenPosixIsUnsupported() throws IOException {
        Path file = tempDir.resolve("session.sqlite3");

        PosixFileHardening.createFile(file);

        assertTrue(Files.exists(file));
        assertEquals(0, Files.size(file));
    }

    @Test
    void createTempFileFallsBackToPlainCreationWhenPosixIsUnsupported() throws IOException {
        Path file = PosixFileHardening.createTempFile("zmbackup-test-", ".tmp");

        try {
            assertTrue(Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void restrictExistingFileDoesNothingWhenPosixIsUnsupported() throws IOException {
        Path file = Files.createFile(tempDir.resolve("preexisting.db"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        PosixFileHardening.restrictExistingFile(file);

        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void newRestrictedOutputStreamFallsBackToPlainOutputStreamWhenPosixIsUnsupported() throws IOException {
        Path file = tempDir.resolve("alice@example.com.tgz");

        try (OutputStream out = PosixFileHardening.newRestrictedOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            out.write("content".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("content", Files.readString(file));
    }
}
