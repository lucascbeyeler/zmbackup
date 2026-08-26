package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Stub for the housekeep subcommand; real cleanup logic lands once the zimbra module is implemented. */
@Command(name = "housekeep", description = "Prune old backup sessions (not yet implemented).")
public final class HousekeepCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println("housekeep: not yet implemented");
        return 1;
    }
}
