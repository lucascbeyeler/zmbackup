package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;

final class LockedExecution {

    private LockedExecution() {}

    static Integer run(AppContext context, PrintWriter err, Callable<Integer> body) throws Exception {
        try (RunLock lock = context.acquireRunLock()) {
            return body.call();
        } catch (LockContentionException e) {
            err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }
}
