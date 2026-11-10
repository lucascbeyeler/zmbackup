package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.Compression;
import io.zmbackup.core.domain.CompressionSettings;
import io.zmbackup.core.domain.HumanReadableSize;
import io.zmbackup.core.domain.Identifiers;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.Blocklist;
import io.zmbackup.core.port.Compressor;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.Notifier;
import io.zmbackup.core.port.ServerConfigArchiver;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private static final Compressor NO_COMPRESSOR = new Compressor() {
        @Override
        public OutputStream compress(OutputStream destination, String entryName, CompressionSettings settings)
                throws IOException {
            if (settings.algorithm() != Compression.NONE) {
                throw new IOException("No compressor is configured for " + settings.algorithm().configName());
            }
            return destination;
        }

        @Override
        public InputStream decompress(InputStream source, Compression algorithm) throws IOException {
            if (algorithm != Compression.NONE) {
                throw new IOException("No compressor is configured for " + algorithm.configName());
            }
            return source;
        }
    };

    private static final DateTimeFormatter SESSION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());
    private static final String LDIFF_SUFFIX = "ldiff";
    private static final String TAR_ENTRY_SUFFIX = ".tar";
    private static final String ZIP_SUFFIX = "zip";
    private static final String SQLITE_SUFFIX = "sqlite3";
    private static final String SERVER_CONFIG_IDENTIFIER = "serverconfig";
    private static final String SELF_IDENTIFIER = "self";

    private static final Duration INCREMENTAL_LOOKBACK = Duration.ofHours(48);

    private static final Duration SESSION_ID_RETRY_BUDGET = Duration.ofSeconds(5);

    private final AccountDiscovery accountDiscovery;
    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;
    private final Blocklist blocklist;
    private final Notifier notifier;
    private final ServerConfigArchiver serverConfigArchiver;
    private final Compressor compressor;
    private final CompressionSettings compression;
    private final int maxParallelProcesses;
    private final boolean lockBackup;
    private final Clock clock;

    private BackupService(
            AccountDiscovery accountDiscovery,
            ZimbraLdapExporter ldapExporter,
            ZimbraMailboxExporter mailboxExporter,
            StorageProvider storageProvider,
            MetadataStore metadataStore,
            Blocklist blocklist,
            Notifier notifier,
            ServerConfigArchiver serverConfigArchiver,
            Compressor compressor,
            CompressionSettings compression,
            int maxParallelProcesses,
            boolean lockBackup,
            Clock clock) {
        this.accountDiscovery = Objects.requireNonNull(accountDiscovery, "accountDiscovery must not be null");
        this.ldapExporter = Objects.requireNonNull(ldapExporter, "ldapExporter must not be null");
        this.mailboxExporter = Objects.requireNonNull(mailboxExporter, "mailboxExporter must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
        this.blocklist = Objects.requireNonNull(blocklist, "blocklist must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.serverConfigArchiver = Objects.requireNonNull(serverConfigArchiver, "serverConfigArchiver must not be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor must not be null");
        this.compression = Objects.requireNonNull(compression, "compression must not be null");
        this.maxParallelProcesses = maxParallelProcesses;
        this.lockBackup = lockBackup;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
        private ServerConfigArchiver serverConfigArchiver = NO_SERVER_CONFIG_ARCHIVER;
        private Compressor compressor = NO_COMPRESSOR;
        private CompressionSettings compression = CompressionSettings.of(Compression.NONE);
        private int maxParallelProcesses = 1;
        private boolean lockBackup = false;
        private Clock clock = Clock.systemDefaultZone();

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

        public Builder serverConfigArchiver(ServerConfigArchiver serverConfigArchiver) {
            this.serverConfigArchiver = serverConfigArchiver;
            return this;
        }

        public Builder compression(Compressor compressor, CompressionSettings compression) {
            this.compressor = compressor;
            this.compression = compression;
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

        public Builder clock(Clock clock) {
            this.clock = clock;
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
                    serverConfigArchiver,
                    compressor,
                    compression,
                    maxParallelProcesses,
                    lockBackup,
                    clock);
        }
    }

    public Optional<BackupSession> backup(BackupType type) throws IOException {
        return backup(type, List.of(), List.of(), false);
    }

    public Optional<BackupSession> backup(BackupType type, List<String> identifiers) throws IOException {
        return backup(type, identifiers, List.of(), false);
    }

    public Optional<BackupSession> backup(BackupType type, List<String> identifiers, List<String> domains)
            throws IOException {
        return backup(type, identifiers, domains, false);
    }

    public Optional<BackupSession> backup(
            BackupType type, List<String> identifiers, List<String> domains, boolean force) throws IOException {
        return backup(type, identifiers, domains, force, compression);
    }

    public CompressionSettings compression() {
        return compression;
    }

    public Optional<BackupSession> backup(
            BackupType type,
            List<String> identifiers,
            List<String> domains,
            boolean force,
            CompressionSettings compression)
            throws IOException {
        Objects.requireNonNull(compression, "compression must not be null");
        List<String> resolved = resolveIdentifiers(type, identifiers, domains, force);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        String sessionId = uniqueSessionId(type);
        Instant sessionStart = Instant.now();
        metadataStore.save(new BackupSession(sessionId, type, SessionStatus.IN_PROGRESS, sessionStart, null, null));
        notifySafely(() -> notifier.notifyBegin(sessionId, type));

        List<Callable<Boolean>> tasks = new ArrayList<>(resolved.size());
        for (String identifier : resolved) {
            tasks.add(() -> backupOne(sessionId, type, identifier, compression));
        }
        boolean allSucceeded;
        try {
            allSucceeded = !Parallel.run(maxParallelProcesses, tasks).contains(false);
        } catch (IOException e) {
            recordFailedSession(sessionId, type, sessionStart);
            throw e;
        }

        Instant sessionEnd = Instant.now();
        long size = storageProvider.sizeOfSession(sessionId);
        SessionStatus status = allSucceeded ? SessionStatus.FINISHED : SessionStatus.FAILED;
        BackupSession completed = new BackupSession(sessionId, type, status, sessionStart, sessionEnd, size);
        metadataStore.save(completed);
        String notifySize = status == SessionStatus.FINISHED ? HumanReadableSize.format(size) : "0B";
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
        long size = storageProvider.sizeOfSession(sessionId);
        BackupSession failed = new BackupSession(sessionId, type, SessionStatus.FAILED, sessionStart, sessionEnd, size);
        metadataStore.save(failed);
        notifySafely(() -> notifier.notifyFinish(sessionId, type, SessionStatus.FAILED, "0B", 0));
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

    private void logIfNothingExported(String identifier, boolean exported) {
        if (!exported) {
            LOG.info(() -> "No new mailbox content to export for " + identifier);
        }
    }

    private boolean backupOne(
            String sessionId, BackupType type, String identifier, CompressionSettings compression)
            throws IOException {
        Instant startedAt = Instant.now();
        try {
            if (type == BackupType.MAILBOX) {
                exportMailbox(sessionId, identifier, null, compression);
            } else if (type == BackupType.INCREMENTAL) {
                Optional<Instant> lastBackup = metadataStore.lastSuccessfulBackupTime(identifier);
                if (lastBackup.isEmpty()) {
                    LOG.info(() -> "No prior successful backup found for " + identifier
                            + " - running a full backup instead of an incremental one.");
                    fullExport(sessionId, identifier, compression);
                } else {
                    exportMailbox(sessionId, identifier, lastBackup.get().minus(INCREMENTAL_LOOKBACK), compression);
                }
            } else if (type == BackupType.FULL) {
                fullExport(sessionId, identifier, compression);
            } else if (type == BackupType.SERVER_CONFIG) {
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, ZIP_SUFFIX)) {
                    serverConfigArchiver.export(destination);
                }
            } else if (type == BackupType.SELF) {
                try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, SQLITE_SUFFIX)) {
                    metadataStore.exportSelfBackup(destination);
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
        long size = storageProvider.sizeOfAccount(sessionId, identifier);
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, sessionId, identifier, size, startedAt, completedAt));
        return true;
    }

    private void fullExport(String sessionId, String identifier, CompressionSettings compression)
            throws IOException {
        try (OutputStream destination = storageProvider.openWrite(sessionId, identifier, LDIFF_SUFFIX)) {
            ldapExporter.export(identifier, LdapObjectType.ACCOUNT, destination);
        }
        exportMailbox(sessionId, identifier, null, compression);
    }

    private void exportMailbox(String sessionId, String identifier, Instant since, CompressionSettings compression)
            throws IOException {
        boolean exported;
        try (OutputStream destination = new LazyCompressingOutputStream(
                storageProvider.openWrite(sessionId, identifier, compression.algorithm().archiveSuffix()),
                stored -> compressor.compress(stored, identifier + TAR_ENTRY_SUFFIX, compression))) {
            exported = mailboxExporter.export(identifier, destination, since);
        }
        logIfNothingExported(identifier, exported);
    }

    private List<String> resolveIdentifiers(
            BackupType type, List<String> identifiers, List<String> domains, boolean force) throws IOException {
        if (type == BackupType.SERVER_CONFIG) {
            return filterAlreadyBackedUpToday(
                    type, filterBlocked(List.of(SERVER_CONFIG_IDENTIFIER)), force);
        }
        if (type == BackupType.SELF) {
            if (!metadataStore.supportsSelfBackup()) {
                return List.of();
            }
            return filterAlreadyBackedUpToday(type, filterBlocked(List.of(SELF_IDENTIFIER)), force);
        }
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
            discovered = domains.isEmpty() ? accountDiscovery.discover(objectType) : discoverForDomains(objectType, domains);
        }
        return filterAlreadyBackedUpToday(type, filterBlocked(filterMalformed(objectType, discovered)), force);
    }

    private List<String> discoverForDomains(LdapObjectType objectType, List<String> domains) throws IOException {
        Set<String> merged = new LinkedHashSet<>();
        for (String domain : domains) {
            merged.addAll(accountDiscovery.discoverForDomain(objectType, domain));
        }
        return new ArrayList<>(merged);
    }

    private List<String> filterMalformed(LdapObjectType objectType, List<String> identifiers) {
        if (objectType == LdapObjectType.SIGNATURE) {
            return identifiers;
        }
        Pattern pattern = objectType == LdapObjectType.DOMAIN ? Identifiers.DOMAIN : Identifiers.EMAIL;
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

    private List<String> filterAlreadyBackedUpToday(BackupType type, List<String> identifiers, boolean force)
            throws IOException {
        if (!lockBackup || force || identifiers.isEmpty()) {
            return identifiers;
        }
        Instant since = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        List<Callable<Boolean>> tasks = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            tasks.add(() -> metadataStore.backedUpSince(identifier, type, since));
        }
        List<Boolean> alreadyBackedUp = Parallel.run(maxParallelProcesses, tasks);
        List<String> allowed = new ArrayList<>(identifiers.size());
        for (int i = 0; i < identifiers.size(); i++) {
            String identifier = identifiers.get(i);
            if (alreadyBackedUp.get(i)) {
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
            case SERVER_CONFIG -> throw new IllegalStateException(
                    "SERVER_CONFIG has no LDAP object type - it is handled directly in backupOne");
            case SELF -> throw new IllegalStateException(
                    "SELF has no LDAP object type - it is handled directly in backupOne");
        };
    }
}
