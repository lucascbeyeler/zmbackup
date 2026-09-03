package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Backs up Zimbra distribution lists from LDAP, mirroring {@code zmbackup -f -dl} in the bash tool. */
@Command(name = "distlist", description = "Back up Zimbra distribution lists from LDAP.")
public final class BackupDistlistCommand extends AbstractAccountScopedBackupCommand {

    @Option(
            names = "--account",
            description = "Back up only this distribution list (repeatable); default: every distribution list.")
    private List<String> accounts = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.DISTRIBUTION_LIST;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }
}
