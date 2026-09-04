package io.zmbackup.core.domain;

import java.time.Instant;
import java.util.Objects;

public record BackupAccountRecord(
        Long id,
        String sessionId,
        String email,
        String size,
        Instant startedAt,
        Instant completedAt) {

    public BackupAccountRecord {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(size, "size must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
