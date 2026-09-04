package io.zmbackup.app.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CLI-level input format validation for {@code --account}/{@code --domain}/{@code --session}
 * values, mirroring the bash tool's {@code validate_email}/{@code validate_domain}/{@code
 * validate_session_id} ({@code MiscAction.sh}), applied before {@code build_listBKP}/{@code
 * backup_main} in {@code validate_account_args} and before each {@code -r} branch's own session
 * lookup in {@code project/zmbackup}. Rejecting malformed input here, before it reaches an LDAP
 * filter, file path, or SQL parameter, gives an immediate, explicit error instead of the value
 * failing deeper in the stack (e.g. an LDAP search returning zero results, or a restore reporting
 * "0/0 accounts restored" for a session that could never have existed).
 */
final class CliValidation {

    private static final Pattern EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern SESSION_ID =
            Pattern.compile("^(full|inc|ldap|domain|distlist|alias|mbox|signature)-[0-9]{14}$");

    private CliValidation() {}

    /**
     * Validates every value in {@code emails} against the bash tool's email pattern, printing
     * {@code "Error! Invalid email address: <value>"} to {@code err} and returning {@code false}
     * on the first mismatch.
     */
    static boolean validateEmails(List<String> emails, PrintWriter err) {
        for (String email : emails) {
            if (!EMAIL.matcher(email).matches()) {
                err.println("Error! Invalid email address: " + email);
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a single email value (e.g. {@code --into}), printing {@code "Error! Invalid email
     * address: <value>"} to {@code err} and returning {@code false} on a mismatch. {@code null} is
     * treated as valid (an absent optional value).
     */
    static boolean validateEmail(String email, PrintWriter err) {
        if (email == null) {
            return true;
        }
        if (!EMAIL.matcher(email).matches()) {
            err.println("Error! Invalid email address: " + email);
            return false;
        }
        return true;
    }

    /**
     * Validates every value in {@code domains} against the bash tool's domain pattern, printing
     * {@code "Error! Invalid domain name: <value>"} to {@code err} and returning {@code false} on
     * the first mismatch.
     */
    static boolean validateDomains(List<String> domains, PrintWriter err) {
        for (String domain : domains) {
            if (!DOMAIN.matcher(domain).matches()) {
                err.println("Error! Invalid domain name: " + domain);
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a single domain value, printing {@code "Error! Invalid domain name: <value>"} to
     * {@code err} and returning {@code false} on a mismatch. {@code null} is treated as valid (an
     * absent optional value).
     */
    static boolean validateDomain(String domain, PrintWriter err) {
        if (domain == null) {
            return true;
        }
        if (!DOMAIN.matcher(domain).matches()) {
            err.println("Error! Invalid domain name: " + domain);
            return false;
        }
        return true;
    }

    /**
     * Validates {@code sessionId} against the bash tool's {@code {prefix}-{14-digit timestamp}}
     * session ID pattern, printing {@code "Error! Invalid session ID: <value>"} to {@code err} and
     * returning {@code false} on a mismatch.
     */
    static boolean validateSessionId(String sessionId, PrintWriter err) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            err.println("Error! Invalid session ID: " + sessionId);
            return false;
        }
        return true;
    }

    /**
     * Validates that {@code --into} (mailbox restore-on-account) is only given alongside exactly
     * one {@code --account}, printing {@code "<commandName>: --into requires exactly one
     * --account"} to {@code err} and returning {@code false} otherwise. A {@code null} destination
     * (the common case: no {@code --into}) is always valid, regardless of {@code accounts}' size.
     * Shared by {@code restore} and {@code restore mailbox}, the two commands that accept {@code
     * --into}, so the two can't drift on this check the way separately duplicated copies could.
     */
    static boolean validateIntoRequiresSingleAccount(
            String commandName, String destination, List<String> accounts, PrintWriter err) {
        if (destination != null && accounts.size() != 1) {
            err.println(commandName + ": --into requires exactly one --account");
            return false;
        }
        return true;
    }

    /**
     * Validates that {@code sessionId} is a full or incremental session, the only kind the
     * top-level {@code restore} command (as opposed to its {@code ldap}/{@code domain}/{@code
     * mailbox} subcommands) can restore. Prints a message pointing at the matching subcommand and
     * returns {@code false} otherwise.
     */
    static boolean validateFullOrIncrementalSessionPrefix(String sessionId, PrintWriter err) {
        if (!(sessionId.startsWith("full") || sessionId.startsWith("inc"))) {
            err.println(
                    "restore: '--session=" + sessionId
                            + "' is not a full/incremental session; use 'restore ldap', 'restore domain', or"
                            + " 'restore mailbox' to restore one kind of content on its own.");
            return false;
        }
        return true;
    }
}
