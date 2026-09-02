package io.zmbackup.app;

/**
 * Thrown when the OS user running zmbackup doesn't match {@code zimbraMailbox.backupUser},
 * mirroring the bash tool's {@code validate_config} refusing to run unless {@code whoami ==
 * $BACKUPUSER}.
 */
public class PrivilegeException extends RuntimeException {

    public PrivilegeException(String message) {
        super(message);
    }
}
