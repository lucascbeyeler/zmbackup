package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PidLockTest {

    @TempDir
    Path tempDir;

    @Test
    void acquireWritesTheCurrentProcessPidToTheLockFile() throws Exception {
        try (PidLock lock = PidLock.acquire(tempDir)) {
            String content = Files.readString(tempDir.resolve("zmbackup.pid")).strip();
            assertEquals(Long.toString(ProcessHandle.current().pid()), content);
        }
    }

    @Test
    void secondAcquireWhileTheFirstIsHeldFailsWithTheHoldingPid() throws Exception {
        try (PidLock first = PidLock.acquire(tempDir)) {
            PidLock.AlreadyRunningException e =
                    assertThrows(PidLock.AlreadyRunningException.class, () -> PidLock.acquire(tempDir));
            assertTrue(e.getMessage().contains(Long.toString(ProcessHandle.current().pid())));
        }
    }

    @Test
    void acquireSucceedsAgainAfterTheFirstLockIsClosed() throws Exception {
        try (PidLock first = PidLock.acquire(tempDir)) {
            // held
        }

        try (PidLock second = PidLock.acquire(tempDir)) {
            String content = Files.readString(tempDir.resolve("zmbackup.pid")).strip();
            assertEquals(Long.toString(ProcessHandle.current().pid()), content);
        }
    }
}
