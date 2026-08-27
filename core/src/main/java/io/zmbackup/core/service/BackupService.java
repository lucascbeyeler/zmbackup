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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs backup sessions for the LDAP-only {@link BackupType}s ({@code LDAP}, {@code ALIAS}, {@code
 * DISTRIBUTION_LIST}, {@code SIGNATURE}, {@code DOMAIN}) as well as {@code MAILBOX}, mirroring
 * {@code backup_main} and its {@code __backupLdap}/{@code __backupDomain}/{@code __backupMailbox}
 * helpers in the bash tool's {@code BackupAction.sh}. The combined LDAP+mailbox types ({@code
 * FULL}, {@code INCREMENTAL}) are handled by a separate service.
 */
public class BackupService {

    private static final DateTimeFormatter SESSION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());
    private static final String LDIFF_SUFFIX = "ldiff";
    private static final String TGZ_SUFFIX = "tgz";

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
     *                    DISTRIBUTION_LIST}, {@code SIGNATURE}, or {@code DOMAIN}), or {@code
     *                    MAILBOX}
     * @param identifiers explicit accounts (or domains, for {@code DOMAIN}) to back up, bypassing
     *                    discovery; empty to discover automatically
     * @param domain      when {@code identifiers} is empty and {@code type != DOMAIN}, restricts
     *                    discovery to this domain (e.g. {@code "example.com"}); {@code null} to
     *                    search the whole directory
     * @return the completed session, or empty if there was nothing to back up
     */
    public Optional<BackupSession> backup(BackupType type, List<String> identifiers, String domain)
            throws IOException {
        if (type.includesLdap() && type.includesMailbox()) {
            throw new IllegalArgumentException(
                    "BackupService does not support combined LDAP+mailbox backup types, got " + type);
        }

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

    /** Exports and stores a single object, recording its result. Returns {@code false} on failure. */
    private boolean backupOne(String sessionId, BackupType type, String identifier) throws IOException {
        Instant startedAt = Instant.now();
        try {
            if (type == BackupType.MAILBOX) {
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
            case LDAP, MAILBOX -> LdapObjectType.ACCOUNT;
            case ALIAS -> LdapObjectType.ALIAS;
            case DISTRIBUTION_LIST -> LdapObjectType.DISTRIBUTION_LIST;
            case SIGNATURE -> LdapObjectType.SIGNATURE;
            case DOMAIN -> LdapObjectType.DOMAIN;
            default -> throw new IllegalArgumentException("No LdapObjectType mapping for " + type);
        };
    }
}
