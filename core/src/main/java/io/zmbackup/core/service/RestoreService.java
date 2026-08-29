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

/**
 * Restores previously backed-up LDAP entries and mailbox content, mirroring {@code
 * restore_main_ldap}/{@code restore_main_mailbox}/{@code restore_main_domain} in the bash tool's
 * {@code RestoreAction.sh}.
 */
public class RestoreService {

    private static final String LDIFF_SUFFIX = "ldiff";
    private static final String TGZ_SUFFIX = "tgz";

    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public RestoreService(
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore) {
        this.ldapExporter = Objects.requireNonNull(ldapExporter, "ldapExporter must not be null");
        this.mailboxExporter = Objects.requireNonNull(mailboxExporter, "mailboxExporter must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    /**
     * Restores the LDAP entry for each of {@code accounts} (or every account in {@code sessionId}
     * when {@code accounts} is empty), mirroring {@code restore_main_ldap}.
     */
    public RestoreResult restoreLdap(String sessionId, List<String> accounts) throws IOException {
        List<String> resolved = resolve(sessionId, accounts);
        List<String> failed = new ArrayList<>();
        for (String account : resolved) {
            if (!restoreLdapOne(sessionId, account)) {
                failed.add(account);
            }
        }
        return new RestoreResult(resolved.size(), failed);
    }

    /**
     * Restores the domain LDAP entry for each of {@code domains} (or every domain in {@code
     * sessionId} when {@code domains} is empty), mirroring {@code restore_main_domain}.
     */
    public RestoreResult restoreDomain(String sessionId, List<String> domains) throws IOException {
        List<String> resolved = resolve(sessionId, domains);
        List<String> failed = new ArrayList<>();
        for (String domain : resolved) {
            if (!restoreDomainOne(sessionId, domain)) {
                failed.add(domain);
            }
        }
        return new RestoreResult(resolved.size(), failed);
    }

    /**
     * Restores the mailbox content for each of {@code accounts} (or every account in {@code
     * sessionId} when {@code accounts} is empty), mirroring {@code restore_main_mailbox}.
     */
    public RestoreResult restoreMailbox(String sessionId, List<String> accounts) throws IOException {
        List<String> resolved = resolve(sessionId, accounts);
        List<String> failed = new ArrayList<>();
        for (String account : resolved) {
            if (!restoreMailboxOne(sessionId, account, account)) {
                failed.add(account);
            }
        }
        return new RestoreResult(resolved.size(), failed);
    }

    /**
     * Restores both the LDAP entry and mailbox content for each of {@code accounts} (or every
     * account in {@code sessionId} when {@code accounts} is empty), equivalent to {@code zmbackup
     * -r full-*} in the bash tool.
     */
    public RestoreResult restoreFull(String sessionId, List<String> accounts) throws IOException {
        RestoreResult ldapResult = restoreLdap(sessionId, accounts);
        RestoreResult mailboxResult = restoreMailbox(sessionId, accounts);
        Set<String> failed = new LinkedHashSet<>(ldapResult.failedAccounts());
        failed.addAll(mailboxResult.failedAccounts());
        return new RestoreResult(ldapResult.total(), List.copyOf(failed));
    }

    private boolean restoreLdapOne(String sessionId, String account) {
        try (InputStream source = storageProvider.openRead(sessionId, account, LDIFF_SUFFIX)) {
            // The adapter reads the DN straight out of the LDIF content, so the object type
            // passed here has no effect on account/alias/distlist/signature restores.
            ldapExporter.restore(LdapObjectType.ACCOUNT, source);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean restoreDomainOne(String sessionId, String domain) {
        try (InputStream source = storageProvider.openRead(sessionId, domain, LDIFF_SUFFIX)) {
            ldapExporter.restoreDomain(source);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean restoreMailboxOne(String sessionId, String account, String destination) {
        if (!storageProvider.exists(sessionId, account, TGZ_SUFFIX)) {
            // Mirrors mailbox_restore's "No such file or directory" case: nothing to restore, not
            // a failure.
            return true;
        }
        try (InputStream source = storageProvider.openRead(sessionId, account, TGZ_SUFFIX)) {
            mailboxExporter.restore(destination, source);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code identifiers} when non-empty; otherwise every account (or domain) recorded for {@code
     * sessionId}, mirroring {@code build_listRST}'s SQLite-backed session lookup.
     */
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
