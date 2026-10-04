package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.RestoreResult;
import io.zmbackup.core.domain.TarEntryCounter;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.ServerConfigArchiver;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RestoreService {

    private static final Logger LOG = Logger.getLogger(RestoreService.class.getName());
    private static final String LDIFF_SUFFIX = "ldiff";
    private static final String TGZ_SUFFIX = "tgz";
    private static final String ZIP_SUFFIX = "zip";
    private static final String SERVER_CONFIG_IDENTIFIER = "serverconfig";

    private static final ServerConfigArchiver NO_SERVER_CONFIG_ARCHIVER = new ServerConfigArchiver() {
        @Override
        public void export(OutputStream destination) throws IOException {
            throw new IOException("serverConfig is not configured in zmbackup.yaml");
        }

        @Override
        public List<String> restore(InputStream source) throws IOException {
            throw new IOException("serverConfig is not configured in zmbackup.yaml");
        }
    };

    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;
    private final ServerConfigArchiver serverConfigArchiver;
    private final int maxParallelProcesses;

    public RestoreService(
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore) {
        this(ldapExporter, mailboxExporter, storageProvider, metadataStore, 1);
    }

    public RestoreService(
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore,
            int maxParallelProcesses) {
        this(
                ldapExporter,
                mailboxExporter,
                storageProvider,
                metadataStore,
                maxParallelProcesses,
                NO_SERVER_CONFIG_ARCHIVER);
    }

    public RestoreService(
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore,
            int maxParallelProcesses,
            ServerConfigArchiver serverConfigArchiver) {
        this.ldapExporter = Objects.requireNonNull(ldapExporter, "ldapExporter must not be null");
        this.mailboxExporter = Objects.requireNonNull(mailboxExporter, "mailboxExporter must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
        this.serverConfigArchiver = Objects.requireNonNull(serverConfigArchiver, "serverConfigArchiver must not be null");
        this.maxParallelProcesses = maxParallelProcesses;
    }

    public RestoreResult restoreLdap(String sessionId, List<String> accounts) throws IOException {
        List<String> resolved = resolve(sessionId, accounts);
        List<Callable<Boolean>> tasks = new ArrayList<>(resolved.size());
        for (String account : resolved) {
            tasks.add(() -> restoreLdapOne(sessionId, account));
        }
        return summarize(resolved, Parallel.run(maxParallelProcesses, tasks));
    }

    public RestoreResult restoreDomain(String sessionId, List<String> domains) throws IOException {
        List<String> resolved = resolve(sessionId, domains);
        List<Callable<Boolean>> tasks = new ArrayList<>(resolved.size());
        for (String domain : resolved) {
            tasks.add(() -> restoreDomainOne(sessionId, domain));
        }
        return summarize(resolved, Parallel.run(maxParallelProcesses, tasks));
    }

    public RestoreResult restoreMailbox(String sessionId, List<String> accounts) throws IOException {
        return restoreMailbox(sessionId, accounts, null, false);
    }

    public RestoreResult restoreMailbox(String sessionId, List<String> accounts, String destination)
            throws IOException {
        return restoreMailbox(sessionId, accounts, destination, false);
    }

    /**
     * @param verify if true, counts items in the archive being restored and compares that against
     *     how many items the destination mailbox actually gained (via a REST export before and
     *     after the restore), failing the account if the gain falls short - see issue #409, where
     *     Zimbra's REST restore endpoint can silently drop malformed messages while still reporting
     *     HTTP success. Costs an extra full mailbox export before and after the restore, so it is
     *     opt-in rather than the default. Re-restoring content that is already present in the
     *     destination can under-count (Zimbra's {@code resolve=skip} will not recreate duplicates),
     *     so only trust a verified restore into a destination that did not already hold the content
     *     being restored.
     */
    public RestoreResult restoreMailbox(String sessionId, List<String> accounts, String destination, boolean verify)
            throws IOException {
        if (destination != null && accounts.size() != 1) {
            throw new IllegalArgumentException("destination requires exactly one account, got " + accounts.size());
        }
        List<String> resolved = resolve(sessionId, accounts);
        List<Callable<Boolean>> tasks = new ArrayList<>(resolved.size());
        for (String account : resolved) {
            String target = destination != null ? destination : account;
            tasks.add(() -> restoreMailboxOne(sessionId, account, target, verify));
        }
        return summarize(resolved, Parallel.run(maxParallelProcesses, tasks));
    }

    public RestoreResult restoreServerConfig(String sessionId) throws IOException {
        boolean succeeded = restoreServerConfigOne(sessionId);
        return new RestoreResult(1, succeeded ? List.of() : List.of(SERVER_CONFIG_IDENTIFIER));
    }

    public RestoreResult restoreFull(String sessionId, List<String> accounts) throws IOException {
        return restoreFull(sessionId, accounts, false);
    }

    public RestoreResult restoreFull(String sessionId, List<String> accounts, boolean verify) throws IOException {
        RestoreResult ldapResult = restoreLdap(sessionId, accounts);
        RestoreResult mailboxResult = restoreMailbox(sessionId, accounts, null, verify);
        Set<String> failed = new LinkedHashSet<>(ldapResult.failedAccounts());
        failed.addAll(mailboxResult.failedAccounts());
        return new RestoreResult(ldapResult.total(), List.copyOf(failed));
    }

    private boolean restoreLdapOne(String sessionId, String account) {
        try (InputStream source = storageProvider.openRead(sessionId, account, LDIFF_SUFFIX)) {
            ldapExporter.restore(LdapObjectType.ACCOUNT, source);
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "LDAP restore failed for " + account, e);
            return false;
        }
    }

    private boolean restoreDomainOne(String sessionId, String domain) {
        try (InputStream source = storageProvider.openRead(sessionId, domain, LDIFF_SUFFIX)) {
            ldapExporter.restoreDomain(source);
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Domain restore failed for " + domain, e);
            return false;
        }
    }

    private boolean restoreServerConfigOne(String sessionId) {
        try (InputStream source = storageProvider.openRead(sessionId, SERVER_CONFIG_IDENTIFIER, ZIP_SUFFIX)) {
            List<String> skipped = serverConfigArchiver.restore(source);
            if (!skipped.isEmpty()) {
                LOG.warning(() -> "Server config restore for session " + sessionId + " could not write "
                        + skipped.size() + " file(s) - the running user likely lacks write permission on their"
                        + " containing directory - and left them untouched: " + skipped);
            }
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Server config restore failed for session " + sessionId, e);
            return false;
        }
    }

    private boolean restoreMailboxOne(String sessionId, String account, String destination, boolean verify) {
        try {
            if (!storageProvider.exists(sessionId, account, TGZ_SUFFIX)) {
                return true;
            }
            long expectedEntries = verify ? countSourceEntries(sessionId, account) : 0;
            long baselineEntries = expectedEntries > 0 ? exportAndCountEntries(destination) : 0;
            try (InputStream source = storageProvider.openRead(sessionId, account, TGZ_SUFFIX)) {
                mailboxExporter.restore(destination, source);
            }
            if (expectedEntries > 0) {
                long actualEntries = exportAndCountEntries(destination);
                long gained = actualEntries - baselineEntries;
                if (gained < expectedEntries) {
                    LOG.warning(() -> "Mailbox restore verification failed for " + account + " (destination "
                            + destination + "): the archive being restored contains " + expectedEntries
                            + " item(s), but the mailbox only gained " + gained + " item(s) after restore"
                            + " (before: " + baselineEntries + ", after: " + actualEntries + "). Zimbra's REST"
                            + " import can silently drop malformed messages while still reporting HTTP success"
                            + " (see issue #409). Note: re-restoring content that already exists in the"
                            + " destination will under-count here, since Zimbra's resolve=skip will not recreate"
                            + " duplicates - only trust this check for a restore into a destination that did not"
                            + " already hold the content being restored.");
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Mailbox restore failed for " + account, e);
            return false;
        }
    }

    private long countSourceEntries(String sessionId, String account) throws IOException {
        try (InputStream in = storageProvider.openRead(sessionId, account, TGZ_SUFFIX)) {
            return TarEntryCounter.countNestedFileEntries(in);
        }
    }

    private long exportAndCountEntries(String account) throws IOException {
        Path tempFile = Files.createTempFile("zmbackup-restore-verify-", ".tgz");
        try {
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (!mailboxExporter.export(account, out)) {
                    return 0;
                }
            }
            try (InputStream in = Files.newInputStream(tempFile)) {
                return TarEntryCounter.countNestedFileEntries(in);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static RestoreResult summarize(List<String> resolved, List<Boolean> outcomes) {
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < resolved.size(); i++) {
            if (!outcomes.get(i)) {
                failed.add(resolved.get(i));
            }
        }
        return new RestoreResult(resolved.size(), failed);
    }

    private List<String> resolve(String sessionId, List<String> identifiers) throws IOException {
        if (!identifiers.isEmpty()) {
            return identifiers;
        }
        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(sessionId);
        List<String> resolved = new ArrayList<>(records.size());
        for (BackupAccountRecord record : records) {
            resolved.add(record.email());
        }
        return resolved;
    }
}
