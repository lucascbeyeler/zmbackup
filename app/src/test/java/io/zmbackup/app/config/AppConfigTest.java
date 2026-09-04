package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    private static final ZimbraLdapConfig LDAP_CONFIG = new ZimbraLdapConfig(
            "ldap://ldap.example.com:389", "uid=zimbra,cn=admins,cn=zimbra", "ldap-s3cr3t", true, null, false, 600);

    private static final ZimbraMailboxConfig MAILBOX_CONFIG = new ZimbraMailboxConfig(
            "zimbra", true, "https://mail.example.com:7071", "zimbra", "mailbox-s3cr3t", null, false);

    private static final BackupConfig BACKUP_CONFIG = new BackupConfig(
            Path.of("/opt/zimbra/backup"),
            Path.of("/opt/zimbra/log/zmbackup.log"),
            Path.of("/etc/zmbackup/blockedlist.conf"),
            3,
            30,
            true,
            new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com"));

    @Test
    void rejectsNullZimbraLdap() {
        assertThrows(NullPointerException.class, () -> new AppConfig(null, MAILBOX_CONFIG, BACKUP_CONFIG, false));
    }

    @Test
    void rejectsNullZimbraMailbox() {
        assertThrows(NullPointerException.class, () -> new AppConfig(LDAP_CONFIG, null, BACKUP_CONFIG, false));
    }

    @Test
    void rejectsNullBackup() {
        assertThrows(NullPointerException.class, () -> new AppConfig(LDAP_CONFIG, MAILBOX_CONFIG, null, false));
    }

    @Test
    void toStringDoesNotLeakNestedSecrets() {
        AppConfig config = new AppConfig(LDAP_CONFIG, MAILBOX_CONFIG, BACKUP_CONFIG, false);

        String result = config.toString();

        assertFalse(result.contains("ldap-s3cr3t"), "toString() must not leak the LDAP bind password: " + result);
        assertFalse(
                result.contains("mailbox-s3cr3t"), "toString() must not leak the mailbox admin password: " + result);
        assertTrue(result.contains("bindPassword=***"));
        assertTrue(result.contains("adminPassword=***"));
    }
}
