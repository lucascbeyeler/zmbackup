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

    private static final Pattern DISCOVERED_EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private static final Pattern DISCOVERED_DOMAIN = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private static final Duration INCREMENTAL_LOOKBACK = Duration.ofHours(48);

    private static final Duration LOCK_BACKUP_WINDOW = Duration.ofHours(24);

    private static final Duration SESSION_ID_RETRY_BUDGET = Duration.ofSeconds(5);

    private final AccountDiscovery accountDiscovery;
    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;
    private final Blocklist blocklist;
    private final Notifier notifier;
    private final int maxParallelProcesses;
    private final boolean lockBackup;

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

    public static Builder builder(
            AccountDiscovery accountDiscovery,
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore) {
        return new Builder(accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore);
    }

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

        public Builder blocklist(Blocklist blocklist) {
            this.blocklist = blocklist;
            return this;
        }

        public Builder notifier(Notifier notifier) {
            this.notifier = notifier;
            return this;
        }

        public Builder maxParallelProcesses(int maxParallelProcesses) {
            this.maxParallelProcesses = maxParallelProcesses;
            return this;
        }

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

    public Optional<BackupSession> backup(BackupType type) throws IOException {
        return backup(type, List.of(), null);
    }

    public Optional<BackupSession> backup(BackupType type, List<String> identifiers) throws IOException {
        return backup(type, identifiers, null);
    }

    public Optional<BackupSession> backup(BackupType type, List<String> identifiers, String domain)
            throws IOException {
        List<String> resolved = resolveIdentifiers(type, identifiers, domain);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        String sessionId = uniqueSessionId(type);
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
        String notifySize = status == SessionStatus.FINISHED ? size : "0";
        int notifyAccountCount =
                status == SessionStatus.FINISHED ? metadataStore.findAccountsForSession(sessionId).size() : 0;
        notifySafely(() -> notifier.notifyFinish(sessionId, type, status, notifySize, notifyAccountCount));
        return Optional.of(completed);
    }

    private String uniqueSessionId(BackupType type) throws IOException {
        Instant deadline = Instant.now().plus(SESSION_ID_RETRY_BUDGET);
        while (true) {
            String sessionId = type.sessionPrefix() + "-" + SESSION_TIMESTAMP.format(Instant.now());
            if (metadataStore.findSession(sessionId).isEmpty()) {
                return sessionId;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IOException(
                        "Could not allocate a unique session ID for " + type + " within "
                                + SESSION_ID_RETRY_BUDGET);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while allocating a unique session ID for " + type, e);
            }
        }
    }

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

    private void notifySafely(NotifierCall call) {
        try {
            call.run();
        } catch (IOException e) {
            LOG.warning(() -> "Failed to send backup notification: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to send backup notification", e);
        }
    }

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
        };
    }
}
