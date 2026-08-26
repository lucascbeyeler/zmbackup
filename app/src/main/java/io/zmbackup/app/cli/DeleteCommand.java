package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Stub for the delete subcommand; real deletion logic lands once the zimbra module is implemented. */
@Command(name = "delete", description = "Delete a stored backup session (not yet implemented).")
public final class DeleteCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println("delete: not yet implemented");
        return 1;
    }
}
