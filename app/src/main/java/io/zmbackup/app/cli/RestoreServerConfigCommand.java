package io.zmbackup.app.cli;

import io.zmbackup.core.domain.RestoreResult;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "serverconfig",
        description = "Restore Zimbra server configuration, TLS certificates, Java keystores, and "
                + "component passwords from a backup session, writing them back to their original host paths.")
public final class RestoreServerConfigCommand implements Callable<Integer> {

    @ParentCommand
    private RestoreCommand parent;

    @Option(names = "--session", required = true, description = "ID of the session to restore.")
    private String sessionId;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter err = spec.commandLine().getErr();
        if (!CliValidation.validateSessionId(sessionId, err)) {
            return CommandLine.ExitCode.USAGE;
        }
        return LockedExecution.run(parent.parent().configFile(), err, context -> {
            RestoreResult result = context.restoreService().restoreServerConfig(sessionId);
            return RestoreRunner.printResult(spec.commandLine().getOut(), sessionId, result);
        });
    }
}
