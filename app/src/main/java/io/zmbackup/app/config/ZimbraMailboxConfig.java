package io.zmbackup.app.config;

import java.util.Objects;

public record ZimbraMailboxConfig(
        String backupUser,
        boolean backupInactiveAccounts,
        String restBaseUrl,
        String adminUser,
        String adminPassword,
        String caCertificatePath,
        boolean trustAllCertificates) {

    public ZimbraMailboxConfig {
        Objects.requireNonNull(backupUser, "backupUser must not be null");
        Objects.requireNonNull(restBaseUrl, "restBaseUrl must not be null");
        Objects.requireNonNull(adminUser, "adminUser must not be null");
        Objects.requireNonNull(adminPassword, "adminPassword must not be null");
    }

    @Override
    public String toString() {
        return "ZimbraMailboxConfig[backupUser=" + backupUser
                + ", backupInactiveAccounts=" + backupInactiveAccounts
                + ", restBaseUrl=" + restBaseUrl
                + ", adminUser=" + adminUser
                + ", adminPassword=***"
                + ", caCertificatePath=" + caCertificatePath
                + ", trustAllCertificates=" + trustAllCertificates
                + "]";
    }
}
