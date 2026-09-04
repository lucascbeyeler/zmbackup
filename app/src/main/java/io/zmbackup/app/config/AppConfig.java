package io.zmbackup.app.config;

import java.util.Objects;

/**
 * The fully parsed contents of {@code zmbackup.yaml}, mirroring the sections of the bash tool's
 * {@code zmbackup.conf}.
 *
 * @param zimbraLdap    LDAP connection settings used for account discovery and LDAP object export
 * @param zimbraMailbox Zimbra mailbox export settings
 * @param backup        local backup behavior (storage, rotation, notifications)
 * @param allowInsecure explicit opt-in required for {@link AppConfig} to start with any setting
 *                      that sends credentials in cleartext or disables certificate verification
 *                      ({@code zimbraLdap.sslEnabled=false}, {@code zimbraLdap.trustAllCertificates=true}
 *                      with no {@code caCertificatePath}, or a non-{@code https://}
 *                      {@code zimbraMailbox.restBaseUrl}); {@link io.zmbackup.app.AppContext} refuses to
 *                      start with such a setting unless this is {@code true}
 */
public record AppConfig(
        ZimbraLdapConfig zimbraLdap, ZimbraMailboxConfig zimbraMailbox, BackupConfig backup, boolean allowInsecure) {

    public AppConfig {
        Objects.requireNonNull(zimbraLdap, "zimbraLdap must not be null");
        Objects.requireNonNull(zimbraMailbox, "zimbraMailbox must not be null");
        Objects.requireNonNull(backup, "backup must not be null");
    }
}
