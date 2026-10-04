package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.HumanReadableSize;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine;

final class BackupRunner {

    private BackupRunner() {}

    static int run(
            AppContext context,
            PrintWriter out,
            PrintWriter err,
            BackupType type,
            List<String> identifiers,
            List<String> domains,
            boolean force)
            throws IOException {
        boolean valid = type == BackupType.DOMAIN
                ? CliValidation.validateDomains(identifiers, err)
                : CliValidation.validateEmails(identifiers, err) && CliValidation.validateDomains(domains, err);
        if (!valid) {
            return CommandLine.ExitCode.USAGE;
        }
        Optional<BackupSession> result = context.backupService().backup(type, identifiers, domains, force);
        if (result.isEmpty()) {
            out.println("Nothing found to back up for " + type.sessionPrefix() + ".");
            return 0;
        }
        BackupSession session = result.get();
        out.printf(
                "Session %s (%s): %s, size %s%n",
                session.sessionId(),
                session.type(),
                session.status(),
                session.size() == null ? "-" : HumanReadableSize.format(session.size()));
        return session.status() == SessionStatus.FINISHED ? 0 : 1;
    }
}
