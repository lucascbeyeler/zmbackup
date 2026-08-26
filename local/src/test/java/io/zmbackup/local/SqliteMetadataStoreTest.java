package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqliteMetadataStoreTest {

    private static final String IN_MEMORY_URL = "jdbc:sqlite:file::memory:?cache=shared";

    /**
     * A shared-cache in-memory SQLite database is destroyed once its last connection closes.
     * {@link SqliteMetadataStore} opens and closes a fresh connection per call, so this anchor
     * connection is held open for the test's duration to keep the database alive between calls.
     */
    private Connection anchor;

    private MetadataStore store;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        anchor = DriverManager.getConnection(IN_MEMORY_URL);
        store = new SqliteMetadataStore(Path.of("file::memory:?cache=shared"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        anchor.close();
    }

    @Test
    void savedSessionCanBeFoundById() throws IOException {
        BackupSession session = session("full-1", SessionStatus.FINISHED, "10M");

        store.save(session);

        assertEquals(Optional.of(session), store.findSession("full-1"));
    }

    @Test
    void findSessionIsEmptyWhenSessionDoesNotExist() throws IOException {
        assertEquals(Optional.empty(), store.findSession("missing"));
    }

    @Test
    void saveReplacesExistingSessionWithSameId() throws IOException {
        store.save(session("full-1", SessionStatus.IN_PROGRESS, null));

        BackupSession finished = session("full-1", SessionStatus.FINISHED, "10M");
        store.save(finished);

        assertEquals(Optional.of(finished), store.findSession("full-1"));
    }

    @Test
    void listSessionsReturnsAllSavedSessions() throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));
        store.save(session("full-2", SessionStatus.IN_PROGRESS, null));

        List<BackupSession> sessions = store.listSessions();

        assertEquals(2, sessions.size());
    }

    @Test
    void listSessionsIsEmptyWhenNoneSaved() throws IOException {
        assertEquals(List.of(), store.listSessions());
    }

    @Test
    void findSessionsCompletedBeforeReturnsOnlyOlderCompletedSessions() throws IOException {
        Instant now = Instant.now();
        store.save(new BackupSession(
                "old", BackupType.FULL, SessionStatus.FINISHED, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), "1M"));
        store.save(new BackupSession(
                "recent", BackupType.FULL, SessionStatus.FINISHED, now, now, "1M"));
        store.save(session("in-progress", SessionStatus.IN_PROGRESS, null));

        List<BackupSession> before = store.findSessionsCompletedBefore(now.minus(1, ChronoUnit.HOURS));

        assertEquals(1, before.size());
        assertEquals("old", before.get(0).sessionId());
    }

    @Test
    void deleteSessionRemovesSessionAndItsAccountRecords() throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com"));

        store.deleteSession("full-1");

        assertEquals(Optional.empty(), store.findSession("full-1"));
        assertEquals(List.of(), store.findAccountsForSession("full-1"));
    }

    @Test
    void deleteSessionOnMissingSessionIsANoop() {
        assertDoesNotThrow(() -> store.deleteSession("missing"));
    }

    @Test
    void recordedAccountBackupCanBeFoundBySession() throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));
        BackupAccountRecord record = accountRecord("full-1", "user@example.com");

        store.recordAccountBackup(record);

        List<BackupAccountRecord> accounts = store.findAccountsForSession("full-1");
        assertEquals(1, accounts.size());
        assertEquals(record.sessionId(), accounts.get(0).sessionId());
        assertEquals(record.email(), accounts.get(0).email());
        assertEquals(record.size(), accounts.get(0).size());
        assertEquals(record.startedAt(), accounts.get(0).startedAt());
        assertEquals(record.completedAt(), accounts.get(0).completedAt());
    }

    @Test
    void findAccountsForSessionIsEmptyWhenSessionHasNoAccounts() throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));

        assertEquals(List.of(), store.findAccountsForSession("full-1"));
    }

    @Test
    void lastSuccessfulBackupTimeIsNotYetImplemented() {
        assertThrows(UnsupportedOperationException.class, () -> store.lastSuccessfulBackupTime("user@example.com"));
    }

    private static BackupSession session(String sessionId, SessionStatus status, String size) {
        Instant now = Instant.now();
        Instant completedAt = status == SessionStatus.IN_PROGRESS ? null : now;
        return new BackupSession(sessionId, BackupType.FULL, status, now, completedAt, size);
    }

    private static BackupAccountRecord accountRecord(String sessionId, String email) {
        Instant now = Instant.now();
        return new BackupAccountRecord(null, sessionId, email, "1M", now, now);
    }
}
