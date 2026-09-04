package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "alias", description = "Back up Zimbra aliases from LDAP.")
public final class BackupAliasCommand extends AbstractAccountScopedBackupCommand {

    @Option(names = "--account", description = "Back up only this alias (repeatable); default: every alias.")
    private List<String> accounts = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.ALIAS;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }
}
