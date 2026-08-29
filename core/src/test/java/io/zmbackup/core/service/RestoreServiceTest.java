package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.RestoreResult;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestoreService} against hand-rolled fakes of its ports, mirroring the
 * style of {@link BackupServiceTest}.
 */
class RestoreServiceTest {

    private final FakeZimbraLdapExporter ldapExporter = new FakeZimbraLdapExporter();
    private final FakeZimbraMailboxExporter mailboxExporter = new FakeZimbraMailboxExporter();
    private final InMemoryStorageProvider storageProvider = new InMemoryStorageProvider();
    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final RestoreService restoreService =
            new RestoreService(ldapExporter, mailboxExporter, storageProvider, metadataStore);

    @Test
    void restoreLdapRestoresExplicitAccounts() throws IOException {
        storageProvider.put("full-1", "alice@example.com", "ldiff", "ldiff:alice@example.com");

        RestoreResult result = restoreService.restoreLdap("full-1", List.of("alice@example.com"));

        assertTrue(result.allSucceeded());
        assertEquals(1, result.total());
        assertEquals(List.of("ldiff:alice@example.com"), ldapExporter.restored.get("alice@example.com"));
    }

    @Test
    void restoreLdapResolvesEveryAccountInSessionWhenNoneGiven() throws IOException {
        storageProvider.put("full-1", "alice@example.com", "ldiff", "ldiff:alice@example.com");
        storageProvider.put("full-1", "bob@example.com", "ldiff", "ldiff:bob@example.com");
        metadataStore.recordAccountBackup(recordFor("full-1", "alice@example.com"));
        metadataStore.recordAccountBackup(recordFor("full-1", "bob@example.com"));

        RestoreResult result = restoreService.restoreLdap("full-1", List.of());

        assertEquals(2, result.total());
        assertTrue(result.allSucceeded());
        assertEquals(Set.of("alice@example.com", "bob@example.com"), ldapExporter.restored.keySet());
    }

    @Test
    void restoreLdapRecordsFailureWhenLdifIsMissing() throws IOException {
        RestoreResult result = restoreService.restoreLdap("full-1", List.of("missing@example.com"));

        assertEquals(1, result.total());
        assertEquals(List.of("missing@example.com"), result.failedAccounts());
    }

    @Test
    void restoreLdapRecordsFailureWhenAdapterThrows() throws IOException {
        storageProvider.put("full-1", "bad@example.com", "ldiff", "ldiff:bad@example.com");
        ldapExporter.failing.add("bad@example.com");

        RestoreResult result = restoreService.restoreLdap("full-1", List.of("bad@example.com"));

        assertEquals(List.of("bad@example.com"), result.failedAccounts());
    }

    @Test
    void restoreDomainRestoresViaRestoreDomainMethod() throws IOException {
        storageProvider.put("domain-1", "example.com", "ldiff", "ldiff:example.com");

        RestoreResult result = restoreService.restoreDomain("domain-1", List.of("example.com"));

        assertTrue(result.allSucceeded());
        assertEquals(List.of("ldiff:example.com"), ldapExporter.restoredDomains.get("example.com"));
        assertTrue(ldapExporter.restored.isEmpty());
    }

    @Test
    void restoreMailboxRestoresIntoTheSameAccountByDefault() throws IOException {
        storageProvider.put("mbox-1", "alice@example.com", "tgz", "tgz:alice");

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"));

        assertTrue(result.allSucceeded());
        assertEquals(List.of("tgz:alice"), mailboxExporter.restoredInto.get("alice@example.com"));
    }

    @Test
    void restoreMailboxTreatsMissingArchiveAsSuccessNotFailure() throws IOException {
        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"));

        assertTrue(result.allSucceeded());
        assertTrue(mailboxExporter.restoredInto.isEmpty());
    }

    @Test
    void restoreMailboxRestoresIntoDestinationAccountWhenGiven() throws IOException {
        storageProvider.put("mbox-1", "alice@example.com", "tgz", "tgz:alice");

        RestoreResult result =
                restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"), "bob@example.com");

        assertTrue(result.allSucceeded());
        assertEquals(List.of("tgz:alice"), mailboxExporter.restoredInto.get("bob@example.com"));
        assertTrue(mailboxExporter.restoredInto.get("alice@example.com") == null);
    }

    @Test
    void restoreMailboxRejectsDestinationWithMultipleAccounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> restoreService.restoreMailbox(
                        "mbox-1", List.of("alice@example.com", "bob@example.com"), "carol@example.com"));
    }

    @Test
    void restoreMailboxRejectsDestinationWithNoExplicitAccount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> restoreService.restoreMailbox("mbox-1", List.of(), "carol@example.com"));
    }

    @Test
    void restoreMailboxRecordsFailureWhenAdapterThrows() throws IOException {
        storageProvider.put("mbox-1", "bad@example.com", "tgz", "tgz:bad");
        mailboxExporter.failing.add("bad@example.com");

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("bad@example.com"));

        assertEquals(List.of("bad@example.com"), result.failedAccounts());
    }

    @Test
    void restoreFullRestoresLdapThenMailboxAndUnionsFailures() throws IOException {
        storageProvider.put("full-1", "alice@example.com", "ldiff", "ldiff:alice@example.com");
        storageProvider.put("full-1", "alice@example.com", "tgz", "tgz:alice");
        storageProvider.put("full-1", "bad@example.com", "ldiff", "ldiff:bad@example.com");
        storageProvider.put("full-1", "bad@example.com", "tgz", "tgz:bad");
        ldapExporter.failing.add("bad@example.com");
        mailboxExporter.failing.add("bad@example.com");

        RestoreResult result = restoreService.restoreFull("full-1", List.of("alice@example.com", "bad@example.com"));

        assertEquals(2, result.total());
        assertEquals(List.of("bad@example.com"), result.failedAccounts());
        assertEquals(List.of("tgz:alice"), mailboxExporter.restoredInto.get("alice@example.com"));
        assertEquals(List.of("ldiff:alice@example.com"), ldapExporter.restored.get("alice@example.com"));
    }

    private static BackupAccountRecord recordFor(String sessionId, String email) {
        Instant now = Instant.now();
        return new BackupAccountRecord(null, sessionId, email, "1K", now, now);
    }

    /** In-memory {@link ZimbraLdapExporter} fake that records what was restored. */
    private static final class FakeZimbraLdapExporter implements ZimbraLdapExporter {
        final Map<String, List<String>> restored = new LinkedHashMap<>();
        final Map<String, List<String>> restoredDomains = new LinkedHashMap<>();
        final Set<String> failing = new HashSet<>();

        @Override
        public void export(String identifier, LdapObjectType type, OutputStream destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exportDomain(String domain, OutputStream destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restore(LdapObjectType type, InputStream source) throws IOException {
            String content = new String(source.readAllBytes());
            String identifier = content.substring(content.lastIndexOf(':') + 1);
            if (failing.contains(identifier)) {
                throw new IOException("simulated restore failure for " + identifier);
            }
            restored.computeIfAbsent(identifier, k -> new ArrayList<>()).add(content);
        }

        @Override
        public void restoreDomain(InputStream source) throws IOException {
            String content = new String(source.readAllBytes());
            String identifier = content.substring(content.lastIndexOf(':') + 1);
            if (failing.contains(identifier)) {
                throw new IOException("simulated restore failure for " + identifier);
            }
            restoredDomains.computeIfAbsent(identifier, k -> new ArrayList<>()).add(content);
        }
    }

    /** In-memory {@link ZimbraMailboxExporter} fake that records the destination each restore went to. */
    private static final class FakeZimbraMailboxExporter implements ZimbraMailboxExporter {
        final Map<String, List<String>> restoredInto = new LinkedHashMap<>();
        final Set<String> failing = new HashSet<>();

        @Override
        public boolean export(String account, OutputStream destination, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restore(String account, InputStream source) throws IOException {
            if (failing.contains(account)) {
                throw new IOException("simulated restore failure for " + account);
            }
            String content = new String(source.readAllBytes());
            restoredInto.computeIfAbsent(account, k -> new ArrayList<>()).add(content);
        }
    }

    /** In-memory {@link StorageProvider} fake backed by a byte-array map. */
    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, byte[]> content = new LinkedHashMap<>();

        void put(String sessionId, String account, String suffix, String value) {
            content.put(key(sessionId, account, suffix), value.getBytes());
        }

        @Override
        public OutputStream openWrite(String sessionId, String account, String suffix) {
            String key = key(sessionId, account, suffix);
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    content.put(key, toByteArray());
                }
            };
        }

        @Override
        public InputStream openRead(String sessionId, String account, String suffix) throws IOException {
            byte[] bytes = content.get(key(sessionId, account, suffix));
            if (bytes == null) {
                throw new IOException("no content for " + key(sessionId, account, suffix));
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean exists(String sessionId, String account, String suffix) {
            return content.containsKey(key(sessionId, account, suffix));
        }

        @Override
        public String sizeOfAccount(String sessionId, String account) {
            return "1B";
        }

        @Override
        public String sizeOfSession(String sessionId) {
            return "1B";
        }

        @Override
        public void deleteSession(String sessionId) {
            content.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
        }

        private static String key(String sessionId, String account, String suffix) {
            return sessionId + "/" + account + "." + suffix;
        }
    }

    /** In-memory {@link MetadataStore} fake backed by simple maps. */
    private static final class InMemoryMetadataStore implements MetadataStore {
        final Map<String, List<BackupAccountRecord>> accounts = new LinkedHashMap<>();

        @Override
        public void save(BackupSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BackupSession> findSession(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BackupSession> listSessions() {
            throw new UnsupportedOperationException();
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
