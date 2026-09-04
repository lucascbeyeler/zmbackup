package io.zmbackup.app;

import java.nio.file.Path;

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
