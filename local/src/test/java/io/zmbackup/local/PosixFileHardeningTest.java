package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosixFileHardeningTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void assumePosix() {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    @Test
    void createDirectoriesRestrictsEveryCreatedDirectoryToOwnerOnlyAccess() throws IOException {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");

        PosixFileHardening.createDirectories(nested);

        assertEquals("rwx------", permissionsOf(nested));
        assertEquals("rwx------", permissionsOf(tempDir.resolve("a").resolve("b")));
        assertEquals("rwx------", permissionsOf(tempDir.resolve("a")));
    }

    @Test
    void createDirectoriesIsANoopWhenDirectoryAlreadyExists() throws IOException {
        Path dir = tempDir.resolve("existing");
        PosixFileHardening.createDirectories(dir);

        PosixFileHardening.createDirectories(dir);

        assertEquals("rwx------", permissionsOf(dir));
    }

    @Test
    void createFileCreatesAnEmptyOwnerOnlyFile() throws IOException {
        Path file = tempDir.resolve("session.sqlite3");

        PosixFileHardening.createFile(file);

        assertTrue(Files.exists(file));
        assertEquals(0, Files.size(file));
        assertEquals("rw-------", permissionsOf(file));
    }

    @Test
    void createFileFailsWhenFileAlreadyExists() throws IOException {
        Path file = tempDir.resolve("session.sqlite3");
        PosixFileHardening.createFile(file);

        assertThrows(FileAlreadyExistsException.class, () -> PosixFileHardening.createFile(file));
    }

    @Test
    void restrictExistingFileNarrowsPermissionsOnAFileCreatedWithoutHardening() throws IOException {
        Path file = Files.createFile(tempDir.resolve("preexisting.db"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        PosixFileHardening.restrictExistingFile(file);

        assertEquals("rw-------", permissionsOf(file));
    }

    @Test
    void newRestrictedOutputStreamWritesContentAndRestrictsANewFile() throws IOException {
        Path file = tempDir.resolve("alice@example.com.tgz");

        try (OutputStream out = PosixFileHardening.newRestrictedOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            out.write("content".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("content", Files.readString(file));
        assertEquals("rw-------", permissionsOf(file));
    }

    @Test
    void newRestrictedOutputStreamRestrictsAFileThatAlreadyExistedWithLooserPermissions() throws IOException {
        Path file = Files.createFile(tempDir.resolve("alice@example.com.tgz"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        try (OutputStream out = PosixFileHardening.newRestrictedOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write("new content".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("new content", Files.readString(file));
        assertEquals("rw-------", permissionsOf(file));
    }

    private static String permissionsOf(Path path) throws IOException {
        Set<java.nio.file.attribute.PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        return PosixFilePermissions.toString(permissions);
    }
}
