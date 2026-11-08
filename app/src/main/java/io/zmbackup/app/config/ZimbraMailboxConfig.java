package io.zmbackup.app.config;

import java.nio.file.Path;
import java.util.Objects;

public record ZimbraMailboxConfig(
        String backupUser,
        boolean backupInactiveAccounts,
        String restBaseUrl,
        String adminUser,
        String adminPassword,
        String caCertificatePath,
        boolean trustAllCertificates,
        int exportHeaderTimeoutSeconds,
        int exportInactivityTimeoutSeconds,
        int restoreTimeoutSeconds,
        boolean batchedExport,
        boolean batchedRestore,
        int transferRetries,
        Path exportJournalDir) {

    public static final int DEFAULT_EXPORT_HEADER_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_EXPORT_INACTIVITY_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_RESTORE_TIMEOUT_SECONDS = 86400;
    public static final int DEFAULT_TRANSFER_RETRIES = 3;

    public ZimbraMailboxConfig {
        Objects.requireNonNull(backupUser, "backupUser must not be null");
        Objects.requireNonNull(restBaseUrl, "restBaseUrl must not be null");
        Objects.requireNonNull(adminUser, "adminUser must not be null");
        Objects.requireNonNull(adminPassword, "adminPassword must not be null");
        if (exportHeaderTimeoutSeconds < 1) {
            throw new IllegalArgumentException("exportHeaderTimeoutSeconds must be at least 1");
        }
        if (exportInactivityTimeoutSeconds < 1) {
            throw new IllegalArgumentException("exportInactivityTimeoutSeconds must be at least 1");
        }
        if (restoreTimeoutSeconds < 1) {
            throw new IllegalArgumentException("restoreTimeoutSeconds must be at least 1");
        }
        if (transferRetries < 0 || transferRetries > 20) {
            throw new IllegalArgumentException("transferRetries must be between 0 and 20");
        }
    }

    /** The settings before the transfer options existed, with every transfer option at its default. */
    public ZimbraMailboxConfig(
            String backupUser,
            boolean backupInactiveAccounts,
            String restBaseUrl,
            String adminUser,
            String adminPassword,
            String caCertificatePath,
            boolean trustAllCertificates) {
        this(backupUser, backupInactiveAccounts, restBaseUrl, adminUser, adminPassword, caCertificatePath,
                trustAllCertificates, DEFAULT_EXPORT_HEADER_TIMEOUT_SECONDS,
                DEFAULT_EXPORT_INACTIVITY_TIMEOUT_SECONDS, DEFAULT_RESTORE_TIMEOUT_SECONDS, true, true,
                DEFAULT_TRANSFER_RETRIES, null);
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
                + ", exportHeaderTimeoutSeconds=" + exportHeaderTimeoutSeconds
                + ", exportInactivityTimeoutSeconds=" + exportInactivityTimeoutSeconds
                + ", restoreTimeoutSeconds=" + restoreTimeoutSeconds
                + ", batchedExport=" + batchedExport
                + ", batchedRestore=" + batchedRestore
                + ", transferRetries=" + transferRetries
                + ", exportJournalDir=" + exportJournalDir
                + "]";
    }
}
