package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.port.ServerConfigArchiver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigFileArchiverTest {

    @TempDir
    Path tempDir;

    private Path confRoot;

    @BeforeEach
    void setUp() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        confRoot = tempDir.resolve("conf");
        Files.createDirectories(confRoot);
    }

    @Test
    void exportThenRestoreRoundTripsFileContentAndPermissions() throws IOException {
        Path localconfig = confRoot.resolve("localconfig.xml");
        Files.writeString(localconfig, "<localconfig><password>secret</password></localconfig>");
        Files.setPosixFilePermissions(localconfig, PosixFilePermissions.fromString("rw-------"));
        Path nested = confRoot.resolve("nginx").resolve("nginx.conf");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "server {}");
        Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("rw-r--r--"));

        ServerConfigArchiver archiver = new ServerConfigFileArchiver(List.of(confRoot));
        byte[] archive = export(archiver);

        Files.writeString(localconfig, "corrupted");
        Files.delete(nested);

        archiver.restore(new ByteArrayInputStream(archive));

        assertEquals("<localconfig><password>secret</password></localconfig>", Files.readString(localconfig));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(localconfig)));
        assertEquals("server {}", Files.readString(nested));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(nested)));
    }

    @Test
    void exportSkipsConfiguredPathThatDoesNotExist() throws IOException {
        Path missing = tempDir.resolve("does-not-exist");
        ServerConfigArchiver archiver = new ServerConfigFileArchiver(List.of(missing));

        byte[] archive = export(archiver);

        assertTrue(archive.length > 0);
    }

    @Test
    void exportDeduplicatesOverlappingRoots() throws IOException {
        Files.writeString(confRoot.resolve("localconfig.xml"), "content");
        Path nestedRoot = confRoot.resolve("nested");
        Files.createDirectories(nestedRoot);
        Files.writeString(nestedRoot.resolve("keystore"), "keystore-bytes");

        ServerConfigArchiver archiver = new ServerConfigFileArchiver(List.of(confRoot, nestedRoot));
        byte[] archive = export(archiver);

        Files.delete(confRoot.resolve("localconfig.xml"));
        Files.delete(nestedRoot.resolve("keystore"));
        archiver.restore(new ByteArrayInputStream(archive));

        assertEquals("content", Files.readString(confRoot.resolve("localconfig.xml")));
        assertEquals("keystore-bytes", Files.readString(nestedRoot.resolve("keystore")));
    }

    @Test
    void restoreRefusesArchiveEntryOutsideConfiguredPathsAndAppliesNothing() throws IOException {
        Path allowed = confRoot.resolve("localconfig.xml");
        Files.writeString(allowed, "original");
        ServerConfigArchiver archiver = new ServerConfigFileArchiver(List.of(confRoot));

        byte[] maliciousArchive = maliciousArchive(confRoot);

        assertThrows(IOException.class, () -> archiver.restore(new ByteArrayInputStream(maliciousArchive)));
        assertEquals("original", Files.readString(allowed));
        assertFalse(Files.exists(Path.of("/tmp/zmbackup-test-escape-marker")));
    }

    @Test
    void restoreSkipsAFileItCannotWriteButStillRestoresEverythingElse() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Path writable = confRoot.resolve("localconfig.xml");
        Files.writeString(writable, "original-writable");
        Path lockedDir = confRoot.resolve("crontabs");
        Files.createDirectories(lockedDir);
        Path locked = lockedDir.resolve("crontab.logger");
        Files.writeString(locked, "original-locked");
        ServerConfigArchiver archiver = new ServerConfigFileArchiver(List.of(confRoot));
        byte[] archive = export(archiver);

        Files.writeString(writable, "corrupted-writable");
        Files.writeString(locked, "corrupted-locked");
        Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            List<String> skipped = archiver.restore(new ByteArrayInputStream(archive));

            assertEquals("original-writable", Files.readString(writable));
            assertEquals("corrupted-locked", Files.readString(locked));
            assertEquals(1, skipped.size());
            assertTrue(skipped.get(0).endsWith("crontabs/crontab.logger"));
        } finally {
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void restoreRejectsArchiveMissingManifest() throws IOException {
        ServerConfigArchiver archiver = new ServerConfigFileArchiver(List.of(confRoot));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(confRoot.resolve("localconfig.xml").toString().substring(1)));
            zip.write("content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(IOException.class, () -> archiver.restore(new ByteArrayInputStream(buffer.toByteArray())));
    }

    @Test
    void constructorRejectsEmptyRoots() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfigFileArchiver(List.of()));
    }

    @Test
    void constructorRejectsRelativePath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerConfigFileArchiver(List.of(Path.of("relative/path"))));
    }

    private static byte[] export(ServerConfigArchiver archiver) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        archiver.export(buffer);
        return buffer.toByteArray();
    }

    private static byte[] maliciousArchive(Path confRoot) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String validEntryName = confRoot.resolve("localconfig.xml").toString().substring(1);
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("MANIFEST.tsv"));
            zip.write((validEntryName + "\t-\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(validEntryName));
            zip.write("tampered".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("tmp/zmbackup-test-escape-marker"));
            zip.write("escaped".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
