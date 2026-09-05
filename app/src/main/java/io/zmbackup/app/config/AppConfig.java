package io.zmbackup.app.config;

import java.util.Objects;

public record AppConfig(
        ZimbraLdapConfig zimbraLdap,
        ZimbraMailboxConfig zimbraMailbox,
        BackupConfig backup,
        StorageConfig storage,
        MetadataConfig metadata,
        boolean allowInsecure) {

    public AppConfig {
        Objects.requireNonNull(zimbraLdap, "zimbraLdap must not be null");
        Objects.requireNonNull(zimbraMailbox, "zimbraMailbox must not be null");
        Objects.requireNonNull(backup, "backup must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
