package io.zmbackup.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A single account's backup within a session, mirroring the {@code backup_account} table in
 * {@code sessions.sqlite3}.
 *
 * @param id           row ID, or {@code null} before the record has been persisted
 * @param sessionId    the {@link BackupSession#sessionId()} this account backup belongs to
 * @param email        the account's email address
 * @param size         human-readable size of the backed-up data (e.g. {@code "10M"})
 * @param startedAt    when this account's backup started
 * @param completedAt  when this account's backup finished, or {@code null} while still in progress
 */
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
