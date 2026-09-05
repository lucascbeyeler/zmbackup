package io.zmbackup.app;

import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class PidLock implements RunLock {

    private static final String LOCK_FILENAME = "zmbackup.pid";

    private final FileChannel channel;
    private final FileLock lock;

    private PidLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static PidLock acquire(Path workDir) throws IOException {
        Path lockFile = workDir.resolve(LOCK_FILENAME);
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
                throw new AlreadyRunningException(
                        "Another zmbackup process (pid " + heldBy + ") is already running against " + workDir);
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
}
