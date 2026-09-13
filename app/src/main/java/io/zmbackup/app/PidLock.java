package io.zmbackup.app;

import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import io.zmbackup.local.PosixFileHardening;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class PidLock implements RunLock {

    private static final String LOCK_FILENAME = "zmbackup.pid";

    private final FileChannel channel;
    private final FileLock lock;

    private PidLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static PidLock acquire(Path workDir) throws IOException {
        return acquire(workDir, null);
    }

    public static PidLock acquire(Path workDir, String resource) throws IOException {
        PosixFileHardening.createDirectories(workDir);
        Path lockFile = workDir.resolve(lockFileName(resource));
        FileChannel channel = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                String heldBy = readPid(channel);
                String suffix = resource == null ? "" : " (a conflicting \"" + resource + "\" operation)";
                throw new AlreadyRunningException("Another zmbackup process (pid " + heldBy
                        + ") is already running" + suffix + " against " + workDir);
            }
            channel.truncate(0);
            channel.write(
                    ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)));
            return new PidLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static String lockFileName(String resource) {
        return resource == null ? LOCK_FILENAME : "zmbackup-" + resource + ".pid";
    }

    public static RunLock acquireForResources(Path workDir, List<String> resources) throws IOException {
        List<String> sorted = resources.stream().distinct().sorted().toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        List<PidLock> acquired = new ArrayList<>(sorted.size());
        try {
            for (String resource : sorted) {
                acquired.add(acquire(workDir, resource));
            }
        } catch (IOException | RuntimeException e) {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                closeQuietly(acquired.get(i));
            }
            throw e;
        }
        return new CompositeLock(acquired);
    }

    private static void closeQuietly(PidLock lock) {
        try {
            lock.close();
        } catch (IOException ignored) {
        }
    }

    private static String readPid(FileChannel channel) throws IOException {
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(channel.size(), 64));
        channel.read(buffer);
        String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? "unknown" : text;
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    public static final class AlreadyRunningException extends LockContentionException {
        private AlreadyRunningException(String message) {
            super(message);
        }
    }

    private static final class CompositeLock implements RunLock {
        private final List<PidLock> locks;

        private CompositeLock(List<PidLock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (int i = locks.size() - 1; i >= 0; i--) {
                try {
                    locks.get(i).close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    } else {
                        first.addSuppressed(e);
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
