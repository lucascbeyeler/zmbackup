package io.zmbackup.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A single backup run, mirroring the {@code backup_session} table in {@code sessions.sqlite3}.
 *
 * @param sessionId    unique session identifier, e.g. {@code "full-20260101120000"}
 * @param type         the kind of backup that was run
 * @param status       the session's current lifecycle state
 * @param startedAt    when the session started
 * @param completedAt  when the session finished, or {@code null} while still in progress
 * @param size         human-readable total size (e.g. {@code "10M"}), or {@code null} until complete
 */
public record BackupSession(
        String sessionId,
        BackupType type,
        SessionStatus status,
        Instant startedAt,
        Instant completedAt,
        String size) {

    public BackupSession {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
