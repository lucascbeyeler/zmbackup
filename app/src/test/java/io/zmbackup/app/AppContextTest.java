package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.BackupConfig;
import io.zmbackup.app.config.ConfigException;
import io.zmbackup.app.config.DynamoDbConfig;
import io.zmbackup.app.config.EmailNotifyConfig;
import io.zmbackup.app.config.EmailNotifyLevel;
import io.zmbackup.app.config.MetadataBackend;
import io.zmbackup.app.config.MetadataConfig;
import io.zmbackup.app.config.S3Config;
import io.zmbackup.app.config.StorageBackend;
import io.zmbackup.app.config.StorageConfig;
import io.zmbackup.app.config.ZimbraLdapConfig;
import io.zmbackup.app.config.ZimbraMailboxConfig;
import io.zmbackup.aws.DynamoDBLock;
import io.zmbackup.aws.DynamoDBMetadataStore;
import io.zmbackup.aws.S3StorageProvider;
import io.zmbackup.core.port.RunLock;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import io.zmbackup.zimbra.ZimbraRestMailboxExporter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppContextTest {

    private static final StorageConfig LOCAL_STORAGE = new StorageConfig(StorageBackend.LOCAL, null);

    private static final MetadataConfig SQLITE_METADATA = new MetadataConfig(MetadataBackend.SQLITE, null);

    @BeforeAll
    static void setUpCredentials() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

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
    void wiresComponentsWhenEmailNotifyLevelIsNoneWithNoRecipientOrSender() throws IOException {
        AppConfig config = new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true, null, false, 600),
                new ZimbraMailboxConfig(
                        System.getProperty("user.name"),
                        true,
                        "https://127.0.0.1:7071",
                        "zimbra",
                        "secret",
                        null,
                        false),
                new BackupConfig(
                        tempDir,
                        tempDir.resolve("zmbackup.log"),
                        tempDir.resolve("blockedlist.conf"),
                        3,
                        30,
                        true,
                        new EmailNotifyConfig(EmailNotifyLevel.NONE, null, null)),
                LOCAL_STORAGE,
                SQLITE_METADATA,
                false);

        AppContext context = new AppContext(config);

        assertEquals(config, context.config());
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

    @Test
    void wiresS3StorageProviderAndDynamoDBLockWhenCloudBackendsAreConfigured() throws Exception {
        WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        try {
            wireMockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                            com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                    .withHeader(
                            "X-Amz-Target",
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo("DynamoDB_20120810.PutItem"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)));
            wireMockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                            com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                    .withHeader(
                            "X-Amz-Target",
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo("DynamoDB_20120810.DeleteItem"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)));
            AppConfig config = configWithCloudBackends(tempDir, URI.create(wireMockServer.baseUrl()), true);

            AppContext context = new AppContext(config);

            assertInstanceOf(S3StorageProvider.class, context.storageProvider());
            assertInstanceOf(DynamoDBMetadataStore.class, context.metadataStore());
            try (RunLock lock = context.acquireRunLock()) {
                assertInstanceOf(DynamoDBLock.class, lock);
            }
        } finally {
            wireMockServer.stop();
        }
    }

    @Test
    void constructorRefusesInsecureS3EndpointOverrideWithoutAllowInsecure() {
        AppConfig config = configWithCloudBackends(tempDir, URI.create("http://127.0.0.1:1"), false);

        ConfigException exception = assertThrows(ConfigException.class, () -> new AppContext(config));

        assertTrue(exception.getMessage().startsWith("storage.s3.endpointOverride"));
    }

    private static AppConfig configWithCloudBackends(Path workDir, URI endpointOverride, boolean allowInsecure) {
        return new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true, null, false, 600),
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
                new StorageConfig(
                        StorageBackend.S3,
                        new S3Config("test-bucket", "us-east-1", S3Config.DEFAULT_PREFIX, endpointOverride)),
                new MetadataConfig(
                        MetadataBackend.DYNAMODB,
                        new DynamoDbConfig(
                                "us-east-1",
                                DynamoDbConfig.DEFAULT_SESSION_TABLE,
                                DynamoDbConfig.DEFAULT_ACCOUNT_TABLE,
                                DynamoDbConfig.DEFAULT_LOCK_TABLE,
                                endpointOverride)),
                allowInsecure);
    }

    private static AppConfig configWithWorkDir(Path workDir) {
        return configWithWorkDir(workDir, System.getProperty("user.name"));
    }

    private static AppConfig configWithWorkDir(Path workDir, String backupUser) {
        return new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true, null, false, 600),
                new ZimbraMailboxConfig(backupUser, true, "https://127.0.0.1:7071", "zimbra", "secret", null, false),
                new BackupConfig(
                        workDir,
                        workDir.resolve("zmbackup.log"),
                        workDir.resolve("blockedlist.conf"),
                        3,
                        30,
                        true,
                        new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com")),
                LOCAL_STORAGE,
                SQLITE_METADATA,
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
                        false,
                        600),
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
                LOCAL_STORAGE,
                SQLITE_METADATA,
                allowInsecure);
    }

    private static AppConfig configWithMailboxTrustAllCertificates(Path workDir, boolean allowInsecure) {
        return new AppConfig(
                new ZimbraLdapConfig(
                        "ldap://127.0.0.1:389", "uid=zimbra,cn=admins,cn=zimbra", "secret", true, null, false, 600),
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
                LOCAL_STORAGE,
                SQLITE_METADATA,
                allowInsecure);
    }
}
