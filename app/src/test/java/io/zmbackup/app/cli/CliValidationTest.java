package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliValidationTest {

    @Test
    void validEmailPasses() {
        StringWriter err = new StringWriter();
        assertTrue(CliValidation.validateEmail("alice@example.com", new PrintWriter(err)));
        assertTrue(err.toString().isEmpty());
    }

    @Test
    void nullEmailIsTreatedAsValid() {
        assertTrue(CliValidation.validateEmail(null, new PrintWriter(new StringWriter())));
    }

    @Test
    void malformedEmailFails() {
        StringWriter err = new StringWriter();
        assertFalse(CliValidation.validateEmail("not-an-email", new PrintWriter(err)));
        assertTrue(err.toString().contains("Error! Invalid email address: not-an-email"));
    }

    @Test
    void allEmailsValidStopsAtFirstMismatch() {
        StringWriter err = new StringWriter();
        assertFalse(CliValidation.validateEmails(
                List.of("alice@example.com", "bad-email", "bob@example.com"), new PrintWriter(err)));
        assertTrue(err.toString().contains("Error! Invalid email address: bad-email"));
    }

    @Test
    void emptyEmailListIsValid() {
        assertTrue(CliValidation.validateEmails(List.of(), new PrintWriter(new StringWriter())));
    }

    @Test
    void validDomainPasses() {
        assertTrue(CliValidation.validateDomain("example.com", new PrintWriter(new StringWriter())));
    }

    @Test
    void nullDomainIsTreatedAsValid() {
        assertTrue(CliValidation.validateDomain(null, new PrintWriter(new StringWriter())));
    }

    @Test
    void malformedDomainFails() {
        StringWriter err = new StringWriter();
        assertFalse(CliValidation.validateDomain("not a domain", new PrintWriter(err)));
        assertTrue(err.toString().contains("Error! Invalid domain name: not a domain"));
    }

    @Test
    void allDomainsValidStopsAtFirstMismatch() {
        StringWriter err = new StringWriter();
        assertFalse(CliValidation.validateDomains(List.of("example.com", "??"), new PrintWriter(err)));
        assertTrue(err.toString().contains("Error! Invalid domain name: ??"));
    }

    @Test
    void validSessionIdsPassForEveryKnownPrefix() {
        PrintWriter err = new PrintWriter(new StringWriter());
        for (String prefix :
                List.of("full", "inc", "ldap", "domain", "distlist", "alias", "mbox", "signature")) {
            assertTrue(CliValidation.validateSessionId(prefix + "-20260101120000", err), prefix);
        }
    }

    @Test
    void malformedSessionIdFails() {
        StringWriter err = new StringWriter();
        assertFalse(CliValidation.validateSessionId("does-not-exist", new PrintWriter(err)));
        assertTrue(err.toString().contains("Error! Invalid session ID: does-not-exist"));
    }

    @Test
    void sessionIdWithUnknownPrefixFails() {
        assertFalse(CliValidation.validateSessionId("bogus-20260101120000", new PrintWriter(new StringWriter())));
    }

    @Test
    void sessionIdWithShortTimestampFails() {
        assertFalse(CliValidation.validateSessionId("full-202601011200", new PrintWriter(new StringWriter())));
    }
}
