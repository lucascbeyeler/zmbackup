package io.zmbackup.app.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "accounts", subcommands = AccountsListCommand.class)
public final class AccountsCommand implements Callable<Integer> {

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
