package io.zmbackup.app.config;

/**
 * Which backup lifecycle events trigger an e-mail notification, mirroring the
 * {@code ENABLE_EMAIL_NOTIFY} field of the bash tool's {@code zmbackup.conf}.
 */
public enum EmailNotifyLevel {
    ALL,
    START,
    FINISH,
    ERROR,
    NONE
}
