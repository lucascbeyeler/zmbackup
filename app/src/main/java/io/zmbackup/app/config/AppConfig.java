package io.zmbackup.app.config;

import java.util.Objects;

public record AppConfig(
        ZimbraLdapConfig zimbraLdap, ZimbraMailboxConfig zimbraMailbox, BackupConfig backup, boolean allowInsecure) {

    public AppConfig {
        Objects.requireNonNull(zimbraLdap, "zimbraLdap must not be null");
        Objects.requireNonNull(zimbraMailbox, "zimbraMailbox must not be null");
        Objects.requireNonNull(backup, "backup must not be null");
    }
}
