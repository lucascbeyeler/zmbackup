package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionStatusTest {

    @Test
    void dbValuesMatchBashToolConventions() {
        assertEquals("IN PROGRESS", SessionStatus.IN_PROGRESS.dbValue());
        assertEquals("FINISHED", SessionStatus.FINISHED.dbValue());
        assertEquals("FAILED", SessionStatus.FAILED.dbValue());
    }

    @Test
    void fromDbValueRoundTrips() {
        for (SessionStatus status : SessionStatus.values()) {
            assertEquals(status, SessionStatus.fromDbValue(status.dbValue()));
        }
    }

    @Test
    void fromDbValueRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> SessionStatus.fromDbValue("BOGUS"));
    }
}
