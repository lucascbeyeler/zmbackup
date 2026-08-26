package io.zmbackup.core.domain;

/** The lifecycle state of a {@link BackupSession}. */
public enum SessionStatus {
    IN_PROGRESS("IN PROGRESS"),
    FINISHED("FINISHED"),
    FAILED("FAILED");

    private final String dbValue;

    SessionStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The value stored in the {@code backup_session.status} column by the bash tool. */
    public String dbValue() {
        return dbValue;
    }

    /** Resolves the status matching a value previously produced by {@link #dbValue()}. */
    public static SessionStatus fromDbValue(String dbValue) {
        for (SessionStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown session status: " + dbValue);
    }
}
