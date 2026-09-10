package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.List;
import picocli.CommandLine.Command;

@Command(
        name = "self",
        description = "Back up zmbackup's own SQLite metadata store (sessions.sqlite3) to the same "
                + "destination as regular backups. A no-op when metadata.backend is not sqlite.")
public final class BackupSelfCommand extends AbstractBackupCommand {

    @Override
    BackupType type() {
        return BackupType.SELF;
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
