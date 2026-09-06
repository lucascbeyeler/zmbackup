package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.List;
import picocli.CommandLine.Command;

@Command(
        name = "serverconfig",
        description = "Back up Zimbra server configuration, TLS certificates, Java keystores, and "
                + "component passwords from this host (see serverConfig.paths in zmbackup.yaml).")
public final class BackupServerConfigCommand extends AbstractBackupCommand {

    @Override
    BackupType type() {
        return BackupType.SERVER_CONFIG;
    }

    @Override
    List<String> identifiers() {
        return List.of();
    }

    @Override
    String domain() {
        return null;
    }
}
