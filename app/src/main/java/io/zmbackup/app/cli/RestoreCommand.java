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

@Command(
        name = "restore",
        description = "Restore a backup session (LDAP + mailbox).",
        subcommands = {
            RestoreLdapCommand.class,
            RestoreDomainCommand.class,
            RestoreMailboxCommand.class,
            RestoreServerConfigCommand.class
        })
public final class RestoreCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Option(names = "--session", description = "ID of the session to restore.")
    private String sessionId;

    @Option(names = "--account", description = "Restore only this account (repeatable); default: every account in the session.")
    private List<String> accounts = new ArrayList<>();

    @Option(
            names = "--into",
            description = "Restore the mailbox into a different destination account (requires exactly one --account).")
    private String destination;

    @Option(
            names = "--verify",
            description = "After restoring mailbox content, verify the destination actually gained as many items"
                    + " as the archive being restored contains (Zimbra's REST import can silently drop malformed"
                    + " messages while still reporting success). Costs an extra full mailbox export before and"
                    + " after the restore. Do not use when re-restoring content already present in the"
                    + " destination - resolve=skip will not recreate duplicates, which this check cannot tell"
                    + " apart from dropped content.")
    private boolean verify;

    @Spec
    private CommandSpec spec;

    Main parent() {
        return parent;
    }

    @Override
    public Integer call() throws Exception {
        PrintWriter err = spec.commandLine().getErr();
        if (sessionId == null) {
            err.println("restore: missing required option '--session=<sessionId>'");
            return CommandLine.ExitCode.USAGE;
        }
        if (!CliValidation.validateSessionId(sessionId, err)) {
            return CommandLine.ExitCode.USAGE;
        }
        if (!(CliValidation.validateEmails(accounts, err) && CliValidation.validateEmail(destination, err))) {
            return CommandLine.ExitCode.USAGE;
        }
        if (!CliValidation.validateIntoRequiresSingleAccount("restore", destination, accounts, err)) {
            return CommandLine.ExitCode.USAGE;
        }
        if (destination == null && !CliValidation.validateFullOrIncrementalSessionPrefix(sessionId, err)) {
            return CommandLine.ExitCode.USAGE;
        }

        PrintWriter out = spec.commandLine().getOut();
        return LockedExecution.run(parent.configFile(), err, context -> {
            RestoreResult result = destination != null
                    ? context.restoreService().restoreMailbox(sessionId, accounts, destination, verify)
                    : context.restoreService().restoreFull(sessionId, accounts, verify);
            return RestoreRunner.printResult(out, sessionId, result);
        });
    }
}
