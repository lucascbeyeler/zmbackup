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

/**
 * Restores a backup session, mirroring {@code zmbackup -r full-*}/{@code -r mbox-*} usage in the
 * bash tool. With no subcommand, restores both LDAP and mailbox content (or, with {@code --into},
 * restores the mailbox alone into a different destination account); the {@code ldap}, {@code
 * domain}, and {@code mailbox} subcommands restore one kind of content on its own.
 */
@Command(
        name = "restore",
        description = "Restore a backup session (LDAP + mailbox).",
        subcommands = {RestoreLdapCommand.class, RestoreDomainCommand.class, RestoreMailboxCommand.class})
public final class RestoreCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    // Not required=true: picocli would enforce that even when a subcommand (which declares its
    // own --session) is invoked instead of this command's own call().
    @Option(names = "--session", description = "ID of the session to restore.")
    private String sessionId;

    @Option(names = "--account", description = "Restore only this account (repeatable); default: every account in the session.")
    private List<String> accounts = new ArrayList<>();

    @Option(
            names = "--into",
            description = "Restore the mailbox into a different destination account (requires exactly one --account).")
    private String destination;

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

        AppContext context = AppContext.fromConfigFile(parent.configFile());
        PrintWriter out = spec.commandLine().getOut();
        return LockedExecution.run(context, err, () -> {
            RestoreResult result = destination != null
                    ? context.restoreService().restoreMailbox(sessionId, accounts, destination)
                    : context.restoreService().restoreFull(sessionId, accounts);
            return RestoreRunner.printResult(out, sessionId, result);
        });
    }
}
