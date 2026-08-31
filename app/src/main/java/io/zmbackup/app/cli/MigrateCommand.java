package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Imports a bash-tool {@code sessions.txt} into the SQLite metadata store this build reads
 * exclusively, for servers moving from the bash tool - which may have stored its sessions as TXT
 * - to this Java build. Delegates the parsing to {@link io.zmbackup.core.service.MigrationService};
 * mirrors the bash tool's {@code -mg}/{@code --migrate} option in spirit, but only ever migrates
 * TXT into SQLite, since that is the only format this build understands.
 *
 * <p>Safe to run more than once, including unattended from the installer: once a {@code
 * sessions.txt} is imported it is renamed to {@code sessions.txt.migrated}, so a repeat run finds
 * nothing left to import instead of re-inserting duplicate account records.
 */
@Command(name = "migrate", description = "Import a bash-tool sessions.txt into the SQLite metadata store.")
public final class MigrateCommand implements Callable<Integer> {

    private static final String SESSIONS_TXT = "sessions.txt";
    private static final String MIGRATED_SUFFIX = ".migrated";

    @ParentCommand
    private Main parent;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.configFile());
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Path workDir = context.config().backup().workDir();
        Path sessionsTxt = workDir.resolve(SESSIONS_TXT);

        return LockedExecution.run(context, err, () -> {
            if (!Files.exists(sessionsTxt)) {
                out.println("No " + SESSIONS_TXT + " found in " + workDir + " - nothing to migrate.");
                return 0;
            }

            List<String> lines = Files.readAllLines(sessionsTxt);
            int imported = context.migrationService().importSessionsText(lines);

            Path migrated = workDir.resolve(SESSIONS_TXT + MIGRATED_SUFFIX);
            Files.move(sessionsTxt, migrated, StandardCopyOption.REPLACE_EXISTING);

            out.println("Imported " + imported + " backup session(s) from " + SESSIONS_TXT
                    + " into the SQLite metadata store.");
            out.println(SESSIONS_TXT + " renamed to " + migrated.getFileName() + ".");
            return 0;
        });
    }
}
