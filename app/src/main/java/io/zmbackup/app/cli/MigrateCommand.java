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
