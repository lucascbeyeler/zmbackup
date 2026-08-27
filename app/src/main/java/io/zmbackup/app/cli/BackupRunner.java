package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/** Shared body for the {@code backup ldap/alias/distlist/signature/domain} subcommands. */
final class BackupRunner {

    private BackupRunner() {}

    /**
     * Runs one backup session of {@code type} against {@code context}, printing its outcome to
     * {@code out}.
     *
     * @return {@code 0} if the session finished (or there was nothing to back up), {@code 1} if
     *     any object in the session failed to export
     */
    static int run(
            AppContext context, PrintWriter out, BackupType type, List<String> identifiers, String domain)
            throws IOException {
        Optional<BackupSession> result = context.backupService().backup(type, identifiers, domain);
        if (result.isEmpty()) {
            out.println("Nothing found to back up for " + type.sessionPrefix() + ".");
            return 0;
        }
        BackupSession session = result.get();
        out.printf(
                "Session %s (%s): %s, size %s%n",
                session.sessionId(), session.type(), session.status(), session.size());
        return session.status() == SessionStatus.FINISHED ? 0 : 1;
    }
}
