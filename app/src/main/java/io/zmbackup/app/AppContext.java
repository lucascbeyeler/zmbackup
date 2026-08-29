package io.zmbackup.app;

import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.YamlConfigLoader;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.core.port.ZimbraLdapExporter;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import io.zmbackup.core.service.BackupService;
import io.zmbackup.core.service.HousekeepService;
import io.zmbackup.core.service.RestoreService;
import io.zmbackup.core.service.SessionService;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import io.zmbackup.zimbra.ZimbraRestMailboxExporter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Wires the application's components from a parsed {@link AppConfig} with plain {@code new},
 * standing in for a dependency injection framework.
 */
public final class AppContext {

    /** Filename of the SQLite database within {@link io.zmbackup.app.config.BackupConfig#workDir()}. */
    private static final String METADATA_STORE_FILENAME = "sessions.sqlite3";

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

    public AppContext(AppConfig config) throws IOException {
        this.config = config;
        this.storageProvider = new LocalStorageProvider(config.backup().workDir());
        this.metadataStore = new SqliteMetadataStore(config.backup().workDir().resolve(METADATA_STORE_FILENAME));
        UnboundIdLdapAdapter ldapAdapter = new UnboundIdLdapAdapter(
                config.zimbraLdap().url(),
                config.zimbraLdap().bindDn(),
                config.zimbraLdap().bindPassword(),
                config.zimbraLdap().sslEnabled());
        this.accountDiscovery = ldapAdapter;
        this.ldapExporter = ldapAdapter;
        this.mailboxExporter = new ZimbraRestMailboxExporter(
                config.zimbraMailbox().restBaseUrl(),
                config.zimbraMailbox().adminUser(),
                config.zimbraMailbox().adminPassword());
        this.sessionService = new SessionService(storageProvider, metadataStore);
        this.backupService =
                new BackupService(accountDiscovery, ldapExporter, mailboxExporter, storageProvider, metadataStore);
        this.restoreService = new RestoreService(ldapExporter, mailboxExporter, storageProvider, metadataStore);
        this.housekeepService = new HousekeepService(storageProvider, metadataStore);
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
}
