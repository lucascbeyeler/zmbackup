package io.zmbackup.app;

import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.ConfigException;
import io.zmbackup.app.config.EmailNotifyLevel;
import io.zmbackup.app.config.YamlConfigLoader;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.Blocklist;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.Notifier;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import io.zmbackup.core.service.BackupService;
import io.zmbackup.core.service.HousekeepService;
import io.zmbackup.core.service.MigrationService;
import io.zmbackup.core.service.RestoreService;
import io.zmbackup.core.service.SessionService;
import io.zmbackup.local.EmailNotifier;
import io.zmbackup.local.FileBlocklist;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import io.zmbackup.zimbra.ZimbraRestMailboxExporter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

public final class AppContext {

    private static final String METADATA_STORE_FILENAME = "sessions.sqlite3";

    private static final String NOTIFY_SMTP_HOST = "localhost";

    private static final int NOTIFY_SMTP_PORT = 25;

    private static final Notifier NO_NOTIFIER = new Notifier() {
        @Override
        public void notifyBegin(String sessionId, BackupType type) {}

        @Override
        public void notifyFinish(
                String sessionId, BackupType type, SessionStatus status, String size, int accountCount) {}
    };

    private static final String ROOT_LOGGER_NAME = "io.zmbackup";

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final AppConfig config;
    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;
    private final AccountDiscovery accountDiscovery;
    private final ZimbraLdapExporter ldapExporter;
    private final ZimbraMailboxExporter mailboxExporter;
    private final SessionService sessionService;
    private final BackupService backupService;
    private final RestoreService restoreService;
    private final HousekeepService housekeepService;
    private final MigrationService migrationService;

    public AppContext(AppConfig config) throws IOException {
        checkBackupUser(config);
        this.config = config;
        installLogging(config.backup().logFile());
        checkInsecureSettings(config);
        this.storageProvider = new LocalStorageProvider(config.backup().workDir());
        SqliteMetadataStore sqliteMetadataStore =
                new SqliteMetadataStore(config.backup().workDir().resolve(METADATA_STORE_FILENAME));
        this.metadataStore = sqliteMetadataStore;
        try {
            UnboundIdLdapAdapter ldapAdapter = new UnboundIdLdapAdapter(
                    config.zimbraLdap().url(),
                    config.zimbraLdap().bindDn(),
                    config.zimbraLdap().bindPassword(),
                    config.zimbraLdap().sslEnabled(),
                    config.zimbraLdap().caCertificatePath(),
                    config.zimbraLdap().trustAllCertificates(),
                    config.zimbraMailbox().backupInactiveAccounts(),
                    config.zimbraLdap().responseTimeoutSeconds() * 1000L);
            this.accountDiscovery = ldapAdapter;
            this.ldapExporter = ldapAdapter;
            this.mailboxExporter = new ZimbraRestMailboxExporter(
                    config.zimbraMailbox().restBaseUrl(),
                    config.zimbraMailbox().adminUser(),
                    config.zimbraMailbox().adminPassword(),
                    config.zimbraMailbox().caCertificatePath(),
                    config.zimbraMailbox().trustAllCertificates());
            Blocklist blocklist = new FileBlocklist(config.backup().blockedListFile());
            Notifier notifier = emailNotifier(config);
            this.sessionService = new SessionService(storageProvider, metadataStore);
            this.backupService = BackupService.builder(
                            accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore)
                    .blocklist(blocklist)
                    .notifier(notifier)
                    .maxParallelProcesses(config.backup().maxParallelProcesses())
                    .lockBackup(config.backup().lockBackup())
                    .build();
            this.restoreService = new RestoreService(
                    ldapExporter,
                    mailboxExporter,
                    storageProvider,
                    metadataStore,
                    config.backup().maxParallelProcesses());
            this.housekeepService = new HousekeepService(storageProvider, metadataStore);
            this.migrationService = new MigrationService(storageProvider, metadataStore);
        } catch (IOException | RuntimeException e) {
            sqliteMetadataStore.close();
            throw e;
        }
    }

    private static void checkBackupUser(AppConfig config) {
        String expected = config.zimbraMailbox().backupUser();
        String actual = System.getProperty("user.name");
        if (!expected.equals(actual)) {
            throw new PrivilegeException("You need to be " + expected + " to run this software.");
        }
    }

    private static void checkInsecureSettings(AppConfig config) {
        if (!config.zimbraLdap().sslEnabled()) {
            refuseUnlessAllowInsecure(
                    config,
                    "zimbraLdap.sslEnabled is false: the LDAP admin bind will not use StartTLS and its"
                            + " credentials will be sent in cleartext. The bash tool never allowed this.");
        } else if (config.zimbraLdap().caCertificatePath() == null && config.zimbraLdap().trustAllCertificates()) {
            refuseUnlessAllowInsecure(
                    config,
                    "zimbraLdap.trustAllCertificates is true: the LDAP StartTLS connection will accept any"
                            + " server certificate, which does not protect against an active MITM attack."
                            + " Configure zimbraLdap.caCertificatePath instead for production use.");
        }
        if (!config.zimbraMailbox().restBaseUrl().toLowerCase(Locale.ROOT).startsWith("https://")) {
            refuseUnlessAllowInsecure(
                    config,
                    "zimbraMailbox.restBaseUrl does not start with https://: mailbox REST requests,"
                            + " including the admin Basic-auth credentials, will be sent in cleartext."
                            + " Configure an https:// URL for production use.");
        } else if (config.zimbraMailbox().caCertificatePath() == null
                && config.zimbraMailbox().trustAllCertificates()) {
            refuseUnlessAllowInsecure(
                    config,
                    "zimbraMailbox.trustAllCertificates is true: the mailbox REST connection will accept any"
                            + " server certificate, which does not protect against an active MITM attack."
                            + " Configure zimbraMailbox.caCertificatePath instead for production use.");
        }
    }

    private static void refuseUnlessAllowInsecure(AppConfig config, String message) {
        if (!config.allowInsecure()) {
            throw new ConfigException(message + " Set allowInsecure: true in zmbackup.yaml to run anyway.");
        }
        LOG.warn(message);
    }

    private static void installLogging(Path logFile) {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger zmbackupLogger = loggerContext.getLogger(ROOT_LOGGER_NAME);
        zmbackupLogger.setAdditive(false);
        zmbackupLogger.setLevel(Level.INFO);
        zmbackupLogger.detachAndStopAllAppenders();
        zmbackupLogger.addAppender(fileAppender(logFile, loggerContext));
        zmbackupLogger.addAppender(syslogAppender(loggerContext));
    }

    private static FileAppender<ILoggingEvent> fileAppender(Path logFile, LoggerContext loggerContext) {
        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(loggerContext);
        encoder.setLayout(new ZmlogLayout());
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(loggerContext);
        appender.setName("zmbackup-logfile");
        appender.setFile(logFile.toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.start();
        return appender;
    }

    private static LocalSyslogAppender syslogAppender(LoggerContext loggerContext) {
        LocalSyslogAppender appender = new LocalSyslogAppender();
        appender.setContext(loggerContext);
        appender.setName("zmbackup-syslog");
        appender.start();
        return appender;
    }

    private static Notifier emailNotifier(AppConfig config) {
        EmailNotifyLevel level = config.backup().emailNotify().level();
        if (level == EmailNotifyLevel.NONE) {
            return NO_NOTIFIER;
        }
        boolean notifyOnBegin = level == EmailNotifyLevel.ALL || level == EmailNotifyLevel.START;
        boolean notifyOnFinishSuccess = level == EmailNotifyLevel.ALL || level == EmailNotifyLevel.FINISH;
        boolean notifyOnFinishError = level == EmailNotifyLevel.ALL || level == EmailNotifyLevel.ERROR;
        return new EmailNotifier(
                NOTIFY_SMTP_HOST,
                NOTIFY_SMTP_PORT,
                config.backup().emailNotify().sender(),
                config.backup().emailNotify().recipient(),
                notifyOnBegin,
                notifyOnFinishSuccess,
                notifyOnFinishError);
    }

    public static AppContext fromConfigFile(Path configFile) throws IOException {
        return new AppContext(YamlConfigLoader.load(configFile));
    }

    public AppConfig config() {
        return config;
    }

    public StorageProvider storageProvider() {
        return storageProvider;
    }

    public MetadataStore metadataStore() {
        return metadataStore;
    }

    public AccountDiscovery accountDiscovery() {
        return accountDiscovery;
    }

    public ZimbraMailboxExporter mailboxExporter() {
        return mailboxExporter;
    }

    public SessionService sessionService() {
        return sessionService;
    }

    public BackupService backupService() {
        return backupService;
    }

    public RestoreService restoreService() {
        return restoreService;
    }

    public HousekeepService housekeepService() {
        return housekeepService;
    }

    public MigrationService migrationService() {
        return migrationService;
    }
}
