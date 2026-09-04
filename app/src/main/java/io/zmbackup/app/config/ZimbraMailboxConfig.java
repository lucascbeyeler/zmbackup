package io.zmbackup.app.config;

import java.util.Objects;

/**
 * Zimbra mailbox export settings, mirroring the {@code BACKUPUSER}/{@code BACKUP_INACTIVE_ACCOUNTS}
 * fields of the bash tool's {@code zmbackup.conf}, plus the REST endpoint settings used by {@code
 * ZimbraRestMailboxExporter}.
 *
 * @param backupUser              the Zimbra service account used to start/stop the service, e.g.
 *                                {@code "zimbra"}
 * @param backupInactiveAccounts whether to include disabled accounts
 * @param restBaseUrl             the Zimbra server's REST base URL, e.g.
 *                                {@code "https://mail.example.com:7071"}
 * @param adminUser               the Zimbra admin account used for REST HTTP Basic authentication
 * @param adminPassword           the admin account's password
 * @param caCertificatePath       path to a PEM-encoded CA certificate (bundle) used to verify the
 *                                REST server's certificate, or {@code null} to use the JVM's
 *                                default trust manager (or, if {@code trustAllCertificates} is
 *                                set, to trust any certificate)
 * @param trustAllCertificates    whether to accept any REST server certificate when {@code
 *                                caCertificatePath} is not set; must be explicitly enabled, since
 *                                it offers no protection against an active MITM attack
 */
public record ZimbraMailboxConfig(
        String backupUser,
        boolean backupInactiveAccounts,
        String restBaseUrl,
        String adminUser,
        String adminPassword,
        String caCertificatePath,
        boolean trustAllCertificates) {

    public ZimbraMailboxConfig {
        Objects.requireNonNull(backupUser, "backupUser must not be null");
        Objects.requireNonNull(restBaseUrl, "restBaseUrl must not be null");
        Objects.requireNonNull(adminUser, "adminUser must not be null");
        Objects.requireNonNull(adminPassword, "adminPassword must not be null");
    }

    /**
     * Overrides the record's default {@code toString()} to redact {@link #adminPassword()}, since
     * this value is logged (e.g. via an uncaught exception's stack trace) wherever a config record
     * ends up in a message.
     */
    @Override
    public String toString() {
        return "ZimbraMailboxConfig[backupUser=" + backupUser
                + ", backupInactiveAccounts=" + backupInactiveAccounts
                + ", restBaseUrl=" + restBaseUrl
                + ", adminUser=" + adminUser
                + ", adminPassword=***"
                + ", caCertificatePath=" + caCertificatePath
                + ", trustAllCertificates=" + trustAllCertificates
                + "]";
    }
}
