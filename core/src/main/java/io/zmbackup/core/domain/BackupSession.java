package io.zmbackup.core.domain;

import java.time.Instant;
import java.util.Objects;

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
