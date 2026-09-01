package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class SessionServiceTest {

    private final InMemoryStorageProvider storageProvider = new InMemoryStorageProvider();
    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final SessionService sessionService = new SessionService(storageProvider, metadataStore);

    @Test
    void listsSessionsMostRecentlyStartedFirst() throws IOException {
        Instant now = Instant.now();
        BackupSession oldest = session("ldap-oldest", now.minus(2, ChronoUnit.DAYS));
        BackupSession middle = session("ldap-middle", now.minus(1, ChronoUnit.DAYS));
        BackupSession newest = session("ldap-newest", now);
        metadataStore.save(oldest);
        metadataStore.save(middle);
        metadataStore.save(newest);

        List<BackupSession> sessions = sessionService.listSessions();

        assertEquals(List.of(newest, middle, oldest), sessions);
    }

    @Test
    void listSessionsReturnsEmptyWhenNoneStored() throws IOException {
        assertEquals(List.of(), sessionService.listSessions());
    }

    @Test
    void deleteSessionRemovesStorageAndMetadata() throws IOException {
        BackupSession session = session("ldap-20260701120000", Instant.now());
        metadataStore.save(session);
        storageProvider.content.put("ldap-20260701120000/alice@example.com.ldiff", new byte[0]);

        boolean deleted = sessionService.deleteSession("ldap-20260701120000");

        assertTrue(deleted);
        assertEquals(Optional.empty(), metadataStore.findSession("ldap-20260701120000"));
        assertTrue(storageProvider.deletedSessions.contains("ldap-20260701120000"));
    }

    @Test
    void deleteSessionReturnsFalseWhenSessionNotFound() throws IOException {
        boolean deleted = sessionService.deleteSession("does-not-exist");

        assertFalse(deleted);
        assertTrue(storageProvider.deletedSessions.isEmpty());
    }

    @Test
    void truncateDatabaseRemovesEverySessionAndAccountRecordButLeavesStorageAlone() throws IOException {
        metadataStore.save(session("ldap-1", Instant.now()));
        metadataStore.save(session("ldap-2", Instant.now()));
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, "ldap-1", "alice@example.com", "1K", Instant.now(), Instant.now()));

        int removed = sessionService.truncateDatabase();

        assertEquals(2, removed);
        assertEquals(List.of(), sessionService.listSessions());
        assertEquals(List.of(), metadataStore.findAccountsForSession("ldap-1"));
        assertTrue(storageProvider.deletedSessions.isEmpty());
    }

    @Test
    void truncateDatabaseReturnsZeroWhenStoreAlreadyEmpty() throws IOException {
        assertEquals(0, sessionService.truncateDatabase());
    }

    private static BackupSession session(String sessionId, Instant startedAt) {
        return new BackupSession(sessionId, BackupType.LDAP, SessionStatus.FINISHED, startedAt, startedAt, "1K");
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
            int removed = sessions.size();
            sessions.clear();
            accounts.clear();
            return removed;
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
