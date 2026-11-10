package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.CompressionSettings;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "mailbox", description = "Back up Zimbra accounts' mailbox content only.")
public final class BackupMailboxCommand extends AbstractAccountScopedBackupCommand {

    @Option(names = "--account", description = "Back up only this account (repeatable); default: every account.")
    private List<String> accounts = new ArrayList<>();

    @Mixin
    private CompressionOptions compressionOptions = new CompressionOptions();

    @Override
    BackupType type() {
        return BackupType.MAILBOX;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }

    @Override
    CompressionSettings compression(CompressionSettings configured) {
        return compressionOptions.resolve(configured);
    }
}
