package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MigrationServiceTest {

    private final InMemoryStorageProvider storageProvider = new InMemoryStorageProvider();
    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final MigrationService migrationService = new MigrationService(storageProvider, metadataStore);

    @Test
    void importsFinishedSessionWithAccounts() throws IOException {
        storageProvider.sessionSizes.put("full-20260101120000", "10M");
        storageProvider.accountSizes.put("full-20260101120000/alice@example.com", "6M");
        storageProvider.accountSizes.put("full-20260101120000/bob@example.com", "4M");

        int imported = migrationService.importSessionsText(
                List.of(
                        "SESSION: full-20260101120000 started on Thu Jan  1 12:00:00 UTC 2026",
                        "full-20260101120000:alice@example.com:01/01/26",
                        "full-20260101120000:bob@example.com:01/01/26",
                        "SESSION: full-20260101120000 completed in Thu Jan  1 12:05:00 UTC 2026"));

        assertEquals(1, imported);
        BackupSession session = metadataStore.sessions.get("full-20260101120000");
        assertEquals(BackupType.FULL, session.type());
        assertEquals(SessionStatus.FINISHED, session.status());
        assertEquals("10M", session.size());
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), session.startedAt());
        assertEquals(session.startedAt(), session.completedAt());

        List<BackupAccountRecord> accounts = metadataStore.accounts.get("full-20260101120000");
        assertEquals(2, accounts.size());
        assertEquals("alice@example.com", accounts.get(0).email());
        assertEquals("6M", accounts.get(0).size());
        assertEquals(
                LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                accounts.get(0).startedAt());
        assertEquals("bob@example.com", accounts.get(1).email());
        assertEquals("4M", accounts.get(1).size());
    }

    @Test
    void importsFailedSessionAsFailed() throws IOException {
        migrationService.importSessionsText(
                List.of(
                        "SESSION: ldap-20260101120000 started on Thu Jan  1 12:00:00 UTC 2026",
                        "SESSION: ldap-20260101120000 failed to move staged data on Thu Jan  1 12:05:00 UTC 2026"));

        assertEquals(SessionStatus.FAILED, metadataStore.sessions.get("ldap-20260101120000").status());
    }

    @Test
    void importsStillRunningSessionAsInProgressWithNoCompletionTime() throws IOException {
        migrationService.importSessionsText(
                List.of("SESSION: inc-20260101120000 started on Thu Jan  1 12:00:00 UTC 2026"));

        BackupSession session = metadataStore.sessions.get("inc-20260101120000");
        assertEquals(SessionStatus.IN_PROGRESS, session.status());
        assertEquals(null, session.completedAt());
    }

    @Test
    void skipsUnparsableSessionIds() throws IOException {
        int imported = migrationService.importSessionsText(
                List.of("SESSION: not-a-valid-session-id started on Thu Jan  1 12:00:00 UTC 2026"));

        assertEquals(0, imported);
        assertTrue(metadataStore.sessions.isEmpty());
    }

    @Test
    void ignoresUnrelatedLines() throws IOException {
        int imported = migrationService.importSessionsText(List.of("", "some unrelated log line", "not:enough"));

        assertEquals(0, imported);
    }

    /** In-memory {@link StorageProvider} fake returning canned sizes, keyed like the real dir layout. */
    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, String> sessionSizes = new LinkedHashMap<>();
        final Map<String, String> accountSizes = new LinkedHashMap<>();

        @Override
        public OutputStream openWrite(String sessionId, String account, String suffix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream openRead(String sessionId, String account, String suffix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String sessionId, String account, String suffix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sizeOfAccount(String sessionId, String account) {
            return accountSizes.getOrDefault(sessionId + "/" + account, "0B");
        }

        @Override
        public String sizeOfSession(String sessionId) {
            return sessionSizes.getOrDefault(sessionId, "0B");
        }

        @Override
        public void deleteSession(String sessionId) {
            throw new UnsupportedOperationException();
        }
    }

    /** In-memory {@link MetadataStore} fake backed by simple maps. */
    private static final class InMemoryMetadataStore implements MetadataStore {
        final Map<String, BackupSession> sessions = new LinkedHashMap<>();
        final Map<String, List<BackupAccountRecord>> accounts = new LinkedHashMap<>();

        @Override
        public void save(BackupSession session) {
            sessions.put(session.sessionId(), session);
        }

        @Override
        public Optional<BackupSession> findSession(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public List<BackupSession> listSessions() {
            return List.copyOf(sessions.values());
        }

        @Override
        public List<BackupSession> findSessionsCompletedBefore(Instant cutoff) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteSession(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int truncate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordAccountBackup(BackupAccountRecord record) {
            accounts.computeIfAbsent(record.sessionId(), key -> new ArrayList<>()).add(record);
        }

        @Override
        public List<BackupAccountRecord> findAccountsForSession(String sessionId) {
            return accounts.getOrDefault(sessionId, List.of());
        }

        @Override
        public Optional<Instant> lastSuccessfulBackupTime(String email) {
            throw new UnsupportedOperationException();
        }
    }
}
