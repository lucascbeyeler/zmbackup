package io.zmbackup.app.config;

import java.nio.file.Path;
import java.util.Objects;

public record BackupConfig(
        Path workDir,
        Path logFile,
        Path blockedListFile,
        int maxParallelProcesses,
        int rotateDays,
        boolean lockBackup,
        EmailNotifyConfig emailNotify) {

    private static final int MAX_PARALLEL_PROCESSES = 256;

    public BackupConfig {
        Objects.requireNonNull(workDir, "workDir must not be null");
        Objects.requireNonNull(logFile, "logFile must not be null");
        Objects.requireNonNull(blockedListFile, "blockedListFile must not be null");
        Objects.requireNonNull(emailNotify, "emailNotify must not be null");
        if (maxParallelProcesses < 1) {
            throw new IllegalArgumentException("maxParallelProcesses must be at least 1");
        }
        if (maxParallelProcesses > MAX_PARALLEL_PROCESSES) {
            throw new IllegalArgumentException("maxParallelProcesses must be at most " + MAX_PARALLEL_PROCESSES);
        }
        if (rotateDays < 0) {
            throw new IllegalArgumentException("rotateDays must not be negative");
        }
    }
}
