package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageProviderTest {

    @TempDir
    Path workDir;

    private StorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalStorageProvider(workDir);
    }

    @Test
    void writtenContentCanBeReadBack() throws IOException {
        write("session1", "user@example.com", "ldiff", "hello world");

        String content = read("session1", "user@example.com", "ldiff");

        assertEquals("hello world", content);
    }

    @Test
    void openWriteCreatesSessionDirectoryLayout() throws IOException {
        write("session1", "user@example.com", "tgz", "content");

        assertTrue(Files.isDirectory(workDir.resolve("session1")));
        assertTrue(Files.exists(workDir.resolve("session1").resolve("user@example.com.tgz")));
    }

    @Test
    void openWriteRestrictsSessionDirectoryAndFileToOwnerOnlyAccess() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        write("session1", "user@example.com", "tgz", "content");

        assertEquals(
                "rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(workDir.resolve("session1"))));
        assertEquals(
                "rw-------",
                PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(workDir.resolve("session1").resolve("user@example.com.tgz"))));
    }

    @Test
    void openWriteTruncatesExistingContent() throws IOException {
        write("session1", "user@example.com", "ldiff", "first version, much longer than the second");
        write("session1", "user@example.com", "ldiff", "second");

        assertEquals("second", read("session1", "user@example.com", "ldiff"));
    }

    @Test
    void existsIsFalseUntilContentIsWritten() throws IOException {
        assertFalse(provider.exists("session1", "user@example.com", "ldiff"));

        write("session1", "user@example.com", "ldiff", "content");

        assertTrue(provider.exists("session1", "user@example.com", "ldiff"));
    }

    @Test
    void sizeOfAccountSumsOnlyThatAccountsFiles() throws IOException {
        write("session1", "user@example.com", "ldiff", "a".repeat(1024));
        write("session1", "user@example.com", "tgz", "b".repeat(1024));
        write("session1", "other@example.com", "ldiff", "c".repeat(4096));

        assertEquals("2K", provider.sizeOfAccount("session1", "user@example.com"));
    }

    @Test
    void sizeOfAccountIsZeroWhenSessionDoesNotExist() throws IOException {
        assertEquals("0B", provider.sizeOfAccount("missing-session", "user@example.com"));
    }

    @Test
    void sizeOfSessionSumsAllAccountsFiles() throws IOException {
        write("session1", "user@example.com", "ldiff", "a".repeat(1024));
        write("session1", "other@example.com", "tgz", "b".repeat(1024));

        assertEquals("2K", provider.sizeOfSession("session1"));
    }

    @Test
    void sizeOfSessionIsZeroWhenSessionDoesNotExist() throws IOException {
        assertEquals("0B", provider.sizeOfSession("missing-session"));
    }

    @Test
    void deleteSessionRemovesAllContentAndTheDirectory() throws IOException {
        write("session1", "user@example.com", "ldiff", "content");
        write("session1", "other@example.com", "tgz", "content");

        provider.deleteSession("session1");

        assertFalse(Files.exists(workDir.resolve("session1")));
    }

    @Test
    void deleteSessionOnMissingSessionIsANoop() {
        assertDoesNotThrow(() -> provider.deleteSession("missing-session"));
    }

    private void write(String sessionId, String account, String suffix, String content) throws IOException {
        try (OutputStream out = provider.openWrite(sessionId, account, suffix)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String read(String sessionId, String account, String suffix) throws IOException {
        try (InputStream in = provider.openRead(sessionId, account, suffix)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
