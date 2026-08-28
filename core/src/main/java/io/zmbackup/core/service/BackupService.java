package io.zmbackup.core.service;

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
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs backup sessions for the LDAP-only {@link BackupType}s ({@code LDAP}, {@code ALIAS}, {@code
 * DISTRIBUTION_LIST}, {@code SIGNATURE}, {@code DOMAIN}), {@code MAILBOX}, {@code FULL}, and
 * {@code INCREMENTAL}, mirroring {@code backup_main} and its {@code __backupLdap}/{@code
 * __backupDomain}/{@code __backupMailbox}/{@code __backupFullInc} helpers in the bash tool's
 * {@code BackupAction.sh}.
 */
public class BackupService {

    private static final DateTimeFormatter SESSION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());
    private static final String LDIFF_SUFFIX = "ldiff";
    private static final String TGZ_SUFFIX = "tgz";

    /**
     * Mirrors the bash tool's {@code YESTERDAY} variable: how far back of the last successful
     * backup an incremental export's {@code after} cutoff is set.
     */
    private static final Duration INCREMENTAL_LOOKBACK = Duration.ofHours(48);

    private final AccountDiscovery accountDiscovery;
    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public BackupService(
            AccountDiscovery accountDiscovery,
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore) {
        this.accountDiscovery = Objects.requireNonNull(accountDiscovery, "accountDiscovery must not be null");
        this.ldapExporter = Objects.requireNonNull(ldapExporter, "ldapExporter must not be null");
        this.mailboxExporter = Objects.requireNonNull(mailboxExporter, "mailboxExporter must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    /** Backs up every object of {@code type} found across the whole directory. */
    public Optional<BackupSession> backup(BackupType type) throws IOException {
        return backup(type, List.of(), null);
    }

    /** Backs up only the given {@code identifiers} (accounts, or domains for {@link BackupType#DOMAIN}). */
    public Optional<BackupSession> backup(BackupType type, List<String> identifiers) throws IOException {
        return backup(type, identifiers, null);
    }

    /**
     * Runs one backup session of {@code type}, mirroring {@code backup_main}: resolves the
     * objects to back up, records an {@code IN PROGRESS} session, exports and stores each object,
     * then updates the session to its final status.
     *
     * @param type        an LDAP-only backup type ({@code LDAP}, {@code ALIAS}, {@code
     *                    DISTRIBUTION_LIST}, {@code SIGNATURE}, or {@code DOMAIN}), {@code
     *                    MAILBOX}, {@code FULL}, or {@code INCREMENTAL}
     * @param identifiers explicit accounts (or domains, for {@code DOMAIN}) to back up, bypassing
     *                    discovery; empty to discover automatically
     * @param domain      when {@code identifiers} is empty and {@code type != DOMAIN}, restricts
     *                    discovery to this domain (e.g. {@code "example.com"}); {@code null} to
     *                    search the whole directory
     * @return the completed session, or empty if there was nothing to back up
     */
    public Optional<BackupSession> backup(BackupType type, List<String> identifiers, String domain)
            throws IOException {
        List<String> resolved = resolveIdentifiers(type, identifiers, domain);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        String sessionId = type.sessionPrefix() + "-" + SESSION_TIMESTAMP.format(Instant.now());
        Instant sessionStart = Instant.now();
        metadataStore.save(new BackupSession(sessionId, type, SessionStatus.IN_PROGRESS, sessionStart, null, null));

        boolean allSucceeded = true;
        for (String identifier : resolved) {
            if (!backupOne(sessionId, type, identifier)) {
                allSucceeded = false;
            }
        }

        Instant sessionEnd = Instant.now();
        String size = storageProvider.sizeOfSession(sessionId);
        SessionStatus status = allSucceeded ? SessionStatus.FINISHED : SessionStatus.FAILED;
        BackupSession completed = new BackupSession(sessionId, type, status, sessionStart, sessionEnd, size);
        metadataStore.save(completed);
        return Optional.of(completed);
    }

    /**
     * Exports and stores a single object, recording its result. Returns {@code false} on failure.
     * For {@code FULL}, mirrors {@code __backupFullInc}: exports LDAP first, then skips the
     * mailbox export (and the account record) entirely if the LDAP export failed. For {@code
     * INCREMENTAL}, exports mailbox content after {@link #incrementalCutoff(String)}.
     */
    private boolean backupOne(String sessionId, BackupType type, String identifier) throws IOException {
        Instant startedAt = Instant.now();
        try {
            if (type == BackupType.MAILBOX) {
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, TGZ_SUFFIX)) {
                    mailboxExporter.export(identifier, destination);
                }
            } else if (type == BackupType.INCREMENTAL) {
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, TGZ_SUFFIX)) {
                    mailboxExporter.export(identifier, destination, incrementalCutoff(identifier));
                }
            } else if (type == BackupType.FULL) {
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, LDIFF_SUFFIX)) {
                    ldapExporter.export(identifier, LdapObjectType.ACCOUNT, destination);
                }
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, TGZ_SUFFIX)) {
                    mailboxExporter.export(identifier, destination);
                }
            } else {
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, LDIFF_SUFFIX)) {
                    if (type == BackupType.DOMAIN) {
                        ldapExporter.exportDomain(identifier, destination);
                    } else {
                        ldapExporter.export(identifier, objectTypeFor(type), destination);
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
        Instant completedAt = Instant.now();
        String size = storageProvider.sizeOfAccount(sessionId, identifier);
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, sessionId, identifier, size, startedAt, completedAt));
        return true;
    }

    /**
     * The {@code after} cutoff for an incremental export of {@code email}: {@link
     * #INCREMENTAL_LOOKBACK} before its last successful backup, mirroring the bash tool's {@code
     * YESTERDAY} variable. Returns {@code null} (falling back to a full export) when {@code email}
     * has no prior successful backup.
     */
    private Instant incrementalCutoff(String email) throws IOException {
        return metadataStore
                .lastSuccessfulBackupTime(email)
                .map(lastBackup -> lastBackup.minus(INCREMENTAL_LOOKBACK))
                .orElse(null);
    }

    private List<String> resolveIdentifiers(BackupType type, List<String> identifiers, String domain)
            throws IOException {
        if (!identifiers.isEmpty()) {
            return identifiers;
        }
        if (type == BackupType.DOMAIN) {
            return accountDiscovery.listDomains();
        }
        LdapObjectType objectType = objectTypeFor(type);
        return domain == null
                ? accountDiscovery.discover(objectType)
                : accountDiscovery.discoverForDomain(objectType, domain);
    }

    private static LdapObjectType objectTypeFor(BackupType type) {
        return switch (type) {
            case LDAP, MAILBOX, FULL, INCREMENTAL -> LdapObjectType.ACCOUNT;
            case ALIAS -> LdapObjectType.ALIAS;
            case DISTRIBUTION_LIST -> LdapObjectType.DISTRIBUTION_LIST;
            case SIGNATURE -> LdapObjectType.SIGNATURE;
            case DOMAIN -> LdapObjectType.DOMAIN;
            default -> throw new IllegalArgumentException("No LdapObjectType mapping for " + type);
        };
    }
}
