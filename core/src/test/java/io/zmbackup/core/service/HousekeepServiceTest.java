package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HousekeepServiceTest {

    private final InMemoryStorageProvider storageProvider = new InMemoryStorageProvider();
    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final HousekeepService housekeepService = new HousekeepService(storageProvider, metadataStore);

    @Test
    void rotatesSessionsCompletedBeforeCutoff() throws IOException {
        Instant now = Instant.now();
        BackupSession old = session("ldap-old", now.minus(10, ChronoUnit.DAYS));
        BackupSession recent = session("ldap-recent", now.minus(1, ChronoUnit.DAYS));
        metadataStore.save(old);
        metadataStore.save(recent);
        storageProvider.content.put("ldap-old/alice@example.com.ldiff", new byte[0]);
        storageProvider.content.put("ldap-recent/bob@example.com.ldiff", new byte[0]);

        List<BackupSession> removed = housekeepService.rotateOldSessions(7);

        assertEquals(List.of(old), removed);
        assertEquals(Optional.empty(), metadataStore.findSession("ldap-old"));
        assertTrue(metadataStore.findSession("ldap-recent").isPresent());
        assertTrue(storageProvider.deletedSessions.contains("ldap-old"));
        assertTrue(!storageProvider.deletedSessions.contains("ldap-recent"));
    }

    @Test
    void rotateOldSessionsRejectsNegativeDays() {
        assertThrows(IllegalArgumentException.class, () -> housekeepService.rotateOldSessions(-1));
    }

    @Test
    void cleanEmptyRemovesSessionsWithNoAccountRecords() throws IOException {
        BackupSession empty = session("ldap-empty", Instant.now());
        BackupSession withAccounts = session("ldap-full", Instant.now());
        metadataStore.save(empty);
        metadataStore.save(withAccounts);
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, "ldap-full", "alice@example.com", "1K", Instant.now(), Instant.now()));

        List<BackupSession> removed = housekeepService.cleanEmpty();

        assertEquals(List.of(empty), removed);
        assertEquals(Optional.empty(), metadataStore.findSession("ldap-empty"));
        assertTrue(metadataStore.findSession("ldap-full").isPresent());
        assertTrue(storageProvider.deletedSessions.contains("ldap-empty"));
    }

    @Test
    void cleanEmptyKeepsSessionsWithAccountRecords() throws IOException {
        BackupSession withAccounts = session("ldap-full", Instant.now());
        metadataStore.save(withAccounts);
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, "ldap-full", "alice@example.com", "1K", Instant.now(), Instant.now()));

        List<BackupSession> removed = housekeepService.cleanEmpty();

        assertTrue(removed.isEmpty());
        assertTrue(metadataStore.findSession("ldap-full").isPresent());
    }

    private static BackupSession session(String sessionId, Instant completedAt) {
        return new BackupSession(
                sessionId, BackupType.LDAP, SessionStatus.FINISHED, completedAt.minusSeconds(60), completedAt, "1K");
    }

    /** In-memory {@link StorageProvider} fake that records which sessions were deleted. */
    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, byte[]> content = new LinkedHashMap<>();
        final Set<String> deletedSessions = new java.util.HashSet<>();

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
            return content.containsKey(sessionId + "/" + account + "." + suffix);
        }

        @Override
        public String sizeOfAccount(String sessionId, String account) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sizeOfSession(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteSession(String sessionId) {
            deletedSessions.add(sessionId);
            content.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
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
            return sessions.values().stream()
                    .filter(s -> s.completedAt() != null && s.completedAt().isBefore(cutoff))
                    .toList();
        }

        @Override
        public void deleteSession(String sessionId) {
            sessions.remove(sessionId);
            accounts.remove(sessionId);
        }

        @Override
        public int truncate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordAccountBackup(BackupAccountRecord record) {
            accounts.computeIfAbsent(record.sessionId(), k -> new ArrayList<>()).add(record);
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
