package io.zmbackup.app.cli;

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

    @Option(
            names = "--verify",
            description = "After restoring, verify the destination actually gained as many items as the archive"
                    + " being restored contains (Zimbra's REST import can silently drop malformed messages while"
                    + " still reporting success). Costs an extra full mailbox export before and after the"
                    + " restore. Do not use when re-restoring content already present in the destination -"
                    + " resolve=skip will not recreate duplicates, which this check cannot tell apart from"
                    + " dropped content.")
    private boolean verify;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter err = spec.commandLine().getErr();
        if (!CliValidation.validateSessionId(sessionId, err)
                || !CliValidation.validateEmails(accounts, err)
                || !CliValidation.validateEmail(destination, err)) {
            return CommandLine.ExitCode.USAGE;
        }
        if (!CliValidation.validateIntoRequiresSingleAccount("restore mailbox", destination, accounts, err)) {
            return CommandLine.ExitCode.USAGE;
        }

        return LockedExecution.run(parent.parent().configFile(), err, context -> {
            RestoreResult result = context.restoreService().restoreMailbox(sessionId, accounts, destination, verify);
            return RestoreRunner.printResult(spec.commandLine().getOut(), sessionId, result);
        });
    }
}
