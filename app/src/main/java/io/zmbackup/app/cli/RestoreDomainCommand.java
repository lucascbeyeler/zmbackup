package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.RestoreResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Restores Zimbra domain LDAP entries from a backup session, mirroring {@code zmbackup -r domain-*} in the bash tool. */
@Command(name = "domain", description = "Restore Zimbra domain LDAP entries from a backup session.")
public final class RestoreDomainCommand implements Callable<Integer> {

    @ParentCommand
    private RestoreCommand parent;

    @Option(names = "--session", required = true, description = "ID of the session to restore.")
    private String sessionId;

    @Option(names = "--domain", description = "Restore only this domain (repeatable); default: every domain in the session.")
    private List<String> domains = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        RestoreResult result = context.restoreService().restoreDomain(sessionId, domains);
        return RestoreRunner.printResult(spec.commandLine().getOut(), sessionId, result);
    }
}
