package io.zmbackup.app.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Local backup behavior, mirroring the {@code WORKDIR}/{@code LOGFILE}/{@code MAX_PARALLEL_PROCESS}/
 * {@code ROTATE_TIME}/{@code LOCK_BACKUP}/{@code ZMBACKUP_BLOCKEDLIST} fields of the bash tool's
 * {@code zmbackup.conf}.
 *
 * @param workDir            directory backups and session metadata are stored under
 * @param logFile            path zmbackup writes its logs to
 * @param blockedListFile    path to the file listing accounts that should never be backed up
 * @param maxParallelProcesses how many accounts to back up concurrently
 * @param rotateDays         how many days of backups to keep before housekeeping deletes them
 * @param lockBackup         whether to allow only one backup session per type per day
 * @param emailNotify        e-mail notification settings
 */
public record BackupConfig(
        Path workDir,
        Path logFile,
        Path blockedListFile,
        int maxParallelProcesses,
        int rotateDays,
        boolean lockBackup,
        EmailNotifyConfig emailNotify) {

    /**
     * The highest {@code maxParallelProcesses} accepted. Each unit backs a thread in {@link
     * io.zmbackup.core.service.Parallel}'s pool, each of which opens its own concurrent
     * connection to the Zimbra LDAP/REST server - a mistyped or malicious config value (e.g. a
     * stray extra digit) should fail fast here rather than trying to spin up an unreasonably large
     * thread pool and connection burst against that server.
     */
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
