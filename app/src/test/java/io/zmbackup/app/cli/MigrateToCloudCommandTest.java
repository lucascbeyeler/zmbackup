package io.zmbackup.app.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MigrateToCloudCommandTest {

    @TempDir
    Path tempDir;

    private WireMockServer wireMockServer;

    @BeforeAll
    static void setUpCredentials() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        wireMockServer.stubFor(post(anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.PutItem"))
                .willReturn(jsonResponse("{}")));
        wireMockServer.stubFor(post(anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.DeleteItem"))
                .willReturn(jsonResponse("{}")));
        wireMockServer.stubFor(post(anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .willReturn(jsonResponse("{\"Items\":[]}")));
        wireMockServer.stubFor(put(urlPathMatching("/.*")).willReturn(aResponse().withStatus(200)));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void migratesLocalPhase1DataIntoTheConfiguredCloudBackend() throws Exception {
        Path sourceDir = tempDir.resolve("source-workdir");
        Files.createDirectories(sourceDir);
        Path sourceDb = sourceDir.resolve("sessions.sqlite3");
        seedSource(sourceDir, sourceDb);

        Path configFile = writeCloudConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));

        int exitCode = cmd.execute(
                "--config",
                configFile.toString(),
                "migrate-to-cloud",
                "--source-dir",
                sourceDir.toString(),
                "--source-db",
                sourceDb.toString());

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Migrated 1 backup session(s) and 1 account record(s)"));
        wireMockServer.verify(anyRequestedFor(anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.PutItem")));
        wireMockServer.verify(anyRequestedFor(urlPathMatching(".*alice%40example\\.com\\.tgz")));
    }

    private void seedSource(Path sourceDir, Path sourceDb) throws IOException {
        try (SqliteMetadataStore metadataStore = new SqliteMetadataStore(sourceDb)) {
            LocalStorageProvider storageProvider = new LocalStorageProvider(sourceDir);
            metadataStore.save(new BackupSession(
                    "mbox-20260101120000",
                    BackupType.MAILBOX,
                    SessionStatus.FINISHED,
                    Instant.parse("2026-01-01T12:00:00Z"),
                    Instant.parse("2026-01-01T12:05:00Z"),
                    "1K"));
            try (var destination = storageProvider.openWrite("mbox-20260101120000", "alice@example.com", "tgz")) {
                destination.write("tgz-content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            metadataStore.recordAccountBackup(new BackupAccountRecord(
                    null,
                    "mbox-20260101120000",
                    "alice@example.com",
                    "1K",
                    Instant.parse("2026-01-01T12:00:00Z"),
                    Instant.parse("2026-01-01T12:05:00Z")));
        }
    }

    private Path writeCloudConfig() throws IOException {
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
                storage:
                  backend: s3
                  s3:
                    bucket: test-bucket
                    region: us-east-1
                    prefix: sessions/
                    endpointOverride: %s
                metadata:
                  backend: dynamodb
                  dynamodb:
                    region: us-east-1
                    endpointOverride: %s
                allowInsecure: true
                """
                        .formatted(
                                System.getProperty("user.name"),
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf"),
                                wireMockServer.baseUrl(),
                                wireMockServer.baseUrl()));
        return configFile;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/x-amz-json-1.0").withBody(body);
    }
}
