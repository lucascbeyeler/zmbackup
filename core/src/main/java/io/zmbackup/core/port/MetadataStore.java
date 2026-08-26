package io.zmbackup.core.port;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link BackupSession} and {@link BackupAccountRecord} data, mirroring the
 * {@code backup_session} and {@code backup_account} tables in the bash tool's
 * {@code sessions.sqlite3}.
 */
public interface MetadataStore {

    /** Creates or replaces the stored session with the same {@link BackupSession#sessionId()}. */
    void save(BackupSession session) throws IOException;

    /** The session with the given ID, or empty if none exists. */
    Optional<BackupSession> findSession(String sessionId) throws IOException;

    /** All stored sessions. */
    List<BackupSession> listSessions() throws IOException;

    /** Sessions whose {@link BackupSession#completedAt()} is before {@code cutoff}, for housekeeping. */
    List<BackupSession> findSessionsCompletedBefore(Instant cutoff) throws IOException;

    /** Removes a session and its associated account records. */
    void deleteSession(String sessionId) throws IOException;

    /** Records that the account in {@code record} was backed up as part of its session. */
    void recordAccountBackup(BackupAccountRecord record) throws IOException;

    /** All account records backed up as part of {@code sessionId}, for restore. */
    List<BackupAccountRecord> findAccountsForSession(String sessionId) throws IOException;

    /**
     * When {@code email} was last successfully backed up — the latest
     * {@link BackupAccountRecord#completedAt()} across all of its sessions — or empty if it was
     * never backed up. Used both to compute the "after" cutoff for incremental mailbox backups
     * and to skip accounts already backed up today.
     */
    Optional<Instant> lastSuccessfulBackupTime(String email) throws IOException;
}
