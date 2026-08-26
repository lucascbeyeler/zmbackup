package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BackupSessionTest {

    @Test
    void inProgressSessionHasNoCompletionOrSize() {
        Instant startedAt = Instant.parse("2026-01-01T12:00:00Z");
        BackupSession session = new BackupSession(
                "full-20260101120000", BackupType.FULL, SessionStatus.IN_PROGRESS, startedAt, null, null);

        assertEquals("full-20260101120000", session.sessionId());
        assertEquals(BackupType.FULL, session.type());
        assertEquals(SessionStatus.IN_PROGRESS, session.status());
        assertEquals(startedAt, session.startedAt());
        assertNull(session.completedAt());
        assertNull(session.size());
    }

    @Test
    void finishedSessionCarriesCompletionAndSize() {
        Instant startedAt = Instant.parse("2026-01-01T12:00:00Z");
        Instant completedAt = Instant.parse("2026-01-01T12:05:00Z");
        BackupSession session = new BackupSession(
                "full-20260101120000", BackupType.FULL, SessionStatus.FINISHED, startedAt, completedAt, "10M");

        assertEquals(completedAt, session.completedAt());
        assertEquals("10M", session.size());
    }

    @Test
    void requiresSessionId() {
        assertThrows(NullPointerException.class, () ->
                new BackupSession(null, BackupType.FULL, SessionStatus.IN_PROGRESS, Instant.now(), null, null));
    }

    @Test
    void requiresType() {
        assertThrows(NullPointerException.class, () ->
                new BackupSession("full-1", null, SessionStatus.IN_PROGRESS, Instant.now(), null, null));
    }

    @Test
    void requiresStatus() {
        assertThrows(NullPointerException.class, () ->
                new BackupSession("full-1", BackupType.FULL, null, Instant.now(), null, null));
    }

    @Test
    void requiresStartedAt() {
        assertThrows(NullPointerException.class, () ->
                new BackupSession("full-1", BackupType.FULL, SessionStatus.IN_PROGRESS, null, null, null));
    }
}
