package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BackupAccountRecordTest {

    @Test
    void unpersistedRecordHasNoId() {
        Instant startedAt = Instant.parse("2026-01-01T12:00:00Z");
        Instant completedAt = Instant.parse("2026-01-01T12:01:00Z");
        BackupAccountRecord record = new BackupAccountRecord(
                null, "full-20260101120000", "user@example.com", "1M", startedAt, completedAt);

        assertNull(record.id());
        assertEquals("full-20260101120000", record.sessionId());
        assertEquals("user@example.com", record.email());
        assertEquals("1M", record.size());
        assertEquals(startedAt, record.startedAt());
        assertEquals(completedAt, record.completedAt());
    }

    @Test
    void persistedRecordCarriesId() {
        BackupAccountRecord record = new BackupAccountRecord(
                42L, "full-1", "user@example.com", "1M", Instant.now(), null);

        assertEquals(42L, record.id());
    }

    @Test
    void requiresSessionId() {
        assertThrows(NullPointerException.class, () ->
                new BackupAccountRecord(null, null, "user@example.com", "1M", Instant.now(), null));
    }

    @Test
    void requiresEmail() {
        assertThrows(NullPointerException.class, () ->
                new BackupAccountRecord(null, "full-1", null, "1M", Instant.now(), null));
    }

    @Test
    void requiresSize() {
        assertThrows(NullPointerException.class, () ->
                new BackupAccountRecord(null, "full-1", "user@example.com", null, Instant.now(), null));
    }

    @Test
    void requiresStartedAt() {
        assertThrows(NullPointerException.class, () ->
                new BackupAccountRecord(null, "full-1", "user@example.com", "1M", null, null));
    }
}
