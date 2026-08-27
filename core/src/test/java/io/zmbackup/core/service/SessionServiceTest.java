package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final SessionService sessionService = new SessionService(metadataStore);

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

    private static BackupSession session(String sessionId, Instant startedAt) {
        return new BackupSession(sessionId, BackupType.LDAP, SessionStatus.FINISHED, startedAt, startedAt, "1K");
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
