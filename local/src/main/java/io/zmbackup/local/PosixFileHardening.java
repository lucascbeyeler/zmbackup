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

final class PosixFileHardening {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private PosixFileHardening() {}

    static void createDirectories(Path dir) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } else {
            Files.createDirectories(dir);
        }
    }

    static void createFile(Path file) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } else {
            Files.createFile(file);
        }
    }

    static void restrictExistingFile(Path file) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        }
    }

    static OutputStream newRestrictedOutputStream(Path file, StandardOpenOption... options) throws IOException {
        if (!POSIX_SUPPORTED) {
            return Files.newOutputStream(file, options);
        }
        SeekableByteChannel channel =
                Files.newByteChannel(file, Set.of(options), PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        return Channels.newOutputStream(channel);
    }
}
