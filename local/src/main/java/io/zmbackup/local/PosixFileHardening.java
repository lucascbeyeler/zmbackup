package io.zmbackup.local;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class PosixFileHardening {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private static boolean posixSupported = detectPosixSupport();

    private PosixFileHardening() {}

    private static boolean detectPosixSupport() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Test-only hook to exercise the non-POSIX fallback branches on a POSIX filesystem. */
    static void setPosixSupportedForTesting(boolean value) {
        posixSupported = value;
    }

    static void restorePosixSupportedForTesting() {
        posixSupported = detectPosixSupport();
    }

    public static void createDirectories(Path dir) throws IOException {
        if (posixSupported) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } else {
            Files.createDirectories(dir);
        }
    }

    static void createFile(Path file) throws IOException {
        if (posixSupported) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } else {
            Files.createFile(file);
        }
    }

    static Path createTempFile(String prefix, String suffix) throws IOException {
        if (posixSupported) {
            return Files.createTempFile(prefix, suffix, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        return Files.createTempFile(prefix, suffix);
    }

    static void restrictExistingFile(Path file) throws IOException {
        if (posixSupported) {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        }
    }

    static OutputStream newRestrictedOutputStream(Path file, StandardOpenOption... options) throws IOException {
        if (!posixSupported) {
            return Files.newOutputStream(file, options);
        }
        SeekableByteChannel channel =
                Files.newByteChannel(file, Set.of(options), PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        return Channels.newOutputStream(channel);
    }
}
