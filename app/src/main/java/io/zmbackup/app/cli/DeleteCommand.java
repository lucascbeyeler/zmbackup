package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Deletes a stored backup session, mirroring the bash tool's {@code delete_one}. */
@Command(name = "delete", description = "Delete a stored backup session.")
public final class DeleteCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Option(names = "--session", required = true, description = "ID of the session to delete.")
    private String sessionId;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.configFile());
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        return LockedExecution.run(context, err, () -> {
            out.println("Removing session " + sessionId + " - please wait.");
            if (context.sessionService().deleteSession(sessionId)) {
                out.println("Backup session " + sessionId + " removed.");
                return 0;
            }
            err.println("Session " + sessionId + " not found in database - ignoring.");
            return 1;
        });
    }
}
