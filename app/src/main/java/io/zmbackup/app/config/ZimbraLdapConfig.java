package io.zmbackup.app.config;

import java.util.Objects;

/**
 * LDAP connection settings, mirroring the {@code LDAPSERVER}/{@code LDAPADMIN}/{@code LDAPPASS}/
 * {@code SSL_ENABLE} fields of the bash tool's {@code zmbackup.conf}.
 *
 * @param url          the LDAP server URL, e.g. {@code "ldap://127.0.0.1:389"}
 * @param bindDn       the admin distinguished name used to bind, e.g.
 *                     {@code "uid=zimbra,cn=admins,cn=zimbra"}
 * @param bindPassword the admin password used to bind
 * @param sslEnabled   whether to use SSL when talking to the Zimbra server
 */
public record ZimbraLdapConfig(String url, String bindDn, String bindPassword, boolean sslEnabled) {

    public ZimbraLdapConfig {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(bindDn, "bindDn must not be null");
        Objects.requireNonNull(bindPassword, "bindPassword must not be null");
    }
}
