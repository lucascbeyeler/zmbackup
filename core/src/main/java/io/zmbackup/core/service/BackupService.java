package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.Blocklist;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.Notifier;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Runs backup sessions for the LDAP-only {@link BackupType}s ({@code LDAP}, {@code ALIAS}, {@code
 * DISTRIBUTION_LIST}, {@code SIGNATURE}, {@code DOMAIN}), {@code MAILBOX}, {@code FULL}, and
 * {@code INCREMENTAL}, mirroring {@code backup_main} and its {@code __backupLdap}/{@code
 * __backupDomain}/{@code __backupMailbox}/{@code __backupFullInc} helpers in the bash tool's
 * {@code BackupAction.sh}.
 */
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class.getName());
    private static final Blocklist NO_BLOCKLIST = identifier -> false;
    private static final Notifier NO_NOTIFIER = new Notifier() {
        @Override
        public void notifyBegin(String sessionId, BackupType type) {}

        @Override
        public void notifyFinish(
                String sessionId, BackupType type, SessionStatus status, String size, int accountCount) {}
    };

    private static final DateTimeFormatter SESSION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());
    private static final String LDIFF_SUFFIX = "ldiff";
    private static final String TGZ_SUFFIX = "tgz";

    /**
     * Format guards applied to LDAP-discovered identifiers before they reach a storage path, LDAP
     * base DN, or REST URL - mirroring the CLI layer's own email/domain checks (the bash tool's
     * {@code validate_email}/{@code validate_domain}), which only cover explicit {@code --account}/
     * {@code --domain} arguments and never see identifiers found via directory discovery. {@link
     * LdapObjectType#SIGNATURE} is exempt: its identifying attribute is a free-text signature name,
     * not an email address.
     */
    private static final Pattern DISCOVERED_EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private static final Pattern DISCOVERED_DOMAIN = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    /**
     * Mirrors the bash tool's {@code YESTERDAY} variable: how far back of the last successful
     * backup an incremental export's {@code after} cutoff is set.
     */
    private static final Duration INCREMENTAL_LOOKBACK = Duration.ofHours(48);

    /**
     * How far back {@code lockBackup} looks for a prior backup of a discovered identifier,
     * mirroring {@code ldap_filter}'s {@code YESTERDAY} cutoff.
     */
    private static final Duration LOCK_BACKUP_WINDOW = Duration.ofHours(24);

    private final AccountDiscovery accountDiscovery;
    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;
    private final Blocklist blocklist;
    private final Notifier notifier;
    private final int maxParallelProcesses;
    private final boolean lockBackup;

    /**
     * @param blocklist            discovered accounts (or domains) found here are skipped rather
     *                             than backed up, mirroring {@code ldap_filter}'s blocklist check
     * @param notifier             notified when a session starts and finishes, mirroring {@code
     *                             notify_begin}/{@code notify_finish}
     * @param maxParallelProcesses how many accounts to back up concurrently, mirroring the bash
     *                             tool's {@code MAX_PARALLEL_PROCESS}; values below 1 are treated
     *                             as 1
     * @param lockBackup           when true, discovered identifiers (not explicit {@code
     *                             --account} lists) with an account-level backup completed within
     *                             the last 24 hours are skipped, mirroring {@code ldap_filter}'s
     *                             {@code LOCK_BACKUP} dedup check
     */
    private BackupService(
            AccountDiscovery accountDiscovery,
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore,
            Blocklist blocklist,
            Notifier notifier,
            int maxParallelProcesses,
            boolean lockBackup) {
        this.accountDiscovery = Objects.requireNonNull(accountDiscovery, "accountDiscovery must not be null");
        this.ldapExporter = Objects.requireNonNull(ldapExporter, "ldapExporter must not be null");
        this.mailboxExporter = Objects.requireNonNull(mailboxExporter, "mailboxExporter must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
        this.blocklist = Objects.requireNonNull(blocklist, "blocklist must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.maxParallelProcesses = maxParallelProcesses;
        this.lockBackup = lockBackup;
    }

    /**
     * Starts building a {@link BackupService} from its required collaborators, with {@link
     * Builder#blocklist}, {@link Builder#notifier}, {@link Builder#maxParallelProcesses}, and
     * {@link Builder#lockBackup} all optional and defaulted - replacing what used to be five
     * telescoping constructors covering every combination of those four optional settings.
     */
    public static Builder builder(
            AccountDiscovery accountDiscovery,
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore) {
        return new Builder(accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore);
    }

    /** Builds a {@link BackupService}; see {@link BackupService#builder} for how to obtain one. */
    public static final class Builder {
        private final AccountDiscovery accountDiscovery;
        private final ZimbraLdapExporter ldapExporter;
        private final ZimbraMailboxExporter mailboxExporter;
        private final StorageProvider storageProvider;
        private final MetadataStore metadataStore;
        private Blocklist blocklist = NO_BLOCKLIST;
        private Notifier notifier = NO_NOTIFIER;
        private int maxParallelProcesses = 1;
        private boolean lockBackup = false;

        private Builder(
                AccountDiscovery accountDiscovery,
                ZimbraLdapExporter ldapExporter,
                ZimbraMailboxExporter mailboxExporter,
                StorageProvider storageProvider,
                MetadataStore metadataStore) {
            this.accountDiscovery = accountDiscovery;
            this.ldapExporter = ldapExporter;
            this.mailboxExporter = mailboxExporter;
            this.storageProvider = storageProvider;
            this.metadataStore = metadataStore;
        }

        /**
         * Discovered accounts (or domains) found here are skipped rather than backed up,
         * mirroring {@code ldap_filter}'s blocklist check. Defaults to backing up everything.
         */
        public Builder blocklist(Blocklist blocklist) {
            this.blocklist = blocklist;
            return this;
        }

        /**
         * Notified when a session starts and finishes, mirroring {@code notify_begin}/{@code
         * notify_finish}. Defaults to sending no notifications.
         */
        public Builder notifier(Notifier notifier) {
            this.notifier = notifier;
            return this;
        }

        /**
         * How many accounts to back up concurrently, mirroring the bash tool's {@code
         * MAX_PARALLEL_PROCESS}; values below 1 are treated as 1. Defaults to 1 (sequential).
         */
        public Builder maxParallelProcesses(int maxParallelProcesses) {
            this.maxParallelProcesses = maxParallelProcesses;
            return this;
        }

        /**
         * When true, discovered identifiers (not explicit {@code --account} lists) with an
         * account-level backup completed within the last 24 hours are skipped, mirroring {@code
         * ldap_filter}'s {@code LOCK_BACKUP} dedup check. Defaults to false.
         */
        public Builder lockBackup(boolean lockBackup) {
            this.lockBackup = lockBackup;
            return this;
        }

        public BackupService build() {
            return new BackupService(
                    accountDiscovery,
                    ldapExporter,
                    mailboxExporter,
                    storageProvider,
                    metadataStore,
                    blocklist,
                    notifier,
                    maxParallelProcesses,
                    lockBackup);
        }
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
        notifySafely(() -> notifier.notifyBegin(sessionId, type));

        List<Callable<Boolean>> tasks = new ArrayList<>(resolved.size());
        for (String identifier : resolved) {
            tasks.add(() -> backupOne(sessionId, type, identifier));
        }
        boolean allSucceeded;
        try {
            allSucceeded = !Parallel.run(maxParallelProcesses, tasks).contains(false);
        } catch (IOException e) {
            recordFailedSession(sessionId, type, sessionStart);
            throw e;
        }

        Instant sessionEnd = Instant.now();
        String size = storageProvider.sizeOfSession(sessionId);
        SessionStatus status = allSucceeded ? SessionStatus.FINISHED : SessionStatus.FAILED;
        BackupSession completed = new BackupSession(sessionId, type, status, sessionStart, sessionEnd, size);
        metadataStore.save(completed);
        // Mirrors notify_finish's SIZE=0/QTDE=0 on a failed session: only report real numbers on success.
        String notifySize = status == SessionStatus.FINISHED ? size : "0";
        int notifyAccountCount =
                status == SessionStatus.FINISHED ? metadataStore.findAccountsForSession(sessionId).size() : 0;
        notifySafely(() -> notifier.notifyFinish(sessionId, type, status, notifySize, notifyAccountCount));
        return Optional.of(completed);
    }

    /**
     * Records {@code sessionId} as {@code FAILED} when {@link Parallel#run} itself threw (e.g. the
     * calling thread was interrupted by SIGTERM/shutdown) rather than any individual task failing,
     * so the session is never left orphaned as {@code IN_PROGRESS} forever.
     */
    private void recordFailedSession(String sessionId, BackupType type, Instant sessionStart) throws IOException {
        Instant sessionEnd = Instant.now();
        String size = storageProvider.sizeOfSession(sessionId);
        BackupSession failed = new BackupSession(sessionId, type, SessionStatus.FAILED, sessionStart, sessionEnd, size);
        metadataStore.save(failed);
        notifySafely(() -> notifier.notifyFinish(sessionId, type, SessionStatus.FAILED, "0", 0));
    }

    private interface NotifierCall {
        void run() throws IOException;
    }

    /**
     * Runs a {@link Notifier} call, logging rather than propagating a failure: a notification
     * problem must never fail the backup itself, mirroring how the bash tool's {@code
     * notify_begin}/{@code notify_finish} only log a warning when {@code sendmail} fails.
     */
    private void notifySafely(NotifierCall call) {
        try {
            call.run();
        } catch (IOException e) {
            LOG.warning(() -> "Failed to send backup notification: " + e.getMessage());
        }
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
            LOG.log(Level.WARNING, "Backup failed for " + identifier, e);
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

    /**
     * Resolves the objects to back up. Explicit {@code identifiers} are used as-is, bypassing the
     * blocklist and {@code lockBackup} dedup, mirroring {@code backup_main}'s {@code -a}/{@code
     * --account} path (which bypasses {@code build_listBKP} entirely); discovered objects are
     * filtered against the blocklist and, when {@link #lockBackup} is enabled, against identifiers
     * already backed up today - same as {@code build_listBKP} always does via {@code ldap_filter}.
     */
    private List<String> resolveIdentifiers(BackupType type, List<String> identifiers, String domain)
            throws IOException {
        if (!identifiers.isEmpty()) {
            return identifiers;
        }
        List<String> discovered;
        LdapObjectType objectType;
        if (type == BackupType.DOMAIN) {
            objectType = LdapObjectType.DOMAIN;
            discovered = accountDiscovery.listDomains();
        } else {
            objectType = objectTypeFor(type);
            discovered = domain == null
                    ? accountDiscovery.discover(objectType)
                    : accountDiscovery.discoverForDomain(objectType, domain);
        }
        return filterAlreadyBackedUpToday(filterBlocked(filterMalformed(objectType, discovered)));
    }

    /**
     * Drops discovered identifiers that don't match the expected email/domain shape (see {@link
     * #DISCOVERED_EMAIL}/{@link #DISCOVERED_DOMAIN}) before they reach a storage path, LDAP base
     * DN, or REST URL - a malformed or hostile LDAP attribute value should never get that far. A
     * no-op for {@link LdapObjectType#SIGNATURE}, whose identifier is a free-text name.
     */
    private List<String> filterMalformed(LdapObjectType objectType, List<String> identifiers) {
        if (objectType == LdapObjectType.SIGNATURE) {
            return identifiers;
        }
        Pattern pattern = objectType == LdapObjectType.DOMAIN ? DISCOVERED_DOMAIN : DISCOVERED_EMAIL;
        List<String> allowed = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            if (pattern.matcher(identifier).matches()) {
                allowed.add(identifier);
            } else {
                LOG.warning(() -> "Discovered identifier has an unexpected format - skipping: " + identifier);
            }
        }
        return allowed;
    }

    private List<String> filterBlocked(List<String> identifiers) {
        List<String> allowed = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            if (blocklist.isBlocked(identifier)) {
                LOG.info(() -> identifier + " found inside blocked list - skipping.");
            } else {
                allowed.add(identifier);
            }
        }
        return allowed;
    }

    /**
     * When {@link #lockBackup} is enabled, drops identifiers already backed up within {@link
     * #LOCK_BACKUP_WINDOW}, mirroring {@code ldap_filter}'s {@code LOCK_BACKUP} check. A no-op
     * when {@link #lockBackup} is disabled.
     */
    private List<String> filterAlreadyBackedUpToday(List<String> identifiers) throws IOException {
        if (!lockBackup) {
            return identifiers;
        }
        Instant since = Instant.now().minus(LOCK_BACKUP_WINDOW);
        List<String> allowed = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            if (metadataStore.backedUpSince(identifier, since)) {
                LOG.info(() -> identifier + " already has backup today - skipping.");
            } else {
                allowed.add(identifier);
            }
        }
        return allowed;
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
