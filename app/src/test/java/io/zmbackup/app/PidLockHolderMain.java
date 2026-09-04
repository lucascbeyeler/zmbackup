package io.zmbackup.app;

import java.nio.file.Path;

/**
 * Test helper run as a separate JVM process by {@link PidLockTest} to exercise the cross-process
 * branch of {@link PidLock#acquire}: a {@code FileLock} held by a different process, rather than
 * the same-JVM {@code OverlappingFileLockException} path.
 *
 * <p>Acquires the lock in the given work directory, signals readiness by printing {@code LOCKED}
 * to stdout, then blocks until stdin is closed (the test destroys this process) so the lock stays
 * held for the duration of the assertion.
 */
public final class PidLockHolderMain {

    private PidLockHolderMain() {}

    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(args[0]);
        try (PidLock lock = PidLock.acquire(workDir)) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
