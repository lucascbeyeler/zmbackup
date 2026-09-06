package io.zmbackup.app.config;

import java.util.Objects;

public record EmailNotifyConfig(
        EmailNotifyLevel level, String recipient, String sender, String smtpHost, int smtpPort) {

    public static final String DEFAULT_SMTP_HOST = "localhost";
    public static final int DEFAULT_SMTP_PORT = 25;

    public EmailNotifyConfig {
        Objects.requireNonNull(level, "level must not be null");
        if (level != EmailNotifyLevel.NONE) {
            Objects.requireNonNull(recipient, "recipient must not be null unless level is NONE");
            Objects.requireNonNull(sender, "sender must not be null unless level is NONE");
        }
        Objects.requireNonNull(smtpHost, "smtpHost must not be null");
        if (smtpPort < 1 || smtpPort > 65535) {
            throw new IllegalArgumentException("smtpPort must be between 1 and 65535");
        }
    }
}
