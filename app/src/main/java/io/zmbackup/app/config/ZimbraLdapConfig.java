package io.zmbackup.app.config;

import java.util.Objects;

/**
 * LDAP connection settings, mirroring the {@code LDAPSERVER}/{@code LDAPADMIN}/{@code LDAPPASS}/
 * {@code SSL_ENABLE} fields of the bash tool's {@code zmbackup.conf}.
 *
 * @param url                  the LDAP server URL, e.g. {@code "ldap://127.0.0.1:389"}
 * @param bindDn               the admin distinguished name used to bind, e.g.
 *                             {@code "uid=zimbra,cn=admins,cn=zimbra"}
 * @param bindPassword         the admin password used to bind
 * @param sslEnabled           whether to use SSL (StartTLS) when talking to the Zimbra server
 * @param caCertificatePath    path to a PEM-encoded CA certificate (bundle) used to verify the
 *                             server's certificate, or {@code null} to use the JVM's default trust
 *                             manager (or, if {@code trustAllCertificates} is set, to trust any
 *                             certificate)
 * @param trustAllCertificates whether to accept any server certificate when {@code caCertificatePath}
 *                             is not set; must be explicitly enabled, since it offers no protection
 *                             against an active MITM attack
 * @param responseTimeoutSeconds how long a single LDAP operation (bind, search, add, delete) is
 *                             allowed to take once connected, before it's abandoned as hung against
 *                             a connected-but-unresponsive server; discovery over a very large
 *                             directory can legitimately take a while, so this is exposed rather
 *                             than fixed
 */
public record ZimbraLdapConfig(
        String url,
        String bindDn,
        String bindPassword,
        boolean sslEnabled,
        String caCertificatePath,
        boolean trustAllCertificates,
        int responseTimeoutSeconds) {

    /** The default {@link #responseTimeoutSeconds()} when {@code zimbraLdap.responseTimeoutSeconds} is unset. */
    public static final int DEFAULT_RESPONSE_TIMEOUT_SECONDS = 600;

    public ZimbraLdapConfig {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(bindDn, "bindDn must not be null");
        Objects.requireNonNull(bindPassword, "bindPassword must not be null");
        if (responseTimeoutSeconds < 1) {
            throw new IllegalArgumentException("responseTimeoutSeconds must be at least 1");
        }
    }

    /**
     * Overrides the record's default {@code toString()} to redact {@link #bindPassword()}, since
     * this value is logged (e.g. via an uncaught exception's stack trace) wherever a config record
     * ends up in a message.
     */
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
