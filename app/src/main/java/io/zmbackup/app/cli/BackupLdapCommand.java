package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "ldap", description = "Back up Zimbra accounts from LDAP.")
public final class BackupLdapCommand extends AbstractAccountScopedBackupCommand {

    @Option(names = "--account", description = "Back up only this account (repeatable); default: every account.")
    private List<String> accounts = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.LDAP;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }
}
