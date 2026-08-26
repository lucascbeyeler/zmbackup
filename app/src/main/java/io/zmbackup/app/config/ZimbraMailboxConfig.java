package io.zmbackup.app.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Zimbra mailbox export settings, mirroring the {@code BACKUPUSER}/{@code ZMMAILBOX}/
 * {@code BACKUP_INACTIVE_ACCOUNTS} fields of the bash tool's {@code zmbackup.conf}.
 *
 * @param backupUser              the Zimbra service account used to start/stop the service and
 *                                run {@code zmmailbox}, e.g. {@code "zimbra"}
 * @param zmmailboxPath           location of the {@code zmmailbox} binary
 * @param backupInactiveAccounts whether to include disabled accounts
 */
public record ZimbraMailboxConfig(String backupUser, Path zmmailboxPath, boolean backupInactiveAccounts) {

    public ZimbraMailboxConfig {
        Objects.requireNonNull(backupUser, "backupUser must not be null");
        Objects.requireNonNull(zmmailboxPath, "zmmailboxPath must not be null");
    }
}
