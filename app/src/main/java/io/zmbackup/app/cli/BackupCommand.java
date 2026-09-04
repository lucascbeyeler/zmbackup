package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "backup",
        subcommands = {
            BackupFullCommand.class,
            BackupIncrementalCommand.class,
            BackupMailboxCommand.class,
            BackupLdapCommand.class,
            BackupAliasCommand.class,
            BackupDistlistCommand.class,
            BackupSignatureCommand.class,
            BackupDomainCommand.class
        })
public final class BackupCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Spec
    private CommandSpec spec;

    Main parent() {
        return parent;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.USAGE;
    }
}
