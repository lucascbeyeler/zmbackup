package io.zmbackup.app;

import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.YamlConfigLoader;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
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

    public AppContext(AppConfig config) throws IOException {
        this.config = config;
        this.storageProvider = new LocalStorageProvider(config.backup().workDir());
        this.metadataStore = new SqliteMetadataStore(config.backup().workDir().resolve(METADATA_STORE_FILENAME));
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
}
