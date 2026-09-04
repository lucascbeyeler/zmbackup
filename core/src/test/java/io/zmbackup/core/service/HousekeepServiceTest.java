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
        assertEquals(1, metadataStore.vacuumCalls);
    }

    @Test
    void rotateOldSessionsRejectsNegativeDays() {
        assertThrows(IllegalArgumentException.class, () -> housekeepService.rotateOldSessions(-1));
    }

    @Test
    void continuesPastAMidBatchFailureAndStillVacuums() throws IOException {
        Instant now = Instant.now();
        BackupSession failing = session("ldap-fail", now.minus(10, ChronoUnit.DAYS));
        BackupSession ok = session("ldap-ok", now.minus(10, ChronoUnit.DAYS));
        metadataStore.save(failing);
        metadataStore.save(ok);
        storageProvider.content.put("ldap-fail/alice@example.com.ldiff", new byte[0]);
        storageProvider.content.put("ldap-ok/bob@example.com.ldiff", new byte[0]);
        storageProvider.failOnDelete.add("ldap-fail");

        List<BackupSession> removed = housekeepService.rotateOldSessions(7);

        assertEquals(List.of(ok), removed);
        assertTrue(storageProvider.content.containsKey("ldap-fail/alice@example.com.ldiff"));
        assertTrue(storageProvider.deletedSessions.contains("ldap-ok"));
        assertTrue(metadataStore.findSession("ldap-fail").isPresent());
        assertEquals(1, metadataStore.vacuumCalls);
    }

    @Test
    void cleanEmptyRemovesZeroByteFilesButKeepsTheSessionAndOtherContent() throws IOException {
        BackupSession session = session("ldap-full", Instant.now());
        metadataStore.save(session);
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, "ldap-full", "alice@example.com", "1K", Instant.now(), Instant.now()));
        storageProvider.content.put("ldap-full/alice@example.com.ldiff", new byte[] {1});
        storageProvider.content.put("ldap-full/bob@example.com.ldiff", new byte[0]);

        int removed = housekeepService.cleanEmpty();

        assertEquals(1, removed);
        assertTrue(metadataStore.findSession("ldap-full").isPresent());
        assertTrue(storageProvider.content.containsKey("ldap-full/alice@example.com.ldiff"));
        assertTrue(!storageProvider.content.containsKey("ldap-full/bob@example.com.ldiff"));
        assertTrue(!storageProvider.deletedSessions.contains("ldap-full"));
    }

    @Test
    void cleanEmptyCountsZeroByteFilesAcrossSessions() throws IOException {
        storageProvider.content.put("ldap-one/alice@example.com.ldiff", new byte[0]);
        storageProvider.content.put("ldap-two/bob@example.com.ldiff", new byte[0]);

        int removed = housekeepService.cleanEmpty();

        assertEquals(2, removed);
    }

    @Test
    void cleanEmptyReturnsZeroWhenNothingIsEmpty() throws IOException {
        storageProvider.content.put("ldap-full/alice@example.com.ldiff", new byte[] {1});

        int removed = housekeepService.cleanEmpty();

        assertEquals(0, removed);
        assertTrue(storageProvider.content.containsKey("ldap-full/alice@example.com.ldiff"));
    }

    private static BackupSession session(String sessionId, Instant completedAt) {
        return new BackupSession(
                sessionId, BackupType.LDAP, SessionStatus.FINISHED, completedAt.minusSeconds(60), completedAt, "1K");
    }

    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, byte[]> content = new LinkedHashMap<>();
        final Set<String> deletedSessions = new java.util.HashSet<>();
        final Set<String> failOnDelete = new java.util.HashSet<>();

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
        public void deleteSession(String sessionId) throws IOException {
            if (failOnDelete.contains(sessionId)) {
                throw new IOException("simulated failure deleting " + sessionId);
            }
            deletedSessions.add(sessionId);
            content.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
        }

        @Override
        public int deleteEmptyFiles() {
            List<String> empty =
                    content.entrySet().stream()
                            .filter(entry -> entry.getValue().length == 0)
                            .map(Map.Entry::getKey)
                            .toList();
            empty.forEach(content::remove);
            return empty.size();
        }
    }

    private static final class InMemoryMetadataStore implements MetadataStore {
        final Map<String, BackupSession> sessions = new LinkedHashMap<>();
        final Map<String, List<BackupAccountRecord>> accounts = new LinkedHashMap<>();
        int vacuumCalls = 0;

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

        @Override
        public boolean backedUpSince(String identifier, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void vacuum() {
            vacuumCalls++;
        }
    }
}
