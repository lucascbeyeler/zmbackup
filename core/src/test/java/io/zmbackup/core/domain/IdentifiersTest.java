package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void emailMatchesAWellFormedAddress() {
        assertTrue(Identifiers.EMAIL.matcher("alice@example.com").matches());
    }

    @Test
    void emailRejectsAValueWithNoAtSign() {
        assertFalse(Identifiers.EMAIL.matcher("alice.example.com").matches());
    }

    @Test
    void domainMatchesAWellFormedDomain() {
        assertTrue(Identifiers.DOMAIN.matcher("example.com").matches());
    }

    @Test
    void domainRejectsAValueContainingAnAtSign() {
        assertFalse(Identifiers.DOMAIN.matcher("alice@example.com").matches());
    }
}
