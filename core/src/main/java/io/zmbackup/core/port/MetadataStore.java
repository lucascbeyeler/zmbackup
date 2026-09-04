package io.zmbackup.core.port;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MetadataStore {

    void save(BackupSession session) throws IOException;

    Optional<BackupSession> findSession(String sessionId) throws IOException;

    List<BackupSession> listSessions() throws IOException;

    List<BackupSession> findSessionsCompletedBefore(Instant cutoff) throws IOException;

    void deleteSession(String sessionId) throws IOException;

    int truncate() throws IOException;

    void recordAccountBackup(BackupAccountRecord record) throws IOException;

    List<BackupAccountRecord> findAccountsForSession(String sessionId) throws IOException;

    Optional<Instant> lastSuccessfulBackupTime(String email) throws IOException;

    boolean backedUpSince(String identifier, Instant since) throws IOException;

    default void vacuum() throws IOException {}
}
