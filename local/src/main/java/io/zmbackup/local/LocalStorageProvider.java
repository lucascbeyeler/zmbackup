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
import java.util.Objects;

public class LocalStorageProvider implements StorageProvider {

    private final Path workDir;

    public LocalStorageProvider(Path workDir) {
        this.workDir = workDir.normalize();
    }

    @Override
    public OutputStream openWrite(String sessionId, String account, String suffix) throws IOException {
        Path file = accountFile(sessionId, account, suffix);
        Path parent = Objects.requireNonNull(file.getParent(), "accountFile always resolves within a parent directory");
        PosixFileHardening.createDirectories(parent);
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
            try (DirectoryStream<Path> entries =
                    Files.newDirectoryStream(sessionDir, path -> isAccountFile(path, account))) {
                for (Path entry : entries) {
                    totalBytes += Files.size(entry);
                }
            }
        }
        return HumanReadableSize.format(totalBytes);
    }

    private static boolean isAccountFile(Path path, String account) {
        String prefix = account + ".";
        String fileName =
                Objects.requireNonNull(path.getFileName(), "directory stream entries always have a file name")
                        .toString();
        return fileName.startsWith(prefix) && fileName.indexOf('.', prefix.length()) < 0;
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
        Path dir = workDir.resolve(sessionId).normalize();
        if (!workDir.equals(dir.getParent())) {
            throw new IllegalArgumentException("Invalid session identifier: " + sessionId);
        }
        return dir;
    }

    private Path accountFile(String sessionId, String account, String suffix) {
        Path sessionDir = sessionDir(sessionId);
        Path file = sessionDir.resolve(account + "." + suffix).normalize();
        if (!sessionDir.equals(file.getParent())) {
            throw new IllegalArgumentException("Invalid backup identifier: " + account);
        }
        return file;
    }
}
