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

/**
 * Picocli entry point. Dispatches to one of the subcommands, each of which turns the
 * {@code --config} option into a wired {@link io.zmbackup.app.AppContext}.
 */
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

    /**
     * Builds the {@link CommandLine} with a handler that reports {@link PrivilegeException}
     * cleanly (message + the usage exit code, mirroring the bash tool's {@code validate_config}
     * {@code exit 2}) instead of picocli's default stack-trace dump, and otherwise prints just the
     * failure's message rather than its full stack trace - which, for an admin-facing CLI run
     * normally rather than under a debugger, is noise that can also expose internal class/file
     * detail with no actionable value. {@code --stacktrace} (mirroring Gradle's own flag of the
     * same name/purpose) opts back into the full trace for diagnosing an unexpected failure.
     */
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

    /** The config file passed via {@code --config}, or {@link YamlConfigLoader#DEFAULT_CONFIG_PATH}. */
    public Path configFile() {
        return configFile;
    }

    /** No subcommand given: show usage instead of doing nothing silently. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.USAGE;
    }
}
