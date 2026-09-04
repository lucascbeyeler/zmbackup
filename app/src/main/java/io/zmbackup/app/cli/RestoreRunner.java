package io.zmbackup.app.cli;

import io.zmbackup.core.domain.RestoreResult;
import java.io.PrintWriter;

final class RestoreRunner {

    private RestoreRunner() {}

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
