package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.PidLock;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;

final class LockedExecution {

    private LockedExecution() {}

    static Integer run(AppContext context, PrintWriter err, Callable<Integer> body) throws Exception {
        try (PidLock lock = PidLock.acquire(context.config().backup().workDir())) {
            return body.call();
        } catch (PidLock.AlreadyRunningException e) {
            err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }
}
