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

/** Backs up Zimbra domains from LDAP, mirroring {@code zmbackup -f -dom} in the bash tool. */
@Command(name = "domain", description = "Back up Zimbra domains from LDAP.")
public final class BackupDomainCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Option(names = "--domain", description = "Back up only this domain (repeatable); default: every domain.")
    private List<String> domains = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        return LockedExecution.run(
                context,
                spec.commandLine().getErr(),
                () -> BackupRunner.run(context, spec.commandLine().getOut(), BackupType.DOMAIN, domains, null));
    }
}
