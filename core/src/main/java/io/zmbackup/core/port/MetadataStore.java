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

    /**
     * Deletes every session and account record, emptying the store completely. Unlike {@link
     * #deleteSession}, this cannot be scoped or undone - callers are responsible for confirming
     * intent first (see {@code zmbackup truncate}).
     *
     * @return the number of sessions removed
     */
    int truncate() throws IOException;

    /** Records that the account in {@code record} was backed up as part of its session. */
    void recordAccountBackup(BackupAccountRecord record) throws IOException;

    /** All account records backed up as part of {@code sessionId}, for restore. */
    List<BackupAccountRecord> findAccountsForSession(String sessionId) throws IOException;

    /**
     * When {@code email} was last successfully backed up — the latest
     * {@link BackupAccountRecord#completedAt()} across all of its sessions — or empty if it was
     * never backed up. Used to compute the "after" cutoff for incremental mailbox backups.
     */
    Optional<Instant> lastSuccessfulBackupTime(String email) throws IOException;

    /**
     * Whether {@code identifier} has any account-level backup record ({@link
     * BackupAccountRecord#completedAt()}) after {@code since}, across every session type and
     * regardless of the owning session's overall status. Mirrors the bash tool's {@code
     * ldap_filter} LOCK_BACKUP dedup check ({@code conclusion_date > YESTERDAY}): used by {@code
     * BackupService} to skip discovery-based backups (not explicit {@code --account} lists) for
     * objects already backed up within roughly the last day.
     */
    boolean backedUpSince(String identifier, Instant since) throws IOException;

    /**
     * Reclaims disk space freed by earlier deletions, mirroring the bash tool's {@code sqlite3
     * ... VACUUM} run at the end of {@code delete_old}/{@code leeroy_jenkins}. A no-op by default
     * - purely a maintenance operation with no effect on stored data, so backends that don't need
     * it (or in-memory test fakes) don't have to implement it.
     */
    default void vacuum() throws IOException {}
}
