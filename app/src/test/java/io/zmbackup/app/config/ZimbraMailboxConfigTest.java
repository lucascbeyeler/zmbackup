package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZimbraMailboxConfigTest {

    @Test
    void toStringRedactsAdminPassword() {
        ZimbraMailboxConfig config =
                new ZimbraMailboxConfig("zimbra", false, "https://mail.example.com:7071", "zimbra", "s3cr3t");

        String result = config.toString();

        assertFalse(result.contains("s3cr3t"), "toString() must not leak adminPassword: " + result);
        assertTrue(result.contains("adminPassword=***"));
        assertTrue(result.contains("https://mail.example.com:7071"));
        assertTrue(result.contains("backupUser=zimbra"));
    }
}
