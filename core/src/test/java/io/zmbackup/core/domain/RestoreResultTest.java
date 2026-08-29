package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RestoreResultTest {

    @Test
    void allSucceededIsTrueWhenNoFailures() {
        RestoreResult result = new RestoreResult(2, List.of());

        assertTrue(result.allSucceeded());
        assertEquals(2, result.succeededCount());
    }

    @Test
    void allSucceededIsFalseWhenSomeFailed() {
        RestoreResult result = new RestoreResult(2, List.of("bad@example.com"));

        assertFalse(result.allSucceeded());
        assertEquals(1, result.succeededCount());
    }

    @Test
    void rejectsMoreFailuresThanTotal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RestoreResult(1, List.of("a@example.com", "b@example.com")));
    }

    @Test
    void rejectsNullFailedAccounts() {
        assertThrows(NullPointerException.class, () -> new RestoreResult(1, null));
    }
}
