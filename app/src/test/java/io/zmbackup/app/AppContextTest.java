package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.BackupConfig;
import io.zmbackup.app.config.ConfigException;
import io.zmbackup.app.config.EmailNotifyConfig;
import io.zmbackup.app.config.EmailNotifyLevel;
import io.zmbackup.app.config.ZimbraLdapConfig;
import io.zmbackup.app.config.ZimbraMailboxConfig;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import io.zmbackup.zimbra.ZimbraRestMailboxExporter;
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
        assertInstanceOf(ZimbraRestMailboxExporter.class, context.mailboxExporter());
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
                  backupUser: %s
                  restBaseUrl: https://127.0.0.1:7071
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: %s
                  logFile: %s
                  blockedListFile: %s
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """
                        .formatted(
                                System.getProperty("user.name"),
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf")));

        AppContext context = AppContext.fromConfigFile(configFile);

        assertEquals("ldap://127.0.0.1:389", context.config().zimbraLdap().url());
        assertTrue(Files.exists(tempDir.resolve("sessions.sqlite3")));
    }

    @Test
    void constructorRejectsMismatchedBackupUser() {
        AppConfig config = configWithWorkDir(tempDir, "not-the-real-user");

        PrivilegeException exception = assertThrows(PrivilegeException.class, () -> new AppContext(config));

        assertEquals("You need to be not-the-real-user to run this software.", exception.getMessage());
    }

    @Test
    void constructorRefusesInsecureLdapSslWithoutAllowInsecure() {
        AppConfig config = configWithSslEnabled(tempDir, false, false);

        ConfigException exception = assertThrows(ConfigException.class, () -> new AppContext(config));

        assertTrue(exception.getMessage().startsWith("zimbraLdap.sslEnabled is false"));
    }

    @Test
    void constructorAllowsInsecureLdapSslWhenAllowInsecureIsSet() throws IOException {
        AppConfig config = configWithSslEnabled(tempDir, false, true);

        AppContext context = new AppContext(config);

        assertEquals(config, context.config());
    }

    @Test
    void constructorRefusesMailboxTrustAllCertificatesWithoutAllowInsecure() {
        AppConfig config = configWithMailboxTrustAllCertificates(tempDir, false);

        ConfigException exception = assertThrows(ConfigException.class, () -> new AppContext(config));

        assertTrue(exception.getMessage().startsWith("zimbraMailbox.trustAllCertificates is true"));
    }

    @Test
    void constructorAllowsMailboxTrustAllCertificatesWhenAllowInsecureIsSet() throws IOException {
        AppConfig config = configWithMailboxTrustAllCertificates(tempDir, true);

        AppContext context = new AppContext(config);

        assertEquals(config, context.config());
    }

    private static AppConfig configWithWorkDir(Path workDir) {
        return configWithWorkDir(workDir, System.getProperty("user.name"));
    }

    private static AppConfig configWithWorkDir(Path workDir, String backupUser) {
        return new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true, null, false),
                new ZimbraMailboxConfig(backupUser, true, "https://127.0.0.1:7071", "zimbra", "secret", null, false),
                new BackupConfig(
                        workDir,
                        workDir.resolve("zmbackup.log"),
                        workDir.resolve("blockedlist.conf"),
                        3,
                        30,
                        true,
                        new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com")),
                false);
    }

    private static AppConfig configWithSslEnabled(Path workDir, boolean sslEnabled, boolean allowInsecure) {
        return new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389",
                        "uid=zimbra,cn=admins,cn=zimbra",
                        "secret",
                        sslEnabled,
                        null,
                        false),
                new ZimbraMailboxConfig(
                        System.getProperty("user.name"),
                        true,
                        "https://127.0.0.1:7071",
                        "zimbra",
                        "secret",
                        null,
                        false),
                new BackupConfig(
                        workDir,
                        workDir.resolve("zmbackup.log"),
                        workDir.resolve("blockedlist.conf"),
                        3,
                        30,
                        true,
                        new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com")),
                allowInsecure);
    }

    private static AppConfig configWithMailboxTrustAllCertificates(Path workDir, boolean allowInsecure) {
        return new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true, null, false),
                new ZimbraMailboxConfig(
                        System.getProperty("user.name"),
                        true,
                        "https://127.0.0.1:7071",
                        "zimbra",
                        "secret",
                        null,
                        true),
                new BackupConfig(
                        workDir,
                        workDir.resolve("zmbackup.log"),
                        workDir.resolve("blockedlist.conf"),
                        3,
                        30,
                        true,
                        new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com")),
                allowInsecure);
    }
}
