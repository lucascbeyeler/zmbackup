package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZimbraLdapConfigTest {

    @Test
    void toStringRedactsBindPassword() {
        ZimbraLdapConfig config = new ZimbraLdapConfig(
                "ldap://ldap.example.com:389",
                "uid=zimbra,cn=admins,cn=zimbra",
                "s3cr3t",
                true,
                "/etc/zmbackup/ldap-ca.pem",
                false);

        String result = config.toString();

        assertFalse(result.contains("s3cr3t"), "toString() must not leak bindPassword: " + result);
        assertTrue(result.contains("bindPassword=***"));
        assertTrue(result.contains("ldap://ldap.example.com:389"));
        assertTrue(result.contains("uid=zimbra,cn=admins,cn=zimbra"));
    }
}
