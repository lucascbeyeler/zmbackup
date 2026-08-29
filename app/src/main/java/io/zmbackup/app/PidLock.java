package io.zmbackup.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An OS-level advisory lock on a {@code zmbackup.pid} file inside the backup work directory,
 * preventing two zmbackup processes from mutating the same backup store at once.
 *
 * <p>This holds a {@link FileLock} for the lifetime of the process rather than writing a bare PID
 * and grepping {@code ps} for it (the bash tool's {@code checkpid}, which was never actually
 * wired up): the OS releases a {@link FileLock} automatically if the process dies, so there is no
 * stale-lock case to detect or clean up.
 */
public final class PidLock implements AutoCloseable {

    private static final String LOCK_FILENAME = "zmbackup.pid";

    private final FileChannel channel;
    private final FileLock lock;

    private PidLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the lock inside {@code workDir}, writing this process's PID into the lock file.
     *
     * @throws AlreadyRunningException if another zmbackup process currently holds the lock
     */
    public static PidLock acquire(Path workDir) throws IOException {
        Path lockFile = workDir.resolve(LOCK_FILENAME);
        FileChannel channel = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            String heldBy = readPid(channel);
            channel.close();
            throw new AlreadyRunningException(
                    "Another zmbackup process (pid " + heldBy + ") is already running against " + workDir);
        }
        channel.truncate(0);
        channel.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)));
        return new PidLock(channel, lock);
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

    /** Thrown by {@link #acquire} when another zmbackup process already holds the lock. */
    public static final class AlreadyRunningException extends IOException {
        private AlreadyRunningException(String message) {
            super(message);
        }
    }
}
