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

/**
 * Backs up mail received since each account's last successful backup, mirroring {@code zmbackup
 * -i -a} in the bash tool.
 */
@Command(name = "incremental", description = "Back up mail received since each account's last successful backup.")
public final class BackupIncrementalCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Option(names = "--account", description = "Back up only this account (repeatable); default: every account.")
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
                        BackupType.INCREMENTAL,
                        accounts,
                        domain));
    }
}
