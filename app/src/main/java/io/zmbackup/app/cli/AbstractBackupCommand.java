package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupType;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Shared body for every {@code backup} subcommand: resolves the {@link AppContext}, takes the
 * backup work-dir lock, and delegates to {@link BackupRunner} with the concrete subcommand's
 * {@link BackupType}, identifiers, and domain filter.
 */
abstract class AbstractBackupCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Spec
    private CommandSpec spec;

    abstract BackupType type();

    /** The account/alias/distribution-list/domain identifiers to restrict this backup to. */
    abstract List<String> identifiers();

    /** The Zimbra domain to restrict discovery to, or {@code null} for no restriction. */
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
