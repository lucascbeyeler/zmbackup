package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackupServiceTest {

    private final FakeAccountDiscovery accountDiscovery = new FakeAccountDiscovery();
    private final FakeZimbraLdapExporter ldapExporter = new FakeZimbraLdapExporter();
    private final FakeZimbraMailboxExporter mailboxExporter = new FakeZimbraMailboxExporter();
    private final InMemoryStorageProvider storageProvider = new InMemoryStorageProvider();
    private final InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
    private final BackupService backupService = new BackupService(
            accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore);

    @Test
    void backsUpDiscoveredAccountsForLdapType() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com", "bob@example.com"));

        Optional<BackupSession> result = backupService.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        BackupSession session = result.get();
        assertTrue(session.sessionId().startsWith("ldap-"));
        assertEquals(BackupType.LDAP, session.type());
        assertEquals(SessionStatus.FINISHED, session.status());

        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(session.sessionId());
        assertEquals(2, records.size());
        assertEquals(Set.of("alice@example.com", "bob@example.com"), namesOf(records));
        assertEquals(
                Set.of(LdapObjectType.ACCOUNT),
                ldapExporter.exportedTypesFor("alice@example.com", "bob@example.com"));
    }

    @Test
    void backsUpExplicitIdentifiersWithoutDiscovery() throws IOException {
        Optional<BackupSession> result = backupService.backup(BackupType.ALIAS, List.of("alias@example.com"));

        assertTrue(result.isPresent());
        assertTrue(accountDiscovery.discoverCalls.isEmpty());
        assertEquals(List.of(LdapObjectType.ALIAS), ldapExporter.exportedTypes.get("alias@example.com"));
    }

    @Test
    void scopesDiscoveryToDomainWhenGiven() throws IOException {
        accountDiscovery.byDomain.put(
                Map.entry(LdapObjectType.DISTRIBUTION_LIST, "example.com"), List.of("list@example.com"));

        Optional<BackupSession> result =
                backupService.backup(BackupType.DISTRIBUTION_LIST, List.of(), "example.com");

        assertTrue(result.isPresent());
        assertEquals(1, metadataStore.findAccountsForSession(result.get().sessionId()).size());
    }

    @Test
    void backsUpSignatureType() throws IOException {
        Optional<BackupSession> result = backupService.backup(BackupType.SIGNATURE, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertTrue(result.get().sessionId().startsWith("signature-"));
        assertEquals(List.of(LdapObjectType.SIGNATURE), ldapExporter.exportedTypes.get("alice@example.com"));
    }

    @Test
    void domainTypeDiscoversDomainsAndUsesExportDomain() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.DOMAIN, List.of("example.com"));

        Optional<BackupSession> result = backupService.backup(BackupType.DOMAIN);

        assertTrue(result.isPresent());
        assertTrue(ldapExporter.domainExports.contains("example.com"));
        assertTrue(ldapExporter.exportedTypes.isEmpty());
    }

    @Test
    void discoveredAccountOnBlocklistIsSkipped() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com", "bob@example.com"));
        BackupService blocklisted = new BackupService(
                accountDiscovery,
                ldapExporter,
                mailboxExporter,
                storageProvider,
                metadataStore,
                identifier -> identifier.equals("bob@example.com"),
                1);

        Optional<BackupSession> result = blocklisted.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void explicitAccountBypassesBlocklist() throws IOException {
        BackupService blocklisted = new BackupService(
                accountDiscovery,
                ldapExporter,
                mailboxExporter,
                storageProvider,
                metadataStore,
                identifier -> true,
                1);

        Optional<BackupSession> result = blocklisted.backup(BackupType.LDAP, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FINISHED, result.get().status());
    }

    @Test
    void returnsEmptyAndSkipsSessionWhenNothingToBackUp() throws IOException {
        Optional<BackupSession> result = backupService.backup(BackupType.SIGNATURE);

        assertTrue(result.isEmpty());
        assertTrue(metadataStore.listSessions().isEmpty());
    }

    @Test
    void marksSessionFailedWhenAnyExportFails() throws IOException {
        ldapExporter.failing.add("bad@example.com");

        Optional<BackupSession> result =
                backupService.backup(BackupType.LDAP, List.of("good@example.com", "bad@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FAILED, result.get().status());
        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(result.get().sessionId());
        assertEquals(Set.of("good@example.com"), namesOf(records));
    }

    @Test
    void incrementalTypePassesFortyEightHourCutoffToExporter() throws IOException {
        Instant lastBackup = Instant.parse("2026-01-10T12:00:00Z");
        metadataStore.lastBackupTimes.put("alice@example.com", lastBackup);

        Optional<BackupSession> result = backupService.backup(BackupType.INCREMENTAL, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertTrue(result.get().sessionId().startsWith("inc-"));
        assertEquals(SessionStatus.FINISHED, result.get().status());
        assertEquals(lastBackup.minus(Duration.ofHours(48)), mailboxExporter.exported.get("alice@example.com"));
    }

    @Test
    void incrementalTypeFallsBackToFullExportWhenNoPriorBackup() throws IOException {
        Optional<BackupSession> result = backupService.backup(BackupType.INCREMENTAL, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FINISHED, result.get().status());
        assertTrue(mailboxExporter.exported.containsKey("alice@example.com"));
        assertEquals(null, mailboxExporter.exported.get("alice@example.com"));
    }

    @Test
    void backsUpDiscoveredAccountsForFullType() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com", "bob@example.com"));

        Optional<BackupSession> result = backupService.backup(BackupType.FULL);

        assertTrue(result.isPresent());
        BackupSession session = result.get();
        assertTrue(session.sessionId().startsWith("full-"));
        assertEquals(BackupType.FULL, session.type());
        assertEquals(SessionStatus.FINISHED, session.status());

        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(session.sessionId());
        assertEquals(Set.of("alice@example.com", "bob@example.com"), namesOf(records));
        assertEquals(Set.of(LdapObjectType.ACCOUNT), ldapExporter.exportedTypesFor("alice@example.com", "bob@example.com"));
        assertEquals(Set.of("alice@example.com", "bob@example.com"), mailboxExporter.exported.keySet());
    }

    @Test
    void fullTypeSkipsMailboxAndRecordWhenLdapExportFails() throws IOException {
        ldapExporter.failing.add("bad@example.com");

        Optional<BackupSession> result = backupService.backup(BackupType.FULL, List.of("bad@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FAILED, result.get().status());
        assertTrue(metadataStore.findAccountsForSession(result.get().sessionId()).isEmpty());
        assertFalse(mailboxExporter.exported.containsKey("bad@example.com"));
    }

    @Test
    void fullTypeSkipsRecordWhenMailboxExportFailsAfterLdapSucceeds() throws IOException {
        mailboxExporter.failing.add("bad@example.com");

        Optional<BackupSession> result = backupService.backup(BackupType.FULL, List.of("bad@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FAILED, result.get().status());
        assertTrue(metadataStore.findAccountsForSession(result.get().sessionId()).isEmpty());
        assertEquals(List.of(LdapObjectType.ACCOUNT), ldapExporter.exportedTypes.get("bad@example.com"));
    }

    @Test
    void backsUpDiscoveredAccountsForMailboxType() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com", "bob@example.com"));

        Optional<BackupSession> result = backupService.backup(BackupType.MAILBOX);

        assertTrue(result.isPresent());
        BackupSession session = result.get();
        assertTrue(session.sessionId().startsWith("mbox-"));
        assertEquals(BackupType.MAILBOX, session.type());
        assertEquals(SessionStatus.FINISHED, session.status());

        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(session.sessionId());
        assertEquals(Set.of("alice@example.com", "bob@example.com"), namesOf(records));
        assertEquals(Set.of("alice@example.com", "bob@example.com"), mailboxExporter.exported.keySet());
        assertTrue(ldapExporter.exportedTypes.isEmpty());
    }

    @Test
    void mailboxTypeSucceedsWithoutWritingWhenNoNewContent() throws IOException {
        mailboxExporter.noNewContent.add("alice@example.com");

        Optional<BackupSession> result = backupService.backup(BackupType.MAILBOX, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FINISHED, result.get().status());
        assertEquals(1, metadataStore.findAccountsForSession(result.get().sessionId()).size());
    }

    @Test
    void marksSessionFailedWhenMailboxExportFails() throws IOException {
        mailboxExporter.failing.add("bad@example.com");

        Optional<BackupSession> result = backupService.backup(BackupType.MAILBOX, List.of("bad@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FAILED, result.get().status());
        assertTrue(metadataStore.findAccountsForSession(result.get().sessionId()).isEmpty());
    }

    private static Set<String> namesOf(List<BackupAccountRecord> records) {
        Set<String> names = new HashSet<>();
        for (BackupAccountRecord record : records) {
            names.add(record.email());
        }
        return names;
    }

    /** In-memory {@link AccountDiscovery} fake returning preconfigured results. */
    private static final class FakeAccountDiscovery implements AccountDiscovery {
        final Map<LdapObjectType, List<String>> wholeDirectory = new EnumMap<>(LdapObjectType.class);
        final Map<Map.Entry<LdapObjectType, String>, List<String>> byDomain = new HashMap<>();
        final List<LdapObjectType> discoverCalls = new ArrayList<>();

        @Override
        public List<String> discover(LdapObjectType type) {
            discoverCalls.add(type);
            return wholeDirectory.getOrDefault(type, List.of());
        }

        @Override
        public List<String> discoverForDomain(LdapObjectType type, String domain) {
            return byDomain.getOrDefault(Map.entry(type, domain), List.of());
        }
    }

    /** In-memory {@link ZimbraLdapExporter} fake that records what was exported. */
    private static final class FakeZimbraLdapExporter implements ZimbraLdapExporter {
        final Map<String, List<LdapObjectType>> exportedTypes = new LinkedHashMap<>();
        final Set<String> domainExports = new HashSet<>();
        final Set<String> failing = new HashSet<>();

        @Override
        public void export(String identifier, LdapObjectType type, OutputStream destination) throws IOException {
            if (failing.contains(identifier)) {
                throw new IOException("simulated export failure for " + identifier);
            }
            exportedTypes.computeIfAbsent(identifier, k -> new ArrayList<>()).add(type);
            destination.write(("ldiff:" + identifier).getBytes());
        }

        @Override
        public void exportDomain(String domain, OutputStream destination) throws IOException {
            if (failing.contains(domain)) {
                throw new IOException("simulated export failure for " + domain);
            }
            domainExports.add(domain);
            destination.write(("ldiff:" + domain).getBytes());
        }

        @Override
        public void restore(LdapObjectType type, InputStream source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restoreDomain(InputStream source) {
            throw new UnsupportedOperationException();
        }

        Set<LdapObjectType> exportedTypesFor(String... identifiers) {
            Set<LdapObjectType> types = new HashSet<>();
            for (String identifier : identifiers) {
                types.addAll(exportedTypes.getOrDefault(identifier, List.of()));
            }
            return types;
        }
    }

    /** In-memory {@link ZimbraMailboxExporter} fake that records what was exported. */
    private static final class FakeZimbraMailboxExporter implements ZimbraMailboxExporter {
        final Map<String, Instant> exported = new LinkedHashMap<>();
        final Set<String> noNewContent = new HashSet<>();
        final Set<String> failing = new HashSet<>();

        @Override
        public boolean export(String account, OutputStream destination, Instant since) throws IOException {
            if (failing.contains(account)) {
                throw new IOException("simulated export failure for " + account);
            }
            if (noNewContent.contains(account)) {
                return false;
            }
            exported.put(account, since);
            destination.write(("tgz:" + account).getBytes());
            return true;
        }

        @Override
        public void restore(String account, InputStream source) {
            throw new UnsupportedOperationException();
        }
    }

    /** In-memory {@link StorageProvider} fake backed by a byte-array map. */
    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, byte[]> content = new LinkedHashMap<>();

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
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public boolean exists(String sessionId, String account, String suffix) {
            return content.containsKey(key(sessionId, account, suffix));
        }

        @Override
        public String sizeOfAccount(String sessionId, String account) {
            String prefix = sessionId + "/" + account + ".";
            long total = content.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .mapToLong(entry -> entry.getValue().length)
                    .sum();
            return total + "B";
        }

        @Override
        public String sizeOfSession(String sessionId) {
            String prefix = sessionId + "/";
            long total = content.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .mapToLong(entry -> entry.getValue().length)
                    .sum();
            return total + "B";
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
        final Map<String, BackupSession> sessions = new LinkedHashMap<>();
        final Map<String, List<BackupAccountRecord>> accounts = new LinkedHashMap<>();
        final Map<String, Instant> lastBackupTimes = new HashMap<>();

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
            return Optional.ofNullable(lastBackupTimes.get(email));
        }
    }
}
