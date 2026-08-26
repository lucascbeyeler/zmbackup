package io.zmbackup.app.config;

import java.util.Objects;

/**
 * The fully parsed contents of {@code zmbackup.yaml}, mirroring the sections of the bash tool's
 * {@code zmbackup.conf}.
 *
 * @param zimbraLdap    LDAP connection settings used for account discovery and LDAP object export
 * @param zimbraMailbox Zimbra mailbox export settings
 * @param backup        local backup behavior (storage, rotation, notifications)
 */
public record AppConfig(ZimbraLdapConfig zimbraLdap, ZimbraMailboxConfig zimbraMailbox, BackupConfig backup) {

    public AppConfig {
        Objects.requireNonNull(zimbraLdap, "zimbraLdap must not be null");
        Objects.requireNonNull(zimbraMailbox, "zimbraMailbox must not be null");
        Objects.requireNonNull(backup, "backup must not be null");
    }
}
