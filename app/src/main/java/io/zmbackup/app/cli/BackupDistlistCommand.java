package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Backs up Zimbra distribution lists from LDAP, mirroring {@code zmbackup -f -dl} in the bash tool. */
@Command(name = "distlist", description = "Back up Zimbra distribution lists from LDAP.")
public final class BackupDistlistCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Option(
            names = "--account",
            description = "Back up only this distribution list (repeatable); default: every distribution list.")
    private List<String> accounts = new ArrayList<>();

    @Option(names = "--domain", description = "Restrict discovery to this Zimbra domain (e.g. example.com).")
    private String domain;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        return LockedExecution.run(
                context,
                spec.commandLine().getErr(),
                () -> BackupRunner.run(
                        context,
                        spec.commandLine().getOut(),
                        spec.commandLine().getErr(),
                        BackupType.DISTRIBUTION_LIST,
                        accounts,
                        domain));
    }
}
