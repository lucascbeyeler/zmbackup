package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.RestoreResult;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Restores mailbox content from a backup session, mirroring {@code zmbackup -r mbox-*} in the bash tool. */
@Command(name = "mailbox", description = "Restore mailbox content from a backup session.")
public final class RestoreMailboxCommand implements Callable<Integer> {

    @ParentCommand
    private RestoreCommand parent;

    @Option(names = "--session", required = true, description = "ID of the session to restore.")
    private String sessionId;

    @Option(names = "--account", description = "Restore only this account (repeatable); default: every account in the session.")
    private List<String> accounts = new ArrayList<>();

    @Option(
            names = "--into",
            description = "Restore into a different destination account (requires exactly one --account).")
    private String destination;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter err = spec.commandLine().getErr();
        if (destination != null && accounts.size() != 1) {
            err.println("restore mailbox: --into requires exactly one --account");
            return CommandLine.ExitCode.USAGE;
        }

        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        return LockedExecution.run(context, err, () -> {
            RestoreResult result = context.restoreService().restoreMailbox(sessionId, accounts, destination);
            return RestoreRunner.printResult(spec.commandLine().getOut(), sessionId, result);
        });
    }
}
