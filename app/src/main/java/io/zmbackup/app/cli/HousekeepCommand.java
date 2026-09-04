package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.service.HousekeepService;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "housekeep", description = "Prune old and empty backup sessions.")
public final class HousekeepCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.configFile());
        PrintWriter out = spec.commandLine().getOut();
        HousekeepService housekeepService = context.housekeepService();

        return LockedExecution.run(context, spec.commandLine().getErr(), () -> {
            out.println("Removing old backup sessions - please wait.");
            List<BackupSession> rotated =
                    housekeepService.rotateOldSessions(context.config().backup().rotateDays());
            for (BackupSession session : rotated) {
                out.println("Backup session " + session.sessionId() + " removed.");
            }

            out.println("Removing empty files - please wait.");
            int emptyFilesRemoved = housekeepService.cleanEmpty();
            out.println(emptyFilesRemoved + " empty file(s) removed.");

            return 0;
        });
    }
}
