package io.zmbackup.app;

import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.EmailNotifyLevel;
import io.zmbackup.app.config.YamlConfigLoader;
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

/**
 * Wires the application's components from a parsed {@link AppConfig} with plain {@code new},
 * standing in for a dependency injection framework.
 */
public final class AppContext {

    /** Filename of the SQLite database within {@link io.zmbackup.app.config.BackupConfig#workDir()}. */
    private static final String METADATA_STORE_FILENAME = "sessions.sqlite3";

    /**
     * Host/port of the local SMTP relay {@link EmailNotifier} submits through, matching the bash
     * tool's use of the {@code sendmail} command to submit to the machine's own MTA.
     */
    private static final String NOTIFY_SMTP_HOST = "localhost";

    private static final int NOTIFY_SMTP_PORT = 25;

    /** Root logger name every zmbackup class logs under, so one appender pair covers all of them. */
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
        if (!config.zimbraLdap().sslEnabled()) {
            // Unlike the bash tool, whose ldapsearch/ldapadd/ldapdelete calls always upgraded with
            // StartTLS (-Z) regardless of SSL_ENABLE (which there only chose http vs. https for the
            // mailbox REST endpoint), zimbraLdap.sslEnabled here directly gates StartTLS on the LDAP
            // admin bind - disabling it sends the bind password in cleartext.
            LOG.warn(
                    "zimbraLdap.sslEnabled is false: the LDAP admin bind will not use StartTLS and its"
                            + " credentials will be sent in cleartext. The bash tool never allowed this.");
        } else if (config.zimbraLdap().caCertificatePath() == null && config.zimbraLdap().trustAllCertificates()) {
            LOG.warn(
                    "zimbraLdap.trustAllCertificates is true: the LDAP StartTLS connection will accept any"
                            + " server certificate, which does not protect against an active MITM attack."
                            + " Configure zimbraLdap.caCertificatePath instead for production use.");
        }
        if (!config.zimbraMailbox().restBaseUrl().toLowerCase(Locale.ROOT).startsWith("https://")) {
            // The bash tool's SSL_ENABLE defaulted to (and warned toward) true, choosing https for the
            // mailbox REST endpoint; nothing here stopped an operator from setting it false, but the
            // default was safe. zimbraMailbox.restBaseUrl carries no equivalent toggle or default: it's
            // an operator-supplied URL, and ZimbraRestMailboxExporter sends the admin Basic-auth
            // credentials over whatever scheme it's given, without any warning of its own.
            LOG.warn(
                    "zimbraMailbox.restBaseUrl does not start with https://: mailbox REST requests,"
                            + " including the admin Basic-auth credentials, will be sent in cleartext."
                            + " Configure an https:// URL for production use.");
        }
        this.storageProvider = new LocalStorageProvider(config.backup().workDir());
        this.metadataStore = new SqliteMetadataStore(config.backup().workDir().resolve(METADATA_STORE_FILENAME));
        UnboundIdLdapAdapter ldapAdapter = new UnboundIdLdapAdapter(
                config.zimbraLdap().url(),
                config.zimbraLdap().bindDn(),
                config.zimbraLdap().bindPassword(),
                config.zimbraLdap().sslEnabled(),
                config.zimbraLdap().caCertificatePath(),
                config.zimbraLdap().trustAllCertificates());
        this.accountDiscovery = ldapAdapter;
        this.ldapExporter = ldapAdapter;
        this.mailboxExporter = new ZimbraRestMailboxExporter(
                config.zimbraMailbox().restBaseUrl(),
                config.zimbraMailbox().adminUser(),
                config.zimbraMailbox().adminPassword());
        Blocklist blocklist = new FileBlocklist(config.backup().blockedListFile());
        Notifier notifier = emailNotifier(config);
        this.sessionService = new SessionService(storageProvider, metadataStore);
        this.backupService = new BackupService(
                accountDiscovery,
                ldapExporter,
                mailboxExporter,
                storageProvider,
                metadataStore,
                blocklist,
                notifier,
                config.backup().maxParallelProcesses(),
                config.backup().lockBackup());
        this.restoreService = new RestoreService(
                ldapExporter, mailboxExporter, storageProvider, metadataStore, config.backup().maxParallelProcesses());
        this.housekeepService = new HousekeepService(storageProvider, metadataStore);
        this.migrationService = new MigrationService(storageProvider, metadataStore);
    }

    /**
     * Refuses to run when the OS user invoking the process doesn't match {@code
     * zimbraMailbox.backupUser}, mirroring the bash tool's {@code validate_config}: {@code if [
     * "$(whoami)" != "$BACKUPUSER" ]; then echo "You need to be $BACKUPUSER to run this
     * software."; exit 2; fi}.
     */
    private static void checkBackupUser(AppConfig config) {
        String expected = config.zimbraMailbox().backupUser();
        String actual = System.getProperty("user.name");
        if (!expected.equals(actual)) {
            throw new PrivilegeException("You need to be " + expected + " to run this software.");
        }
    }

    /**
     * Routes every {@code io.zmbackup} logger to {@code logFile}/syslog, the same two
     * destinations the bash tool's {@code zmlog} writes to, using the standard SLF4J/Logback
     * stack instead of the JDK's default console handler.
     *
     * <p>{@code core} stays free of external dependencies (see {@code core/build.gradle.kts}) and
     * keeps logging through plain {@code java.util.logging}; {@link SLF4JBridgeHandler} routes
     * those records into SLF4J/Logback here instead.
     */
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

    /**
     * Builds an {@link EmailNotifier} from {@code config}'s {@code backup.emailNotify} section,
     * translating {@link EmailNotifyLevel} into which lifecycle events it notifies on.
     */
    private static EmailNotifier emailNotifier(AppConfig config) {
        EmailNotifyLevel level = config.backup().emailNotify().level();
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

    /** Reads {@code configFile} and wires the components it describes. */
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
