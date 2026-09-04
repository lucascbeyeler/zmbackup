package io.zmbackup.app.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.regex.Pattern;

final class CliValidation {

    private static final Pattern EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern SESSION_ID =
            Pattern.compile("^(full|inc|ldap|domain|distlist|alias|mbox|signature)-[0-9]{14}$");

    private CliValidation() {}

    static boolean validateEmails(List<String> emails, PrintWriter err) {
        for (String email : emails) {
            if (!EMAIL.matcher(email).matches()) {
                err.println("Error! Invalid email address: " + email);
                return false;
            }
        }
        return true;
    }

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

    static boolean validateDomains(List<String> domains, PrintWriter err) {
        for (String domain : domains) {
            if (!DOMAIN.matcher(domain).matches()) {
                err.println("Error! Invalid domain name: " + domain);
                return false;
            }
        }
        return true;
    }

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

    static boolean validateSessionId(String sessionId, PrintWriter err) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            err.println("Error! Invalid session ID: " + sessionId);
            return false;
        }
        return true;
    }

    static boolean validateIntoRequiresSingleAccount(
            String commandName, String destination, List<String> accounts, PrintWriter err) {
        if (destination != null && accounts.size() != 1) {
            err.println(commandName + ": --into requires exactly one --account");
            return false;
        }
        return true;
    }

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
