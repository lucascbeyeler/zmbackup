package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "truncate",
        description = "Empty the backup metadata database. TEST/DEV USE ONLY - never run this against production.")
public final class TruncateCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Option(
            names = "--force-clean",
            description = "Confirm the truncate - required, since this action is irreversible.")
    private boolean forceClean;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        if (!forceClean) {
            err.println("This action is irreversible: it permanently deletes every backup session and "
                    + "account record from the database (the backup files on disk are left untouched). "
                    + "Only ever use it against a test/development installation - never production, "
                    + "since the deleted history cannot be recovered. Re-run with --force-clean to confirm.");
            return CommandLine.ExitCode.USAGE;
        }

        AppContext context = AppContext.fromConfigFile(parent.configFile());
        return LockedExecution.run(context, err, () -> {
            out.println("TEST/DEV USE ONLY - truncating the backup metadata database. "
                    + "The backup files on disk are not affected.");
            int removed = context.sessionService().truncateDatabase();
            out.println(removed + " backup session(s) removed from the database.");
            return 0;
        });
    }
}
