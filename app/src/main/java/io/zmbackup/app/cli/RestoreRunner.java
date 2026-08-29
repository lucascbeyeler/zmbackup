package io.zmbackup.app.cli;

import io.zmbackup.core.domain.RestoreResult;
import java.io.PrintWriter;

/** Shared body for printing a {@link RestoreResult} from the {@code restore} subcommands. */
final class RestoreRunner {

    private RestoreRunner() {}

    /**
     * Prints the outcome of restoring {@code sessionId} to {@code out}.
     *
     * @return {@code 0} if every account (or domain) restored successfully, {@code 1} if any failed
     */
    static int printResult(PrintWriter out, String sessionId, RestoreResult result) {
        int succeeded = result.succeededCount();
        if (result.allSucceeded()) {
            out.printf("Restore session %s completed (%d/%d accounts restored)%n", sessionId, succeeded, result.total());
            return 0;
        }
        out.printf(
                "Restore session %s completed with errors (%d/%d accounts restored, %d failed)%n",
                sessionId, succeeded, result.total(), result.failedAccounts().size());
        return 1;
    }
}
