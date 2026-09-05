package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.CloudMigrationResult;
import io.zmbackup.core.service.CloudMigrationService;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "migrate-to-cloud",
        description = "Move an existing Phase 1 install's backup data into the configured cloud backend.")
public final class MigrateToCloudCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Spec
    private CommandSpec spec;

    @Option(
            names = "--source-dir",
            required = true,
            description = "Phase 1 local backup directory to migrate from.")
    private Path sourceDir;

    @Option(names = "--source-db", required = true, description = "Phase 1 sessions.sqlite3 file to migrate from.")
    private Path sourceDb;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.configFile());
        PrintWriter out = spec.commandLine().getOut();

        return LockedExecution.run(context, spec.commandLine().getErr(), () -> {
            try (SqliteMetadataStore sourceMetadata = new SqliteMetadataStore(sourceDb)) {
                LocalStorageProvider sourceStorage = new LocalStorageProvider(sourceDir);
                CloudMigrationService migrationService = new CloudMigrationService(
                        sourceStorage, sourceMetadata, context.storageProvider(), context.metadataStore());
                CloudMigrationResult result = migrationService.migrate();
                out.println("Migrated " + result.sessionsMigrated() + " backup session(s) and "
                        + result.accountsMigrated() + " account record(s) from " + sourceDir + " into the cloud.");
                return 0;
            }
        });
    }
}
