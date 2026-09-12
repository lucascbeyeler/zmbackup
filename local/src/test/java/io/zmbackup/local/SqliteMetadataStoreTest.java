package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMetadataStoreTest {

    private SqliteMetadataStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new SqliteMetadataStore(Path.of("file::memory:?cache=shared"));
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
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
    void truncateRemovesEverySessionAndAccountRecordAndReturnsCount() throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));
        store.save(session("full-2", SessionStatus.IN_PROGRESS, null));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com"));

        int removed = store.truncate();

        assertEquals(2, removed);
        assertEquals(List.of(), store.listSessions());
        assertEquals(List.of(), store.findAccountsForSession("full-1"));
    }

    @Test
    void truncateOnEmptyStoreReturnsZero() throws IOException {
        assertEquals(0, store.truncate());
    }

    @Test
    void vacuumLeavesStoredDataIntact() throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com"));

        store.vacuum();

        assertEquals(1, store.listSessions().size());
        assertEquals(1, store.findAccountsForSession("full-1").size());
    }

    @Test
    void vacuumOnEmptyStoreDoesNotThrow() {
        assertDoesNotThrow(() -> store.vacuum());
    }

    @Test
    void supportsSelfBackupReturnsTrue() {
        assertTrue(store.supportsSelfBackup());
    }

    @Test
    void exportSelfBackupProducesAReadableSnapshotWithStoredDataIntact(@TempDir Path tempDir) throws IOException {
        store.save(session("full-1", SessionStatus.FINISHED, "10M"));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com"));

        Path exported = tempDir.resolve("self-export.sqlite3");
        try (var destination = Files.newOutputStream(exported)) {
            store.exportSelfBackup(destination);
        }

        try (SqliteMetadataStore snapshot = new SqliteMetadataStore(exported)) {
            assertEquals(1, snapshot.listSessions().size());
            assertEquals("full-1", snapshot.listSessions().get(0).sessionId());
            assertEquals(1, snapshot.findAccountsForSession("full-1").size());
        }
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
    void lastSuccessfulBackupTimeIsEmptyWhenAccountNeverBackedUp() throws IOException {
        assertEquals(Optional.empty(), store.lastSuccessfulBackupTime("user@example.com"));
    }

    @Test
    void lastSuccessfulBackupTimeReturnsMostRecentFinishedMailboxSession() throws IOException {
        Instant now = Instant.now();
        Instant older = now.minus(2, ChronoUnit.DAYS);
        Instant newer = now.minus(1, ChronoUnit.DAYS);
        store.save(session("full-1", BackupType.FULL, SessionStatus.FINISHED, older));
        store.save(session("inc-1", BackupType.INCREMENTAL, SessionStatus.FINISHED, newer));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com", older));
        store.recordAccountBackup(accountRecord("inc-1", "user@example.com", newer));

        assertEquals(Optional.of(newer), store.lastSuccessfulBackupTime("user@example.com"));
    }

    @Test
    void lastSuccessfulBackupTimeIgnoresUnfinishedSessions() throws IOException {
        Instant now = Instant.now();
        store.save(session("full-1", BackupType.FULL, SessionStatus.IN_PROGRESS, null));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com", now));

        assertEquals(Optional.empty(), store.lastSuccessfulBackupTime("user@example.com"));
    }

    @Test
    void lastSuccessfulBackupTimeCountsAccountRecordedUnderFailedSession() throws IOException {
        Instant now = Instant.now();
        store.save(session("full-1", BackupType.FULL, SessionStatus.FAILED, now));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com", now));

        assertEquals(Optional.of(now), store.lastSuccessfulBackupTime("user@example.com"));
    }

    @Test
    void lastSuccessfulBackupTimeIgnoresNonMailboxSessionTypes() throws IOException {
        Instant now = Instant.now();
        store.save(session("ldap-1", BackupType.LDAP, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("ldap-1", "user@example.com", now));

        assertEquals(Optional.empty(), store.lastSuccessfulBackupTime("user@example.com"));
    }

    @Test
    void lastSuccessfulBackupTimeIgnoresOtherAccounts() throws IOException {
        Instant now = Instant.now();
        store.save(session("full-1", BackupType.FULL, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("full-1", "other@example.com", now));

        assertEquals(Optional.empty(), store.lastSuccessfulBackupTime("user@example.com"));
    }

    @Test
    void backedUpSinceIsFalseWhenAccountNeverBackedUp() throws IOException {
        assertEquals(
                false,
                store.backedUpSince("user@example.com", BackupType.LDAP, Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceIsTrueWhenAccountBackedUpAfterCutoff() throws IOException {
        Instant now = Instant.now();
        store.save(session("ldap-1", BackupType.LDAP, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("ldap-1", "user@example.com", now));

        assertEquals(
                true, store.backedUpSince("user@example.com", BackupType.LDAP, now.minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceIsFalseWhenLastBackupIsBeforeCutoff() throws IOException {
        Instant old = Instant.now().minus(30, ChronoUnit.HOURS);
        store.save(session("ldap-1", BackupType.LDAP, SessionStatus.FINISHED, old));
        store.recordAccountBackup(accountRecord("ldap-1", "user@example.com", old));

        assertEquals(
                false,
                store.backedUpSince("user@example.com", BackupType.LDAP, Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceIgnoresTheOwningSessionsOverallStatus() throws IOException {
        Instant now = Instant.now();
        store.save(session("full-1", BackupType.FULL, SessionStatus.FAILED, now));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com", now));

        assertEquals(
                true, store.backedUpSince("user@example.com", BackupType.FULL, now.minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceIgnoresOtherAccounts() throws IOException {
        Instant now = Instant.now();
        store.save(session("ldap-1", BackupType.LDAP, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("ldap-1", "other@example.com", now));

        assertEquals(
                false, store.backedUpSince("user@example.com", BackupType.LDAP, now.minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceIsFalseForANonOverlappingBackupType() throws IOException {
        Instant now = Instant.now();
        store.save(session("ldap-1", BackupType.LDAP, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("ldap-1", "user@example.com", now));

        assertEquals(
                false, store.backedUpSince("user@example.com", BackupType.MAILBOX, now.minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceIsTrueForMailboxWhenAFullBackupAlreadyCoveredItToday() throws IOException {
        Instant now = Instant.now();
        store.save(session("full-1", BackupType.FULL, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("full-1", "user@example.com", now));

        assertEquals(
                true, store.backedUpSince("user@example.com", BackupType.MAILBOX, now.minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void backedUpSinceWorksForServerConfigWhichIncludesNeitherLdapNorMailbox() throws IOException {
        assertEquals(
                false,
                store.backedUpSince(
                        "serverconfig", BackupType.SERVER_CONFIG, Instant.now().minus(24, ChronoUnit.HOURS)));

        Instant now = Instant.now();
        store.save(session("serverconfig-1", BackupType.SERVER_CONFIG, SessionStatus.FINISHED, now));
        store.recordAccountBackup(accountRecord("serverconfig-1", "serverconfig", now));

        assertEquals(
                true,
                store.backedUpSince("serverconfig", BackupType.SERVER_CONFIG, now.minus(24, ChronoUnit.HOURS)));
    }

    @Test
    void migrateLegacyRowsRewritesBashToolHumanReadableTypeAndSqliteDatetimeTimestamps(@TempDir Path workDir)
            throws IOException, SQLException {
        Path databaseFile = workDir.resolve("sessions.sqlite3");
        try (SqliteMetadataStore fresh = new SqliteMetadataStore(databaseFile)) {
            // just to create the schema
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "insert into backup_session(sessionID, initial_date, conclusion_date, size, type, status) "
                            + "values ('full-20260101120000', '2026-01-01 12:00:00', '2026-01-01 12:05:00', "
                            + "'10M', 'Full Account', 'FINISHED')");
            statement.execute(
                    "insert into backup_account(sessionID, account_size, email, initial_date, conclusion_date) "
                            + "values ('full-20260101120000', '10M', 'user@example.com', '2026-01-01 12:00:00', "
                            + "'2026-01-01 12:05:00')");
        }

        try (SqliteMetadataStore store = new SqliteMetadataStore(databaseFile)) {
            int converted = store.migrateLegacyRows();

            assertEquals(2, converted);
            BackupSession session = store.findSession("full-20260101120000").orElseThrow();
            assertEquals(BackupType.FULL, session.type());
            assertEquals(Instant.parse("2026-01-01T12:00:00Z"), session.startedAt());
            assertEquals(Instant.parse("2026-01-01T12:05:00Z"), session.completedAt());
            BackupAccountRecord account = store.findAccountsForSession("full-20260101120000").get(0);
            assertEquals(Instant.parse("2026-01-01T12:00:00Z"), account.startedAt());
            assertEquals(Instant.parse("2026-01-01T12:05:00Z"), account.completedAt());

            assertEquals(0, store.migrateLegacyRows());
        }
    }

    @Test
    void migrateLegacyRowsDerivesTypeFromSessionIdWhenColumnIsUnrecognized(@TempDir Path workDir)
            throws IOException, SQLException {
        Path databaseFile = workDir.resolve("sessions.sqlite3");
        try (SqliteMetadataStore fresh = new SqliteMetadataStore(databaseFile)) {
            // just to create the schema
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "insert into backup_session(sessionID, initial_date, conclusion_date, size, type, status) "
                            + "values ('mbox-20260101120000', '2026-01-01T12:00:00Z', null, "
                            + "'1M', 'Mailbox', 'IN PROGRESS')");
        }

        try (SqliteMetadataStore store = new SqliteMetadataStore(databaseFile)) {
            store.migrateLegacyRows();

            assertEquals(BackupType.MAILBOX, store.findSession("mbox-20260101120000").orElseThrow().type());
        }
    }

    @Test
    void mapSessionSurfacesAClearErrorForAnUnnormalizedLegacyRow(@TempDir Path workDir)
            throws IOException, SQLException {
        Path databaseFile = workDir.resolve("sessions.sqlite3");
        try (SqliteMetadataStore fresh = new SqliteMetadataStore(databaseFile)) {
            // just to create the schema
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "insert into backup_session(sessionID, initial_date, conclusion_date, size, type, status) "
                            + "values ('full-20260101120000', '2026-01-01 12:00:00', null, "
                            + "'10M', 'Full Account', 'FINISHED')");
        }

        try (SqliteMetadataStore store = new SqliteMetadataStore(databaseFile)) {
            IOException e = assertThrows(IOException.class, () -> store.findSession("full-20260101120000"));

            assertTrue(e.getMessage().contains("full-20260101120000"));
            assertTrue(e.getMessage().contains("zmbackup migrate"));
        }
    }

    @Test
    void constructingStoreCreatesIndexesOnFrequentlyQueriedBackupAccountColumns(@TempDir Path workDir)
            throws IOException, SQLException {
        Path databaseFile = workDir.resolve("sessions.sqlite3");

        new SqliteMetadataStore(databaseFile).close();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select name from sqlite_master where type = 'index' and tbl_name = 'backup_account'")) {
            List<String> indexNames = new ArrayList<>();
            while (rs.next()) {
                indexNames.add(rs.getString("name"));
            }
            assertTrue(indexNames.contains("idx_backup_account_email"));
            assertTrue(indexNames.contains("idx_backup_account_sessionID"));
        }
    }

    @Test
    void constructingAgainstARealFileRestrictsDirectoryAndDatabaseToOwnerOnlyAccess(@TempDir Path workDir)
            throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path sessionDir = workDir.resolve("sessions");
        Path databaseFile = sessionDir.resolve("sessions.sqlite3");

        new SqliteMetadataStore(databaseFile).close();

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(sessionDir)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(databaseFile)));
    }

    private static BackupSession session(String sessionId, SessionStatus status, String size) {
        Instant now = Instant.now();
        Instant completedAt = status == SessionStatus.IN_PROGRESS ? null : now;
        return new BackupSession(sessionId, BackupType.FULL, status, now, completedAt, size);
    }

    private static BackupSession session(String sessionId, BackupType type, SessionStatus status, Instant completedAt) {
        Instant now = Instant.now();
        return new BackupSession(sessionId, type, status, now, completedAt, "1M");
    }

    private static BackupAccountRecord accountRecord(String sessionId, String email) {
        Instant now = Instant.now();
        return new BackupAccountRecord(null, sessionId, email, "1M", now, now);
    }

    private static BackupAccountRecord accountRecord(String sessionId, String email, Instant completedAt) {
        return new BackupAccountRecord(null, sessionId, email, "1M", completedAt, completedAt);
    }
}
