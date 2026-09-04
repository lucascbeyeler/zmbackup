package io.zmbackup.app.cli;

import io.zmbackup.app.PrivilegeException;
import io.zmbackup.app.config.YamlConfigLoader;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "zmbackup",
        versionProvider = VersionProvider.class,
        subcommands = {
            BackupCommand.class,
            RestoreCommand.class,
            ListCommand.class,
            DeleteCommand.class,
            HousekeepCommand.class,
            AccountsCommand.class,
            MigrateCommand.class,
            TruncateCommand.class
        })
public final class Main implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean helpRequested;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Show the zmbackup version.")
    boolean versionRequested;

    @Option(names = "--config", description = "Path to zmbackup.yaml (default: ${DEFAULT-VALUE})")
    private Path configFile = YamlConfigLoader.DEFAULT_CONFIG_PATH;

    @Option(names = "--stacktrace", description = "Show the full stack trace when a command fails.")
    private boolean stacktraceRequested;

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        Main main = new Main();
        CommandLine cmd = new CommandLine(main);
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            if (ex instanceof PrivilegeException) {
                commandLine.getErr().println(ex.getMessage());
                return CommandLine.ExitCode.USAGE;
            }
            if (main.stacktraceRequested) {
                ex.printStackTrace(commandLine.getErr());
            } else {
                commandLine.getErr().println(ex.getClass().getName()
                        + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
                commandLine.getErr().println("(Run with --stacktrace to get the full stack trace.)");
            }
            return CommandLine.ExitCode.SOFTWARE;
        });
        return cmd;
    }

    public Path configFile() {
        return configFile;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.USAGE;
    }
}
