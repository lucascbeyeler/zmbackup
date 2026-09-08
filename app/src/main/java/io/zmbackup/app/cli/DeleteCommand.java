package io.zmbackup.app.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

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
        PrintWriter err = spec.commandLine().getErr();
        if (!CliValidation.validateSessionId(sessionId, err)) {
            return CommandLine.ExitCode.USAGE;
        }

        PrintWriter out = spec.commandLine().getOut();

        return LockedExecution.run(parent.configFile(), err, context -> {
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
