package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Backs up Zimbra accounts' mailbox content only, mirroring {@code zmbackup -f -m -a} in the bash
 * tool.
 */
@Command(name = "mailbox", description = "Back up Zimbra accounts' mailbox content only.")
public final class BackupMailboxCommand extends AbstractAccountScopedBackupCommand {

    @Option(names = "--account", description = "Back up only this account (repeatable); default: every account.")
    private List<String> accounts = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.MAILBOX;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }
}
