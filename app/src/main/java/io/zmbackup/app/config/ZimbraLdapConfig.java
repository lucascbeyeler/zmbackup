package io.zmbackup.app.config;

import java.util.Objects;

public record ZimbraLdapConfig(
        String url,
        String bindDn,
        String bindPassword,
        boolean sslEnabled,
        String caCertificatePath,
        boolean trustAllCertificates,
        int responseTimeoutSeconds) {

    public static final int DEFAULT_RESPONSE_TIMEOUT_SECONDS = 600;

    public ZimbraLdapConfig {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(bindDn, "bindDn must not be null");
        Objects.requireNonNull(bindPassword, "bindPassword must not be null");
        if (responseTimeoutSeconds < 1) {
            throw new IllegalArgumentException("responseTimeoutSeconds must be at least 1");
        }
    }

    @Override
    public String toString() {
        return "ZimbraLdapConfig[url=" + url
                + ", bindDn=" + bindDn
                + ", bindPassword=***"
                + ", sslEnabled=" + sslEnabled
                + ", caCertificatePath=" + caCertificatePath
                + ", trustAllCertificates=" + trustAllCertificates
                + ", responseTimeoutSeconds=" + responseTimeoutSeconds
                + "]";
    }
}
