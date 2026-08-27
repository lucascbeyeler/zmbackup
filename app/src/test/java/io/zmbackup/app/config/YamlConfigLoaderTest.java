package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigLoaderTest {

    @TempDir
    Path tempDir;

    private static final String FULL_YAML =
            """
            zimbraLdap:
              url: ldap://ldap.example.com:389
              bindDn: uid=zimbra,cn=admins,cn=zimbra
              bindPassword: secret
              sslEnabled: false
            zimbraMailbox:
              backupUser: zimbra
              zmmailboxPath: /opt/zimbra/bin/zmmailbox
              restBaseUrl: https://127.0.0.1:7071
              adminUser: zimbra
              adminPassword: secret
              backupInactiveAccounts: false
            backup:
              workDir: /opt/zimbra/backup
              logFile: /opt/zimbra/log/zmbackup.log
              blockedListFile: /etc/zmbackup/blockedlist.conf
              maxParallelProcesses: 5
              rotateDays: 14
              lockBackup: false
              emailNotify:
                level: ERROR
                recipient: admin@example.com
                sender: root@example.com
            """;

    private static final String MINIMAL_YAML =
            """
            zimbraLdap:
              url: ldap://127.0.0.1:389
              bindDn: uid=zimbra,cn=admins,cn=zimbra
              bindPassword: secret
            zimbraMailbox:
              backupUser: zimbra
              zmmailboxPath: /opt/zimbra/bin/zmmailbox
              restBaseUrl: https://127.0.0.1:7071
              adminUser: zimbra
              adminPassword: secret
            backup:
              workDir: /opt/zimbra/backup
              logFile: /opt/zimbra/log/zmbackup.log
              blockedListFile: /etc/zmbackup/blockedlist.conf
              emailNotify:
                recipient: admin@example.com
                sender: root@example.com
            """;

    @Test
    void parsesAllFields() {
        AppConfig config = YamlConfigLoader.load(new StringReader(FULL_YAML));

        assertEquals("ldap://ldap.example.com:389", config.zimbraLdap().url());
        assertEquals("uid=zimbra,cn=admins,cn=zimbra", config.zimbraLdap().bindDn());
        assertEquals("secret", config.zimbraLdap().bindPassword());
        assertFalseSsl(config);

        assertEquals("zimbra", config.zimbraMailbox().backupUser());
        assertEquals(Path.of("/opt/zimbra/bin/zmmailbox"), config.zimbraMailbox().zmmailboxPath());
        assertEquals(false, config.zimbraMailbox().backupInactiveAccounts());
        assertEquals("https://127.0.0.1:7071", config.zimbraMailbox().restBaseUrl());
        assertEquals("zimbra", config.zimbraMailbox().adminUser());
        assertEquals("secret", config.zimbraMailbox().adminPassword());

        assertEquals(Path.of("/opt/zimbra/backup"), config.backup().workDir());
        assertEquals(Path.of("/opt/zimbra/log/zmbackup.log"), config.backup().logFile());
        assertEquals(Path.of("/etc/zmbackup/blockedlist.conf"), config.backup().blockedListFile());
        assertEquals(5, config.backup().maxParallelProcesses());
        assertEquals(14, config.backup().rotateDays());
        assertEquals(false, config.backup().lockBackup());
        assertEquals(EmailNotifyLevel.ERROR, config.backup().emailNotify().level());
        assertEquals("admin@example.com", config.backup().emailNotify().recipient());
        assertEquals("root@example.com", config.backup().emailNotify().sender());
    }

    private static void assertFalseSsl(AppConfig config) {
        assertEquals(false, config.zimbraLdap().sslEnabled());
    }

    @Test
    void appliesDefaultsForOptionalFields() {
        AppConfig config = YamlConfigLoader.load(new StringReader(MINIMAL_YAML));

        assertTrue(config.zimbraLdap().sslEnabled());
        assertTrue(config.zimbraMailbox().backupInactiveAccounts());
        assertEquals(3, config.backup().maxParallelProcesses());
        assertEquals(30, config.backup().rotateDays());
        assertTrue(config.backup().lockBackup());
        assertEquals(EmailNotifyLevel.ALL, config.backup().emailNotify().level());
    }

    @Test
    void loadsFromFile() throws IOException {
        Path configFile = tempDir.resolve("zmbackup.yaml");
        Files.writeString(configFile, MINIMAL_YAML);

        AppConfig config = YamlConfigLoader.load(configFile);

        assertEquals("ldap://127.0.0.1:389", config.zimbraLdap().url());
    }

    @Test
    void missingFileThrowsConfigException() {
        Path missing = tempDir.resolve("does-not-exist.yaml");

        ConfigException exception = assertThrows(ConfigException.class, () -> YamlConfigLoader.load(missing));
        assertTrue(exception.getMessage().contains("does-not-exist.yaml"));
    }

    @Test
    void emptyFileThrowsConfigException() {
        ConfigException exception =
                assertThrows(ConfigException.class, () -> YamlConfigLoader.load(new StringReader("")));
        assertEquals("Config file is empty", exception.getMessage());
    }

    @Test
    void missingRequiredFieldThrowsConfigException() {
        String yaml =
                """
                zimbraMailbox:
                  backupUser: zimbra
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
                  restBaseUrl: https://127.0.0.1:7071
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: /opt/zimbra/backup
                  logFile: /opt/zimbra/log/zmbackup.log
                  blockedListFile: /etc/zmbackup/blockedlist.conf
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """;

        ConfigException exception =
                assertThrows(ConfigException.class, () -> YamlConfigLoader.load(new StringReader(yaml)));
        assertEquals("Missing required config value: zimbraLdap.url", exception.getMessage());
    }

    @Test
    void nonMappingSectionThrowsConfigException() {
        String yaml =
                """
                zimbraLdap: not-a-mapping
                zimbraMailbox:
                  backupUser: zimbra
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
                  restBaseUrl: https://127.0.0.1:7071
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: /opt/zimbra/backup
                  logFile: /opt/zimbra/log/zmbackup.log
                  blockedListFile: /etc/zmbackup/blockedlist.conf
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """;

        ConfigException exception =
                assertThrows(ConfigException.class, () -> YamlConfigLoader.load(new StringReader(yaml)));
        assertEquals("Expected a mapping at 'zimbraLdap.url'", exception.getMessage());
    }

    @Test
    void wrongTypeThrowsConfigException() {
        String yaml =
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:389
                  bindDn: uid=zimbra,cn=admins,cn=zimbra
                  bindPassword: secret
                  sslEnabled: not-a-boolean
                zimbraMailbox:
                  backupUser: zimbra
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
                  restBaseUrl: https://127.0.0.1:7071
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: /opt/zimbra/backup
                  logFile: /opt/zimbra/log/zmbackup.log
                  blockedListFile: /etc/zmbackup/blockedlist.conf
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """;

        ConfigException exception =
                assertThrows(ConfigException.class, () -> YamlConfigLoader.load(new StringReader(yaml)));
        assertTrue(exception.getMessage().contains("zimbraLdap.sslEnabled"));
    }

    @Test
    void invalidEmailNotifyLevelThrowsConfigException() {
        String yaml =
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:389
                  bindDn: uid=zimbra,cn=admins,cn=zimbra
                  bindPassword: secret
                zimbraMailbox:
                  backupUser: zimbra
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
                  restBaseUrl: https://127.0.0.1:7071
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: /opt/zimbra/backup
                  logFile: /opt/zimbra/log/zmbackup.log
                  blockedListFile: /etc/zmbackup/blockedlist.conf
                  emailNotify:
                    level: LOUD
                    recipient: admin@example.com
                    sender: root@example.com
                """;

        ConfigException exception =
                assertThrows(ConfigException.class, () -> YamlConfigLoader.load(new StringReader(yaml)));
        assertTrue(exception.getMessage().contains("backup.emailNotify.level"));
    }
}
