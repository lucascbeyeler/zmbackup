package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Groups the LDAP-only backup subcommands: {@code ldap}, {@code alias}, {@code distlist}, {@code
 * signature}, {@code domain}.
 */
@Command(
        name = "backup",
        subcommands = {
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

    /** No subcommand given: show usage instead of doing nothing silently. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.USAGE;
    }
}
