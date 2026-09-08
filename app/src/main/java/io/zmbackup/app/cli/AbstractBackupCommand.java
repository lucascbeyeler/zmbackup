package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

abstract class AbstractBackupCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(
            names = "--force",
            description = "Back up even if an identifier already has a conflicting backup recorded today "
                    + "(bypasses backup.lockBackup for this run only).")
    private boolean force;

    abstract BackupType type();

    abstract List<String> identifiers();

    abstract String domain();

    @Override
    public final Integer call() throws Exception {
        return LockedExecution.run(
                parent.parent().configFile(),
                spec.commandLine().getErr(),
                context -> BackupRunner.run(
                        context,
                        spec.commandLine().getOut(),
                        spec.commandLine().getErr(),
                        type(),
                        identifiers(),
                        domain(),
                        force));
    }
}
