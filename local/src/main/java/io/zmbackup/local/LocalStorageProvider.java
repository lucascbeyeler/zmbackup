package io.zmbackup.local;

import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;

/**
 * Filesystem-backed {@link StorageProvider} storing content under
 * {@code {workDir}/{sessionId}/{account}.{suffix}}, the same layout the bash tool uses under its
 * {@code WORKDIR}.
 */
public class LocalStorageProvider implements StorageProvider {

    private final Path workDir;

    public LocalStorageProvider(Path workDir) {
        this.workDir = workDir;
    }

    @Override
    public OutputStream openWrite(String sessionId, String account, String suffix) throws IOException {
        Path file = accountFile(sessionId, account, suffix);
        PosixFileHardening.createDirectories(file.getParent());
        return PosixFileHardening.newRestrictedOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override
    public InputStream openRead(String sessionId, String account, String suffix) throws IOException {
        return Files.newInputStream(accountFile(sessionId, account, suffix));
    }

    @Override
    public boolean exists(String sessionId, String account, String suffix) {
        return Files.exists(accountFile(sessionId, account, suffix));
    }

    @Override
    public String sizeOfAccount(String sessionId, String account) throws IOException {
        Path sessionDir = sessionDir(sessionId);
        long totalBytes = 0;
        if (Files.isDirectory(sessionDir)) {
            String prefix = account + ".";
            try (DirectoryStream<Path> entries =
                    Files.newDirectoryStream(sessionDir, path -> path.getFileName().toString().startsWith(prefix))) {
                for (Path entry : entries) {
                    totalBytes += Files.size(entry);
                }
            }
        }
        return HumanReadableSize.format(totalBytes);
    }

    @Override
    public String sizeOfSession(String sessionId) throws IOException {
        Path sessionDir = sessionDir(sessionId);
        long[] totalBytes = {0};
        if (Files.isDirectory(sessionDir)) {
            Files.walkFileTree(sessionDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalBytes[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return HumanReadableSize.format(totalBytes[0]);
    }

    @Override
    public void deleteSession(String sessionId) throws IOException {
        Path sessionDir = sessionDir(sessionId);
        if (!Files.exists(sessionDir)) {
            return;
        }
        Files.walkFileTree(sessionDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public int deleteEmptyFiles() throws IOException {
        if (!Files.isDirectory(workDir)) {
            return 0;
        }
        int[] removed = {0};
        Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && attrs.size() == 0) {
                    Files.delete(file);
                    removed[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return removed[0];
    }

    private Path sessionDir(String sessionId) {
        return workDir.resolve(sessionId);
    }

    private Path accountFile(String sessionId, String account, String suffix) {
        return sessionDir(sessionId).resolve(account + "." + suffix);
    }
}
