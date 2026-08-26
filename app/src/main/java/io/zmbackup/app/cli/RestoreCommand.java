package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Stub for the restore subcommand; real restore logic lands once the zimbra module is implemented. */
@Command(name = "restore", description = "Restore a backup session (not yet implemented).")
public final class RestoreCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println("restore: not yet implemented");
        return 1;
    }
}
