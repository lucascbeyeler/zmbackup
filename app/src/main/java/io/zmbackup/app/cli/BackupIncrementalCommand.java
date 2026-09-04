package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "incremental", description = "Back up mail received since each account's last successful backup.")
public final class BackupIncrementalCommand extends AbstractAccountScopedBackupCommand {

    @Option(names = "--account", description = "Back up only this account (repeatable); default: every account.")
    private List<String> accounts = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.INCREMENTAL;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }
}
