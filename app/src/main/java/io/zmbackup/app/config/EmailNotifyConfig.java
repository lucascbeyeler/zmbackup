package io.zmbackup.app.config;

import java.util.Objects;

/**
 * E-mail notification settings, mirroring the {@code ENABLE_EMAIL_NOTIFY}/{@code EMAIL_NOTIFY}/
 * {@code EMAIL_SENDER} fields of the bash tool's {@code zmbackup.conf}.
 *
 * @param level     which lifecycle events to notify on
 * @param recipient the address a report is sent to after each run; required unless
 *                  {@code level} is {@link EmailNotifyLevel#NONE}, since no notification is ever
 *                  sent in that case
 * @param sender    the address the report is sent from, e.g. {@code "root@domain"}; required
 *                  unless {@code level} is {@link EmailNotifyLevel#NONE}
 */
public record EmailNotifyConfig(EmailNotifyLevel level, String recipient, String sender) {

    public EmailNotifyConfig {
        Objects.requireNonNull(level, "level must not be null");
        if (level != EmailNotifyLevel.NONE) {
            Objects.requireNonNull(recipient, "recipient must not be null unless level is NONE");
            Objects.requireNonNull(sender, "sender must not be null unless level is NONE");
        }
    }
}
