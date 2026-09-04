package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.Notifier;
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
    private final BackupService backupService = BackupService.builder(
                    accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
            .build();

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
        BackupService blocklisted = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> identifier.equals("bob@example.com"))
                .maxParallelProcesses(1)
                .build();

        Optional<BackupSession> result = blocklisted.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void discoveredDomainOnBlocklistIsSkipped() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.DOMAIN, List.of("example.com", "blocked.example.com"));
        BackupService blocklisted = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> identifier.equals("blocked.example.com"))
                .maxParallelProcesses(1)
                .build();

        Optional<BackupSession> result = blocklisted.backup(BackupType.DOMAIN);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void discoveredAccountWithUnexpectedFormatIsSkipped() throws IOException {
        accountDiscovery.wholeDirectory.put(
                LdapObjectType.ACCOUNT, List.of("alice@example.com", "../../etc/passwd"));

        Optional<BackupSession> result = backupService.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void discoveredDomainWithUnexpectedFormatIsSkipped() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.DOMAIN, List.of("example.com", "not,a=domain"));

        Optional<BackupSession> result = backupService.backup(BackupType.DOMAIN);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void explicitlyRequestedAccountBypassesFormatValidation() throws IOException {
        Optional<BackupSession> result = backupService.backup(BackupType.LDAP, List.of("not-an-email"));

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("not-an-email"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void discoveredSignatureNameIsNotFormatValidated() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.SIGNATURE, List.of("My Vacation Signature"));

        Optional<BackupSession> result = backupService.backup(BackupType.SIGNATURE);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("My Vacation Signature"),
                namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void domainScopedDiscoveryRespectsBlocklist() throws IOException {
        accountDiscovery.byDomain.put(
                Map.entry(LdapObjectType.ACCOUNT, "example.com"),
                List.of("alice@example.com", "bob@example.com"));
        BackupService blocklisted = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> identifier.equals("bob@example.com"))
                .maxParallelProcesses(1)
                .build();

        Optional<BackupSession> result = blocklisted.backup(BackupType.LDAP, List.of(), "example.com");

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void backupIsSkippedEntirelyWhenEveryDiscoveredAccountIsBlocked() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com"));
        BackupService blocklisted = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> true)
                .maxParallelProcesses(1)
                .build();

        Optional<BackupSession> result = blocklisted.backup(BackupType.LDAP);

        assertTrue(result.isEmpty());
        assertTrue(metadataStore.listSessions().isEmpty());
    }

    @Test
    void lockBackupSkipsDiscoveredAccountAlreadyBackedUpToday() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com", "bob@example.com"));
        metadataStore.recordAccountBackup(new BackupAccountRecord(
                null, "ldap-earlier", "bob@example.com", "1B", Instant.now(), Instant.now()));
        BackupService lockedBackup = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> false)
                .notifier(new RecordingNotifier())
                .maxParallelProcesses(1)
                .lockBackup(true)
                .build();

        Optional<BackupSession> result = lockedBackup.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void lockBackupDoesNotSkipWhenPriorBackupIsOlderThanADay() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com"));
        metadataStore.recordAccountBackup(new BackupAccountRecord(
                null,
                "ldap-earlier",
                "alice@example.com",
                "1B",
                Instant.now().minus(Duration.ofHours(30)),
                Instant.now().minus(Duration.ofHours(30))));
        BackupService lockedBackup = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> false)
                .notifier(new RecordingNotifier())
                .maxParallelProcesses(1)
                .lockBackup(true)
                .build();

        Optional<BackupSession> result = lockedBackup.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void lockBackupDisabledDoesNotSkipRecentlyBackedUpAccount() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com"));
        metadataStore.recordAccountBackup(new BackupAccountRecord(
                null, "ldap-earlier", "alice@example.com", "1B", Instant.now(), Instant.now()));

        Optional<BackupSession> result = backupService.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(
                Set.of("alice@example.com"), namesOf(metadataStore.findAccountsForSession(result.get().sessionId())));
    }

    @Test
    void explicitAccountBypassesLockBackup() throws IOException {
        metadataStore.recordAccountBackup(new BackupAccountRecord(
                null, "ldap-earlier", "alice@example.com", "1B", Instant.now(), Instant.now()));
        BackupService lockedBackup = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> false)
                .notifier(new RecordingNotifier())
                .maxParallelProcesses(1)
                .lockBackup(true)
                .build();

        Optional<BackupSession> result = lockedBackup.backup(BackupType.LDAP, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FINISHED, result.get().status());
    }

    @Test
    void explicitAccountBypassesBlocklist() throws IOException {
        BackupService blocklisted = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> true)
                .maxParallelProcesses(1)
                .build();

        Optional<BackupSession> result = blocklisted.backup(BackupType.LDAP, List.of("alice@example.com"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FINISHED, result.get().status());
    }

    @Test
    void backupNeverRunsMoreThanMaxParallelProcessesAccountsConcurrently() throws IOException {
        List<String> accounts = List.of(
                "a@example.com", "b@example.com", "c@example.com", "d@example.com", "e@example.com",
                "f@example.com");
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, accounts);
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        ZimbraLdapExporter trackingExporter = new ZimbraLdapExporter() {
            @Override
            public void export(String identifier, LdapObjectType type, OutputStream destination) throws IOException {
                int current = inFlight.incrementAndGet();
                maxInFlight.updateAndGet(prev -> Math.max(prev, current));
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
            }

            @Override
            public void exportDomain(String domain, OutputStream destination) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void restore(LdapObjectType type, InputStream source) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void restoreDomain(InputStream source) {
                throw new UnsupportedOperationException();
            }
        };
        BackupService parallelBackup = BackupService.builder(
                        accountDiscovery, trackingExporter, mailboxExporter, storageProvider, metadataStore)
                .maxParallelProcesses(2)
                .build();

        Optional<BackupSession> result = parallelBackup.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FINISHED, result.get().status());
        assertTrue(maxInFlight.get() <= 2, "observed " + maxInFlight.get() + " concurrent exports, expected at most 2");
        assertEquals(2, maxInFlight.get());
    }

    @Test
    void notifiesBeginAndFinishForASession() throws IOException {
        RecordingNotifier notifier = new RecordingNotifier();
        BackupService notified = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> false)
                .notifier(notifier)
                .maxParallelProcesses(1)
                .build();

        Optional<BackupSession> result = notified.backup(BackupType.LDAP, List.of("alice@example.com"));

        assertEquals(2, notifier.calls.size());
        assertTrue(notifier.calls.get(0).startsWith("begin:"));
        assertEquals(
                "finish:" + result.get().sessionId() + ":LDAP:" + result.get().status() + ":" + result.get().size()
                        + ":1",
                notifier.calls.get(1));
    }

    @Test
    void doesNotNotifyWhenNothingToBackUp() throws IOException {
        RecordingNotifier notifier = new RecordingNotifier();
        BackupService notified = BackupService.builder(
                        accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> false)
                .notifier(notifier)
                .maxParallelProcesses(1)
                .build();

        notified.backup(BackupType.SIGNATURE);

        assertTrue(notifier.calls.isEmpty());
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
    void interruptedRunIsRecordedAsFailedRatherThanLeftInProgress() throws IOException {
        accountDiscovery.wholeDirectory.put(LdapObjectType.ACCOUNT, List.of("alice@example.com"));
        RecordingNotifier notifier = new RecordingNotifier();
        ZimbraLdapExporter interruptingExporter = new ZimbraLdapExporter() {
            @Override
            public void export(String identifier, LdapObjectType type, OutputStream destination) {
                // An unchecked exception escapes backupOne's IOException handling, propagating out
                // of the task and forcing Parallel.run itself to throw - mirroring the interrupted
                // shutdown scenario from the bug report, where Parallel.run throws before the
                // session can be marked FINISHED/FAILED.
                throw new RuntimeException("simulated interruption for " + identifier);
            }

            @Override
            public void exportDomain(String domain, OutputStream destination) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void restore(LdapObjectType type, InputStream source) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void restoreDomain(InputStream source) {
                throw new UnsupportedOperationException();
            }
        };
        BackupService interrupted = BackupService.builder(
                        accountDiscovery, interruptingExporter, mailboxExporter, storageProvider, metadataStore)
                .blocklist(identifier -> false)
                .notifier(notifier)
                .maxParallelProcesses(1)
                .build();

        assertThrows(IOException.class, () -> interrupted.backup(BackupType.LDAP));

        List<BackupSession> sessions = metadataStore.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(SessionStatus.FAILED, sessions.get(0).status());
        assertTrue(notifier.calls.get(notifier.calls.size() - 1).startsWith("finish:"));
        assertTrue(notifier.calls.get(notifier.calls.size() - 1).contains(":FAILED:"));
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

    /**
     * In-memory {@link StorageProvider} fake backed by a byte-array map. Uses a {@link
     * ConcurrentHashMap} since {@link #backupNeverRunsMoreThanMaxParallelProcessesAccountsConcurrently}
     * exercises it from multiple threads at once.
     */
    private static final class InMemoryStorageProvider implements StorageProvider {
        final Map<String, byte[]> content = new java.util.concurrent.ConcurrentHashMap<>();

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

        @Override
        public int deleteEmptyFiles() {
            throw new UnsupportedOperationException();
        }

        private static String key(String sessionId, String account, String suffix) {
            return sessionId + "/" + account + "." + suffix;
        }
    }

    /** In-memory {@link MetadataStore} fake backed by simple maps. */
    /** {@link Notifier} fake that records every call it receives. */
    private static final class RecordingNotifier implements Notifier {
        final List<String> calls = new ArrayList<>();

        @Override
        public void notifyBegin(String sessionId, BackupType type) {
            calls.add("begin:" + sessionId + ":" + type);
        }

        @Override
        public void notifyFinish(
                String sessionId, BackupType type, SessionStatus status, String size, int accountCount) {
            calls.add("finish:" + sessionId + ":" + type + ":" + status + ":" + size + ":" + accountCount);
        }
    }

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
        public int truncate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized void recordAccountBackup(BackupAccountRecord record) {
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

        @Override
        public boolean backedUpSince(String identifier, Instant since) {
            return accounts.values().stream()
                    .flatMap(List::stream)
                    .anyMatch(record -> record.email().equals(identifier)
                            && record.completedAt() != null
                            && record.completedAt().isAfter(since));
        }
    }
}
