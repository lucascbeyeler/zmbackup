package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.BackupConfig;
import io.zmbackup.app.config.EmailNotifyConfig;
import io.zmbackup.app.config.EmailNotifyLevel;
import io.zmbackup.app.config.ZimbraLdapConfig;
import io.zmbackup.app.config.ZimbraMailboxConfig;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppContextTest {

    @TempDir
    Path tempDir;

    @Test
    void wiresComponentsFromConfig() throws IOException {
        AppConfig config = configWithWorkDir(tempDir);

        AppContext context = new AppContext(config);

        assertEquals(config, context.config());
        assertInstanceOf(LocalStorageProvider.class, context.storageProvider());
        assertInstanceOf(SqliteMetadataStore.class, context.metadataStore());
        assertInstanceOf(UnboundIdLdapAdapter.class, context.accountDiscovery());
        assertTrue(Files.exists(tempDir.resolve("sessions.sqlite3")));
    }

    @Test
    void fromConfigFileLoadsAndWiresComponents() throws IOException {
        Path configFile = tempDir.resolve("zmbackup.yaml");
        Files.writeString(
                configFile,
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:389
                  bindDn: uid=zimbra,cn=admins,cn=zimbra
                  bindPassword: secret
                zimbraMailbox:
                  backupUser: zimbra
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
                backup:
                  workDir: %s
                  logFile: %s
                  blockedListFile: %s
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """
                        .formatted(
                                tempDir, tempDir.resolve("zmbackup.log"), tempDir.resolve("blockedlist.conf")));

        AppContext context = AppContext.fromConfigFile(configFile);

        assertEquals("ldap://127.0.0.1:389", context.config().zimbraLdap().url());
        assertTrue(Files.exists(tempDir.resolve("sessions.sqlite3")));
    }

    private static AppConfig configWithWorkDir(Path workDir) {
        return new AppConfig(
                new ZimbraLdapConfig("ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true),
                new ZimbraMailboxConfig("zimbra", Path.of("/opt/zimbra/bin/zmmailbox"), true),
                new BackupConfig(
                        workDir,
                        workDir.resolve("zmbackup.log"),
                        workDir.resolve("blockedlist.conf"),
                        3,
                        30,
                        true,
                        new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com")));
    }
}
