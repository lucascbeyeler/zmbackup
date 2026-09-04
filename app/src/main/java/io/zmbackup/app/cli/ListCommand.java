package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupSession;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "list", description = "List stored backup sessions.")
public final class ListCommand implements Callable<Integer> {

    private static final String BORDER =
            "+-----------------------------+----------------------+----------------------+----------+--------------------+";
    private static final String ROW_FORMAT = "| %-27s | %-20s | %-20s | %-8s | %-18s |%n";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    @ParentCommand
    private Main parent;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.configFile());
        PrintWriter out = spec.commandLine().getOut();

        out.println(BORDER);
        out.printf(ROW_FORMAT, "Session ID", "Started", "Completed", "Size", "Type");
        out.println(BORDER);
        for (BackupSession session : context.sessionService().listSessions()) {
            out.printf(
                    ROW_FORMAT,
                    session.sessionId(),
                    formatTimestamp(session.startedAt()),
                    formatTimestamp(session.completedAt()),
                    session.size() == null ? "-" : session.size(),
                    session.type());
        }
        out.println(BORDER);
        return 0;
    }

    private static String formatTimestamp(Instant instant) {
        return instant == null ? "-" : TIMESTAMP_FORMAT.format(instant);
    }
}
