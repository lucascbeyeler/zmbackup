package io.zmbackup.app.cli;

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
        subcommands = {
            BackupCommand.class,
            RestoreCommand.class,
            ListCommand.class,
            DeleteCommand.class,
            HousekeepCommand.class
        })
public final class Main implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean helpRequested;

    @Option(names = "--config", description = "Path to zmbackup.yaml (default: ${DEFAULT-VALUE})")
    private Path configFile = YamlConfigLoader.DEFAULT_CONFIG_PATH;

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
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
