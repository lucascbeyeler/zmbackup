package io.zmbackup.core.domain;

public enum SessionStatus {
    IN_PROGRESS("IN PROGRESS"),
    FINISHED("FINISHED"),
    FAILED("FAILED");

    private final String dbValue;

    SessionStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static SessionStatus fromDbValue(String dbValue) {
        for (SessionStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown session status: " + dbValue);
    }
}
