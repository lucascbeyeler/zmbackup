package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.RestoreResult;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.ServerConfigArchiver;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class RestoreServiceTest {

    private final FakeZimbraLdapExporter ldapExporter = new FakeZimbraLdapExporter();
    private final FakeZimbraMailboxExporter mailboxExporter = new FakeZimbraMailboxExporter();
    private final InMemoryStorageProvider storageProvider = new InMemoryStorageProvider();
    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final FakeServerConfigArchiver serverConfigArchiver = new FakeServerConfigArchiver();
    private final RestoreService restoreService = new RestoreService(
            ldapExporter, mailboxExporter, storageProvider, metadataStore, 1, serverConfigArchiver);

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
    void restoreDomainResolvesEveryDomainInSessionWhenNoneGiven() throws IOException {
        storageProvider.put("domain-1", "example.com", "ldiff", "ldiff:example.com");
        storageProvider.put("domain-1", "other.com", "ldiff", "ldiff:other.com");
        metadataStore.recordAccountBackup(recordFor("domain-1", "example.com"));
        metadataStore.recordAccountBackup(recordFor("domain-1", "other.com"));

        RestoreResult result = restoreService.restoreDomain("domain-1", List.of());

        assertEquals(2, result.total());
        assertTrue(result.allSucceeded());
        assertEquals(Set.of("example.com", "other.com"), ldapExporter.restoredDomains.keySet());
    }

    @Test
    void restoreDomainRecordsFailureWhenLdifIsMissing() throws IOException {
        RestoreResult result = restoreService.restoreDomain("domain-1", List.of("missing.com"));

        assertEquals(List.of("missing.com"), result.failedAccounts());
    }

    @Test
    void restoreDomainRecordsFailureWhenAdapterThrows() throws IOException {
        storageProvider.put("domain-1", "bad.com", "ldiff", "ldiff:bad.com");
        ldapExporter.failing.add("bad.com");

        RestoreResult result = restoreService.restoreDomain("domain-1", List.of("bad.com"));

        assertEquals(List.of("bad.com"), result.failedAccounts());
    }

    @Test
    void restoreServerConfigRestoresTheSingleArchiveForTheSession() throws IOException {
        storageProvider.put("serverconfig-1", "serverconfig", "zip", "archive-bytes");

        RestoreResult result = restoreService.restoreServerConfig("serverconfig-1");

        assertTrue(result.allSucceeded());
        assertEquals(1, result.total());
        assertEquals(List.of("archive-bytes"), serverConfigArchiver.restored);
    }

    @Test
    void restoreServerConfigFailsWhenArchiveIsMissing() throws IOException {
        RestoreResult result = restoreService.restoreServerConfig("serverconfig-1");

        assertEquals(1, result.total());
        assertEquals(List.of("serverconfig"), result.failedAccounts());
    }

    @Test
    void restoreServerConfigStillSucceedsWhenSomeFilesAreSkipped() throws IOException {
        storageProvider.put("serverconfig-1", "serverconfig", "zip", "archive-bytes");
        serverConfigArchiver.nextRestoreSkips = List.of("opt/zimbra/conf/crontabs/crontab.logger");

        RestoreResult result = restoreService.restoreServerConfig("serverconfig-1");

        assertTrue(result.allSucceeded());
        assertEquals(1, result.total());
    }

    @Test
    void restoreServerConfigFailsWhenArchiverThrows() throws IOException {
        storageProvider.put("serverconfig-1", "serverconfig", "zip", "archive-bytes");
        serverConfigArchiver.failNextRestore = true;

        RestoreResult result = restoreService.restoreServerConfig("serverconfig-1");

        assertEquals(List.of("serverconfig"), result.failedAccounts());
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
    void restoreMailboxRecordsFailureRatherThanSuccessWhenExistsCheckFails() throws IOException {
        storageProvider.failOnExists.add("mbox-1/alice@example.com.tgz");

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"));

        assertEquals(List.of("alice@example.com"), result.failedAccounts());
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
    void restoreMailboxVerifySucceedsWhenDestinationGainsTheExpectedItemCount() throws IOException {
        storageProvider.putBytes("mbox-1", "alice@example.com", "tgz", tgzWithEntries(3));
        mailboxExporter.gainOnRestore.put("alice@example.com", 3);

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"), null, true);

        assertTrue(result.allSucceeded());
    }

    @Test
    void restoreMailboxVerifyFailsWhenZimbraSilentlyDropsEveryItemDespiteReportingSuccess() throws IOException {
        storageProvider.putBytes("mbox-1", "alice@example.com", "tgz", tgzWithEntries(3));
        mailboxExporter.gainOnRestore.put("alice@example.com", 0);

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"), null, true);

        assertEquals(List.of("alice@example.com"), result.failedAccounts());
        // restore() itself never threw - this is exactly the issue #409 scenario.
        assertEquals(1, mailboxExporter.restoredInto.get("alice@example.com").size());
    }

    @Test
    void restoreMailboxVerifyFailsOnPartialItemLoss() throws IOException {
        storageProvider.putBytes("mbox-1", "alice@example.com", "tgz", tgzWithEntries(5));
        mailboxExporter.gainOnRestore.put("alice@example.com", 2);

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"), null, true);

        assertEquals(List.of("alice@example.com"), result.failedAccounts());
    }

    @Test
    void restoreMailboxVerifyAccountsForPreExistingDestinationContent() throws IOException {
        storageProvider.putBytes("mbox-1", "alice@example.com", "tgz", tgzWithEntries(3));
        mailboxExporter.itemCountsByAccount.put("bob@example.com", 10);
        // Only 1 of the 3 restored items actually landed, but the destination already had 10 of its
        // own - a naive "does the destination have enough items" check would wrongly pass here.
        mailboxExporter.gainOnRestore.put("bob@example.com", 1);

        RestoreResult result =
                restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"), "bob@example.com", true);

        assertEquals(List.of("alice@example.com"), result.failedAccounts());
    }

    @Test
    void restoreMailboxVerifyIsANoOpWhenTheSourceArchiveIsEmpty() throws IOException {
        storageProvider.putBytes("mbox-1", "alice@example.com", "tgz", tgzWithEntries(0));

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"), null, true);

        assertTrue(result.allSucceeded());
        assertEquals(0, mailboxExporter.exportCalls);
    }

    @Test
    void restoreMailboxWithoutVerifySucceedsEvenWhenContentWasSilentlyDropped() throws IOException {
        storageProvider.putBytes("mbox-1", "alice@example.com", "tgz", tgzWithEntries(3));
        mailboxExporter.gainOnRestore.put("alice@example.com", 0);

        RestoreResult result = restoreService.restoreMailbox("mbox-1", List.of("alice@example.com"));

        assertTrue(result.allSucceeded());
        assertEquals(0, mailboxExporter.exportCalls);
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

    @Test
    void constructorRejectsNullPorts() {
        assertThrows(
                NullPointerException.class,
                () -> new RestoreService(null, mailboxExporter, storageProvider, metadataStore));
        assertThrows(
                NullPointerException.class,
                () -> new RestoreService(ldapExporter, null, storageProvider, metadataStore));
        assertThrows(
                NullPointerException.class,
                () -> new RestoreService(ldapExporter, mailboxExporter, null, metadataStore));
        assertThrows(
                NullPointerException.class,
                () -> new RestoreService(ldapExporter, mailboxExporter, storageProvider, null));
    }

    private static BackupAccountRecord recordFor(String sessionId, String email) {
        Instant now = Instant.now();
        return new BackupAccountRecord(null, sessionId, email, 1024L, now, now);
    }

    /** Builds a minimal real .tgz (gzip+tar) with {@code entryCount} trivial regular-file entries. */
    private static byte[] tgzWithEntries(int entryCount) throws IOException {
        int blockSize = 512;
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (int i = 0; i < entryCount; i++) {
            byte[] content = ("item " + i).getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[blockSize];
            byte[] name = ("Inbox/item-" + i + ".eml").getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, header, 0, name.length);
            writeOctalField(header, 100, 8, 0644); // mode
            writeOctalField(header, 124, 12, content.length); // size
            Arrays.fill(header, 148, 156, (byte) ' '); // checksum placeholder
            header[156] = '0'; // typeflag: regular file
            int sum = 0;
            for (byte b : header) {
                sum += b & 0xFF;
            }
            byte[] checksum = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(checksum, 0, header, 148, checksum.length);

            tar.write(header);
            tar.write(content);
            int padding = (blockSize - content.length % blockSize) % blockSize;
            tar.write(new byte[padding]);
        }
        tar.write(new byte[blockSize * 2]);

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(gzipped)) {
            gzipOut.write(tar.toByteArray());
        }
        return gzipped.toByteArray();
    }

    private static void writeOctalField(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, length - 1 - octal.length())) + octal;
        byte[] bytes = padded.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static final class FakeServerConfigArchiver implements ServerConfigArchiver {
        final List<String> restored = new ArrayList<>();
        boolean failNextRestore;
        List<String> nextRestoreSkips = List.of();

        @Override
        public void export(OutputStream destination) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> restore(InputStream source) throws IOException {
            if (failNextRestore) {
                failNextRestore = false;
                throw new IOException("simulated server config restore failure");
            }
            restored.add(new String(source.readAllBytes()));
            List<String> skipped = nextRestoreSkips;
            nextRestoreSkips = List.of();
            return skipped;
        }
    }

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

    private static final class FakeZimbraMailboxExporter implements ZimbraMailboxExporter {
        final Map<String, List<String>> restoredInto = new LinkedHashMap<>();
        final Set<String> failing = new HashSet<>();
        final Map<String, Integer> itemCountsByAccount = new LinkedHashMap<>();
        final Map<String, Integer> gainOnRestore = new LinkedHashMap<>();
        int exportCalls;

        @Override
        public boolean export(String account, OutputStream destination, Instant since) throws IOException {
            exportCalls++;
            destination.write(tgzWithEntries(itemCountsByAccount.getOrDefault(account, 0)));
            return true;
        }

        @Override
        public void restore(String account, InputStream source) throws IOException {
            if (failing.contains(account)) {
                throw new IOException("simulated restore failure for " + account);
            }
            String content = new String(source.readAllBytes());
            restoredInto.computeIfAbsent(account, k -> new ArrayList<>()).add(content);
            // Simulates what actually lands in the destination, independent of what was uploaded -
            // this is exactly issue #409's scenario: Zimbra can silently drop some or all of it.
            Integer gain = gainOnRestore.get(account);
            if (gain != null) {
                itemCountsByAccount.merge(account, gain, Integer::sum);
            }
        }
    }

    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, byte[]> content = new LinkedHashMap<>();
        final Set<String> failOnExists = new HashSet<>();

        void put(String sessionId, String account, String suffix, String value) {
            content.put(key(sessionId, account, suffix), value.getBytes());
        }

        void putBytes(String sessionId, String account, String suffix, byte[] value) {
            content.put(key(sessionId, account, suffix), value);
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
        public boolean exists(String sessionId, String account, String suffix) throws IOException {
            if (failOnExists.contains(key(sessionId, account, suffix))) {
                throw new IOException("simulated transient storage error checking " + key(sessionId, account, suffix));
            }
            return content.containsKey(key(sessionId, account, suffix));
        }

        @Override
        public boolean sessionExists(String sessionId) {
            return content.keySet().stream().anyMatch(key -> key.startsWith(sessionId + "/"));
        }

        @Override
        public long sizeOfAccount(String sessionId, String account) {
            return 1L;
        }

        @Override
        public long sizeOfSession(String sessionId) {
            return 1L;
        }

        @Override
        public void deleteSession(String sessionId) {
            content.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
        }

        @Override
        public int deleteEmptyFiles() {
            throw new UnsupportedOperationException();
        }

        private static String key(String sessionId, String account, String suffix) {
            return sessionId + "/" + account + "." + suffix;
        }
    }

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
        public boolean backedUpSince(String identifier, BackupType type, Instant since) {
            throw new UnsupportedOperationException();
        }
    }
}
