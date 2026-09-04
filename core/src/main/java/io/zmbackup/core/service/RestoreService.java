package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.RestoreResult;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.InputStream;
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

    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;
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
        this.ldapExporter = Objects.requireNonNull(ldapExporter, "ldapExporter must not be null");
        this.mailboxExporter = Objects.requireNonNull(mailboxExporter, "mailboxExporter must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
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
        return restoreMailbox(sessionId, accounts, null);
    }

    public RestoreResult restoreMailbox(String sessionId, List<String> accounts, String destination)
            throws IOException {
        if (destination != null && accounts.size() != 1) {
            throw new IllegalArgumentException("destination requires exactly one account, got " + accounts.size());
        }
        List<String> resolved = resolve(sessionId, accounts);
        List<Callable<Boolean>> tasks = new ArrayList<>(resolved.size());
        for (String account : resolved) {
            tasks.add(() -> restoreMailboxOne(sessionId, account, destination != null ? destination : account));
        }
        return summarize(resolved, Parallel.run(maxParallelProcesses, tasks));
    }

    public RestoreResult restoreFull(String sessionId, List<String> accounts) throws IOException {
        RestoreResult ldapResult = restoreLdap(sessionId, accounts);
        RestoreResult mailboxResult = restoreMailbox(sessionId, accounts);
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

    private boolean restoreMailboxOne(String sessionId, String account, String destination) {
        if (!storageProvider.exists(sessionId, account, TGZ_SUFFIX)) {
            return true;
        }
        try (InputStream source = storageProvider.openRead(sessionId, account, TGZ_SUFFIX)) {
            mailboxExporter.restore(destination, source);
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Mailbox restore failed for " + account, e);
            return false;
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
