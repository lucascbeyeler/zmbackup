package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupType;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

abstract class AbstractBackupCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Spec
    private CommandSpec spec;

    abstract BackupType type();

    abstract List<String> identifiers();

    abstract String domain();

    @Override
    public final Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        return LockedExecution.run(
                context,
                spec.commandLine().getErr(),
                () -> BackupRunner.run(
                        context, spec.commandLine().getOut(), spec.commandLine().getErr(), type(), identifiers(), domain()));
    }
}
