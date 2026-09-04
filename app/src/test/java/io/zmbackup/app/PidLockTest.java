package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PidLockTest {

    @TempDir
    Path tempDir;

    private Process holderProcess;

    @AfterEach
    void tearDown() {
        if (holderProcess != null) {
            holderProcess.destroyForcibly();
        }
    }

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
        }

        try (PidLock second = PidLock.acquire(tempDir)) {
            String content = Files.readString(tempDir.resolve("zmbackup.pid")).strip();
            assertEquals(Long.toString(ProcessHandle.current().pid()), content);
        }
    }

    @Test
    void secondAcquireWhileAnotherProcessHoldsTheLockFailsWithTheHoldingPid() throws Exception {
        holderProcess = startLockHolderProcess(tempDir);
        long holderPid = holderProcess.pid();
        awaitReady(holderProcess);

        PidLock.AlreadyRunningException e =
                assertThrows(PidLock.AlreadyRunningException.class, () -> PidLock.acquire(tempDir));
        assertTrue(e.getMessage().contains(Long.toString(holderPid)));
    }

    private static Process startLockHolderProcess(Path workDir) throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-cp",
                System.getProperty("java.class.path"),
                PidLockHolderMain.class.getName(),
                workDir.toString());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static void awaitReady(Process process) throws IOException, InterruptedException {
        BufferedReader out =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = out.readLine()) != null && !"LOCKED".equals(line)) {
        }
        if (!"LOCKED".equals(line)) {
            throw new IllegalStateException("Lock holder process exited without signaling readiness");
        }
        TimeUnit.MILLISECONDS.sleep(100);
    }
}
