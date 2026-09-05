package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.CloudMigrationResult;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CloudMigrationServiceTest {

    private final InMemoryStorage sourceStorage = new InMemoryStorage();
    private final InMemoryMetadata sourceMetadata = new InMemoryMetadata();
    private final InMemoryStorage destinationStorage = new InMemoryStorage();
    private final InMemoryMetadata destinationMetadata = new InMemoryMetadata();
    private final CloudMigrationService migrationService =
            new CloudMigrationService(sourceStorage, sourceMetadata, destinationStorage, destinationMetadata);

    @Test
    void migratesSessionsAccountsAndFiles() throws IOException {
        seedSession("full-20260101120000", "alice@example.com", "ldiff-content", "tgz-content");

        CloudMigrationResult result = migrationService.migrate();

        assertEquals(1, result.sessionsMigrated());
        assertEquals(1, result.accountsMigrated());
        assertEquals(
                "full-20260101120000",
                destinationMetadata.sessions.get("full-20260101120000").sessionId());
        assertEquals(
                "ldiff-content",
                new String(
                        destinationStorage.readAll("full-20260101120000", "alice@example.com", "ldiff"),
                        StandardCharsets.UTF_8));
        assertEquals(
                "tgz-content",
                new String(
                        destinationStorage.readAll("full-20260101120000", "alice@example.com", "tgz"),
                        StandardCharsets.UTF_8));
    }

    @Test
    void migratesOnlyTheSuffixesThatExist() throws IOException {
        seedSession("mbox-20260101120000", "bob@example.com", null, "tgz-only");

        migrationService.migrate();

        assertTrue(destinationStorage.exists("mbox-20260101120000", "bob@example.com", "tgz"));
        assertTrue(!destinationStorage.exists("mbox-20260101120000", "bob@example.com", "ldiff"));
    }

    @Test
    void retryingAfterAPartialFailureDoesNotDuplicateAlreadyMigratedAccounts() throws IOException {
        seedSession("full-20260101120000", "alice@example.com", "ldiff", "tgz");
        sourceMetadata.recordAccountBackup(new BackupAccountRecord(
                null,
                "full-20260101120000",
                "bob@example.com",
                "1K",
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T12:05:00Z")));
        sourceStorage.write("full-20260101120000", "bob@example.com", "ldiff", "bob-ldiff");

        CloudMigrationResult first = migrationService.migrate();
        CloudMigrationResult second = migrationService.migrate();

        assertEquals(2, first.accountsMigrated());
        assertEquals(0, second.accountsMigrated());
        assertEquals(
                2,
                destinationMetadata.findAccountsForSession("full-20260101120000").size());
    }

    private void seedSession(String sessionId, String email, String ldiffContent, String tgzContent)
            throws IOException {
        sourceMetadata.save(new BackupSession(
                sessionId,
                BackupType.FULL,
                SessionStatus.FINISHED,
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T12:05:00Z"),
                "10M"));
        sourceMetadata.recordAccountBackup(new BackupAccountRecord(
                null, sessionId, email, "1K", Instant.parse("2026-01-01T12:00:00Z"), Instant.parse(
                        "2026-01-01T12:05:00Z")));
        if (ldiffContent != null) {
            sourceStorage.write(sessionId, email, "ldiff", ldiffContent);
        }
        if (tgzContent != null) {
            sourceStorage.write(sessionId, email, "tgz", tgzContent);
        }
    }

    private static final class InMemoryStorage implements StorageProvider {
        private final Map<String, byte[]> files = new HashMap<>();

        void write(String sessionId, String account, String suffix, String content) {
            files.put(key(sessionId, account, suffix), content.getBytes(StandardCharsets.UTF_8));
        }

        byte[] readAll(String sessionId, String account, String suffix) {
            return files.get(key(sessionId, account, suffix));
        }

        private static String key(String sessionId, String account, String suffix) {
            return sessionId + "/" + account + "." + suffix;
        }

        @Override
        public OutputStream openWrite(String sessionId, String account, String suffix) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    files.put(key(sessionId, account, suffix), toByteArray());
                }
            };
        }

        @Override
        public InputStream openRead(String sessionId, String account, String suffix) {
            return new ByteArrayInputStream(files.get(key(sessionId, account, suffix)));
        }

        @Override
        public boolean exists(String sessionId, String account, String suffix) {
            return files.containsKey(key(sessionId, account, suffix));
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
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteEmptyFiles() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryMetadata implements MetadataStore {
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

        @Override
        public boolean backedUpSince(String identifier, Instant since) {
            throw new UnsupportedOperationException();
        }
    }
}
