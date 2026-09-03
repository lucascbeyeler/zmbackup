package io.zmbackup.app.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Zimbra mailbox export settings, mirroring the {@code BACKUPUSER}/{@code ZMMAILBOX}/
 * {@code BACKUP_INACTIVE_ACCOUNTS} fields of the bash tool's {@code zmbackup.conf}, plus the REST
 * endpoint settings used by {@code ZimbraRestMailboxExporter}.
 *
 * @param backupUser              the Zimbra service account used to start/stop the service and
 *                                run {@code zmmailbox}, e.g. {@code "zimbra"}
 * @param zmmailboxPath           location of the {@code zmmailbox} binary
 * @param backupInactiveAccounts whether to include disabled accounts
 * @param restBaseUrl             the Zimbra server's REST base URL, e.g.
 *                                {@code "https://mail.example.com:7071"}
 * @param adminUser               the Zimbra admin account used for REST HTTP Basic authentication
 * @param adminPassword           the admin account's password
 */
public record ZimbraMailboxConfig(
        String backupUser,
        Path zmmailboxPath,
        boolean backupInactiveAccounts,
        String restBaseUrl,
        String adminUser,
        String adminPassword) {

    public ZimbraMailboxConfig {
        Objects.requireNonNull(backupUser, "backupUser must not be null");
        Objects.requireNonNull(zmmailboxPath, "zmmailboxPath must not be null");
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
                + ", zmmailboxPath=" + zmmailboxPath
                + ", backupInactiveAccounts=" + backupInactiveAccounts
                + ", restBaseUrl=" + restBaseUrl
                + ", adminUser=" + adminUser
                + ", adminPassword=***"
                + "]";
    }
}
