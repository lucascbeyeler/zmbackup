package io.zmbackup.app.config;

import java.util.Objects;

/**
 * E-mail notification settings, mirroring the {@code ENABLE_EMAIL_NOTIFY}/{@code EMAIL_NOTIFY}/
 * {@code EMAIL_SENDER} fields of the bash tool's {@code zmbackup.conf}.
 *
 * @param level     which lifecycle events to notify on
 * @param recipient the address a report is sent to after each run
 * @param sender    the address the report is sent from, e.g. {@code "root@domain"}
 */
public record EmailNotifyConfig(EmailNotifyLevel level, String recipient, String sender) {

    public EmailNotifyConfig {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(sender, "sender must not be null");
    }
}
