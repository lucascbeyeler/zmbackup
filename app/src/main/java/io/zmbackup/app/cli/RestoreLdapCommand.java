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

/** Restores LDAP entries from a backup session, mirroring {@code zmbackup -r ldap-*} in the bash tool. */
@Command(name = "ldap", description = "Restore LDAP entries from a backup session.")
public final class RestoreLdapCommand implements Callable<Integer> {

    @ParentCommand
    private RestoreCommand parent;

    @Option(names = "--session", required = true, description = "ID of the session to restore.")
    private String sessionId;

    @Option(names = "--account", description = "Restore only this account (repeatable); default: every account in the session.")
    private List<String> accounts = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter err = spec.commandLine().getErr();
        if (!CliValidation.validateSessionId(sessionId, err) || !CliValidation.validateEmails(accounts, err)) {
            return CommandLine.ExitCode.USAGE;
        }
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        return LockedExecution.run(context, err, () -> {
            RestoreResult result = context.restoreService().restoreLdap(sessionId, accounts);
            return RestoreRunner.printResult(spec.commandLine().getOut(), sessionId, result);
        });
    }
}
