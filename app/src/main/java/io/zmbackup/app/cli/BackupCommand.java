package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Stub for the backup subcommand; real backup logic lands once the zimbra module is implemented. */
@Command(name = "backup", description = "Run a backup (not yet implemented).")
public final class BackupCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println("backup: not yet implemented");
        return 1;
    }
}
