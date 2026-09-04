package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.PidLock;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * Wraps a mutating subcommand's body in a {@link PidLock} on the backup work directory, so two
 * zmbackup processes can never back up, restore, delete, or housekeep the same store at once.
 */
final class LockedExecution {

    private LockedExecution() {}

    /**
     * Runs {@code body} while holding the lock, or prints an error and returns {@link
     * CommandLine.ExitCode#SOFTWARE} if it's already held.
     */
    static Integer run(AppContext context, PrintWriter err, Callable<Integer> body) throws Exception {
        try (PidLock lock = PidLock.acquire(context.config().backup().workDir())) {
            return body.call();
        } catch (PidLock.AlreadyRunningException e) {
            err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }
}
