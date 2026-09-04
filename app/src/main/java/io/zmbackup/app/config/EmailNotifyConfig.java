package io.zmbackup.app.config;

import java.util.Objects;

public record EmailNotifyConfig(EmailNotifyLevel level, String recipient, String sender) {

    public EmailNotifyConfig {
        Objects.requireNonNull(level, "level must not be null");
        if (level != EmailNotifyLevel.NONE) {
            Objects.requireNonNull(recipient, "recipient must not be null unless level is NONE");
            Objects.requireNonNull(sender, "sender must not be null unless level is NONE");
        }
    }
}
