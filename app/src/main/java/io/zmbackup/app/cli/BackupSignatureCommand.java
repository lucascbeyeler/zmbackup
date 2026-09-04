package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "signature", description = "Back up Zimbra signatures from LDAP.")
public final class BackupSignatureCommand extends AbstractAccountScopedBackupCommand {

    @Option(
            names = "--account",
            description = "Back up only this account's signatures (repeatable); default: every account.")
    private List<String> accounts = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.SIGNATURE;
    }

    @Override
    List<String> identifiers() {
        return accounts;
    }
}
