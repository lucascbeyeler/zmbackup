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

/**
 * Restricts backup session directories, backed-up mail content, and the metadata database to
 * owner-only access (0700/0600) so that a permissive default umask (e.g. 022) does not leave
 * them world-readable. No-ops on filesystems without POSIX permission support.
 */
final class PosixFileHardening {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private PosixFileHardening() {}

    /** Creates {@code dir} and any missing parents, restricting every directory it creates. */
    static void createDirectories(Path dir) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } else {
            Files.createDirectories(dir);
        }
    }

    /** Creates a new, empty, owner-only file. Fails if {@code file} already exists. */
    static void createFile(Path file) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } else {
            Files.createFile(file);
        }
    }

    /** Restricts an existing file to owner-only access. */
    static void restrictExistingFile(Path file) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        }
    }

    /**
     * Opens {@code file} for writing with the given options, restricting it to owner-only access
     * whether the file is created fresh or already existed.
     */
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
