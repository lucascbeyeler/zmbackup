package io.zmbackup.app.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackagedJarIT {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final byte[] MAILBOX = "mailbox-bytes".getBytes(StandardCharsets.UTF_8);
    private static final int TIMEOUT_SECONDS = 120;

    @TempDir
    Path tempDir;

    private InMemoryDirectoryServer directory;
    private WireMockServer zimbra;
    private Path workDir;

    @BeforeEach
    void startFakeZimbra() throws Exception {
        workDir = Files.createDirectories(tempDir.resolve("work"));
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("uid=zimbra,cn=admins,cn=zimbra", "secret");
        config.setSchema(null);
        directory = new InMemoryDirectoryServer(config);
        directory.startListening();
        directory.add("dc=example,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "example"));
        addAccount(ALICE);
        addAccount(BOB);

        zimbra = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        zimbra.start();
        zimbra.stubFor(get(urlPathMatching("/service/home/[^/]+/"))
                .willReturn(aResponse().withStatus(200).withBody(MAILBOX)));
        zimbra.stubFor(post(urlPathMatching("/service/home/[^/]+/"))
                .willReturn(aResponse().withStatus(200)));
    }

    @AfterEach
    void stopFakeZimbra() {
        directory.shutDown(true);
        zimbra.stop();
    }

    @Test
    void fullBackupWritesVerifiableContentAndRecordsTheSession() throws Exception {
        Path config = writeConfig("", "");

        JarRun backup = runJar(config, "UTC", "backup", "full");

        assertEquals(0, backup.exitCode(), backup.output());
        String sessionId = onlySession(workDir);
        assertTrue(sessionId.startsWith("full-"), sessionId);
        assertEquals(Set.of(ALICE, BOB), accountsOf(workDir, sessionId));
        assertArrayEquals(MAILBOX, gunzip(workDir.resolve(sessionId).resolve(ALICE + ".tgz")));
        String ldif = Files.readString(workDir.resolve(sessionId).resolve(ALICE + ".ldiff"));
        assertTrue(ldif.contains("zimbraMailDeliveryAddress: " + ALICE), ldif);

        JarRun list = runJar(config, "UTC", "list");
        assertEquals(0, list.exitCode(), list.output());
        assertTrue(list.output().contains(sessionId), list.output());
    }

    @Test
    void mailboxRestoreSendsTheBackedUpBytesToTheServiceHomePath() throws Exception {
        Path config = writeConfig("", "");
        assertEquals(0, runJar(config, "UTC", "backup", "full").exitCode());
        String sessionId = onlySession(workDir);

        JarRun restore = runJar(config, "UTC", "restore", "--session", sessionId, "--account", ALICE);

        assertEquals(0, restore.exitCode(), restore.output());
        zimbra.verify(postRequestedFor(urlPathEqualTo("/service/home/" + ALICE + "/"))
                .withRequestBody(binaryEqualTo(MAILBOX)));
    }

    @Test
    void zstdFromTheCommandLineWritesATzstArchiveThatRestoresTheOriginalBytes() throws Exception {
        Path config = writeConfig("", "");

        JarRun backup = runJar(config, "UTC", "backup", "mailbox", "--account", ALICE,
                "--compression", "zstd", "--compression-level", "19", "--compression-threads", "2");

        assertEquals(0, backup.exitCode(), backup.output());
        String sessionId = onlySession(workDir);
        Path archive = workDir.resolve(sessionId).resolve(ALICE + ".tzst");
        assertTrue(Files.exists(archive), backup.output());
        assertFalse(Files.exists(workDir.resolve(sessionId).resolve(ALICE + ".tgz")));
        assertArrayEquals(new byte[] {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD},
                Arrays.copyOf(Files.readAllBytes(archive), 4));

        JarRun restore = runJar(config, "UTC", "restore", "mailbox", "--session", sessionId, "--account", ALICE);

        assertEquals(0, restore.exitCode(), restore.output());
        zimbra.verify(postRequestedFor(urlPathEqualTo("/service/home/" + ALICE + "/"))
                .withRequestBody(binaryEqualTo(MAILBOX)));
    }

    @Test
    void xzFromTheConfigFileIsUsedWithoutAnyOption() throws Exception {
        Path config = writeConfig("  compression:\n    algorithm: xz\n    level: 1\n", "");

        JarRun backup = runJar(config, "UTC", "backup", "mailbox", "--account", ALICE);

        assertEquals(0, backup.exitCode(), backup.output());
        String sessionId = onlySession(workDir);
        assertArrayEquals(new byte[] {(byte) 0xFD, '7', 'z', 'X', 'Z', 0x00},
                Arrays.copyOf(Files.readAllBytes(workDir.resolve(sessionId).resolve(ALICE + ".txz")), 6));
    }

    @Test
    void incrementalBackupAsksOnlyForRecentMailOnceAnAccountHasABaseline() throws Exception {
        Path config = writeConfig("", "");
        createSchema(config);
        Instant threeDaysAgo = Instant.now().minus(Duration.ofDays(3));
        seedBackup(workDir, "full-20000101000000", ALICE, threeDaysAgo);

        JarRun backup = runJar(config, "UTC", "backup", "incremental");

        assertEquals(0, backup.exitCode(), backup.output());
        zimbra.verify(getRequestedFor(urlPathEqualTo("/service/home/" + ALICE + "/"))
                .withQueryParam("query", matching("after:\"\\d{2}/\\d{2}/\\d{4}\"")));
        zimbra.verify(getRequestedFor(urlPathEqualTo("/service/home/" + BOB + "/"))
                .withQueryParam("fmt", matching("tar")));
        assertTrue(zimbra.findAll(getRequestedFor(urlPathEqualTo("/service/home/" + BOB + "/"))).stream()
                .noneMatch(request -> request.getUrl().contains("query=")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Kiritimati", "Etc/GMT+12", "America/New_York"})
    void lockBackupIgnoresABackupCompletedJustBeforeLocalMidnight(String zone) throws Exception {
        Path config = writeConfig("", "");
        createSchema(config);
        seedBackup(workDir, "inc-20000101000000", ALICE, startOfLocalDay(zone).minus(Duration.ofMinutes(1)));

        JarRun backup = runJar(config, zone, "backup", "full");

        assertEquals(0, backup.exitCode(), backup.output());
        assertEquals(Set.of(ALICE, BOB), accountsOf(workDir, sessionWithPrefix(workDir, "full-")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Kiritimati", "Etc/GMT+12", "America/New_York"})
    void lockBackupSkipsAnAccountCompletedSinceLocalMidnight(String zone) throws Exception {
        Path config = writeConfig("", "");
        createSchema(config);
        seedBackup(workDir, "inc-20000101000000", ALICE, startOfLocalDay(zone).plus(Duration.ofMinutes(1)));

        JarRun backup = runJar(config, zone, "backup", "full");

        assertEquals(0, backup.exitCode(), backup.output());
        assertEquals(Set.of(BOB), accountsOf(workDir, sessionWithPrefix(workDir, "full-")));
    }

    @Test
    void forceBypassesLockBackup() throws Exception {
        Path config = writeConfig("", "");
        createSchema(config);
        seedBackup(workDir, "inc-20000101000000", ALICE, startOfLocalDay("UTC").plus(Duration.ofMinutes(1)));

        JarRun backup = runJar(config, "UTC", "backup", "full", "--force");

        assertEquals(0, backup.exitCode(), backup.output());
        assertEquals(Set.of(ALICE, BOB), accountsOf(workDir, sessionWithPrefix(workDir, "full-")));
    }

    @Test
    void aSecondRunOnTheSameDayBacksUpNothingNew() throws Exception {
        Path config = writeConfig("", "");
        assertEquals(0, runJar(config, "UTC", "backup", "full").exitCode());
        int exportsAfterFirstRun = mailboxExportCount();

        JarRun second = runJar(config, "UTC", "backup", "full");

        assertEquals(0, second.exitCode(), second.output());
        assertEquals(exportsAfterFirstRun, mailboxExportCount());
    }

    @Test
    void metadataDirHoldsTheDatabaseAndWorkDirHoldsTheContent() throws Exception {
        Path metadataDir = tempDir.resolve("metadata");
        Path config = writeConfig("  metadataDir: " + metadataDir + "\n", "");

        JarRun backup = runJar(config, "UTC", "backup", "full");

        assertEquals(0, backup.exitCode(), backup.output());
        assertTrue(Files.exists(metadataDir.resolve("sessions.sqlite3")));
        assertFalse(Files.exists(workDir.resolve("sessions.sqlite3")));
        String sessionId = onlySession(metadataDir);
        assertTrue(Files.exists(workDir.resolve(sessionId).resolve(ALICE + ".tgz")));
    }

    @Test
    void upgradedAwsSdkStillUploadsThroughThePackagedJar() throws Exception {
        WireMockServer s3 = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        s3.start();
        try {
            s3.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("ETag", "\"abc\"")));
            String storage =
                    """
                    storage:
                      backend: s3
                      s3:
                        bucket: it-bucket
                        region: us-east-1
                        prefix: sessions/
                        endpointOverride: http://127.0.0.1:%d
                    """
                            .formatted(s3.port());
            Path config = writeConfig("", storage);

            JarRun backup = runJar(config, "UTC", "backup", "ldap", "--account", ALICE);

            assertEquals(0, backup.exitCode(), backup.output());
            s3.verify(putRequestedFor(urlPathMatching("/it-bucket/sessions/ldap-[0-9]+/alice%40example\\.com\\.ldiff")));
        } finally {
            s3.stop();
        }
    }

    private static byte[] gunzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return in.readAllBytes();
        }
    }

    private int mailboxExportCount() {
        return zimbra.findAll(getRequestedFor(urlPathMatching("/service/home/[^/]+/"))).size();
    }

    private void addAccount(String email) throws Exception {
        String uid = email.substring(0, email.indexOf('@'));
        directory.add(
                "uid=" + uid + ",dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", uid),
                new Attribute("zimbraMailDeliveryAddress", email),
                new Attribute("mail", email));
    }

    private Path writeConfig(String extraBackupKeys, String extraTopLevelKeys) throws IOException {
        Path config = tempDir.resolve("zmbackup.yaml");
        Path blocklist = Files.writeString(tempDir.resolve("blockedlist.conf"), "");
        Files.writeString(
                config,
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:%d
                  bindDn: uid=zimbra,cn=admins,cn=zimbra
                  bindPassword: secret
                  sslEnabled: false
                zimbraMailbox:
                  backupUser: %s
                  restBaseUrl: http://127.0.0.1:%d
                  adminUser: admin@example.com
                  adminPassword: secret
                  batchedExport: false
                  batchedRestore: false
                backup:
                  workDir: %s
                  logFile: %s
                  blockedListFile: %s
                  lockBackup: true
                %s  emailNotify:
                    level: NONE
                %sallowInsecure: true
                """
                        .formatted(
                                directory.getListenPort(),
                                System.getProperty("user.name"),
                                zimbra.port(),
                                workDir,
                                tempDir.resolve("zmbackup.log"),
                                blocklist,
                                extraBackupKeys,
                                extraTopLevelKeys));
        return config;
    }

    private void createSchema(Path config) throws Exception {
        JarRun list = runJar(config, "UTC", "list");
        assertEquals(0, list.exitCode(), list.output());
    }

    private static Instant startOfLocalDay(String zone) {
        ZoneId zoneId = ZoneId.of(zone);
        return LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
    }

    private static Connection database(Path directory) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("sessions.sqlite3"));
    }

    private static void seedBackup(Path directory, String sessionId, String account, Instant completedAt)
            throws SQLException {
        try (Connection connection = database(directory);
                PreparedStatement session = connection.prepareStatement(
                        "insert into backup_session(sessionID, initial_date, conclusion_date, size, type, status)"
                                + " values (?, ?, ?, 1, 'INCREMENTAL', 'COMPLETED')");
                PreparedStatement record = connection.prepareStatement(
                        "insert into backup_account(sessionID, account_size, email, initial_date, conclusion_date)"
                                + " values (?, 1, ?, ?, ?)")) {
            session.setString(1, sessionId);
            session.setString(2, completedAt.toString());
            session.setString(3, completedAt.toString());
            session.executeUpdate();
            record.setString(1, sessionId);
            record.setString(2, account);
            record.setString(3, completedAt.toString());
            record.setString(4, completedAt.toString());
            record.executeUpdate();
        }
    }

    private static String onlySession(Path directory) throws SQLException {
        List<String> sessions = sessions(directory);
        assertEquals(1, sessions.size(), sessions.toString());
        return sessions.get(0);
    }

    private static String sessionWithPrefix(Path directory, String prefix) throws SQLException {
        try (Stream<String> matching = sessions(directory).stream().filter(id -> id.startsWith(prefix))) {
            List<String> found = matching.toList();
            assertEquals(1, found.size(), found.toString());
            return found.get(0);
        }
    }

    private static List<String> sessions(Path directory) throws SQLException {
        List<String> sessions = new ArrayList<>();
        try (Connection connection = database(directory);
                PreparedStatement statement = connection.prepareStatement("select sessionID from backup_session");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                sessions.add(rows.getString(1));
            }
        }
        return sessions;
    }

    private static Set<String> accountsOf(Path directory, String sessionId) throws SQLException {
        Set<String> accounts = new TreeSet<>();
        try (Connection connection = database(directory);
                PreparedStatement statement =
                        connection.prepareStatement("select email from backup_account where sessionID = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    accounts.add(rows.getString(1));
                }
            }
        }
        return accounts;
    }

    private JarRun runJar(Path config, String zone, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.timezone=" + zone,
                "-jar",
                System.getProperty("zmbackup.shadowJar"),
                "--config",
                config.toString()));
        command.addAll(List.of(args));
        Path outputFile = Files.createTempFile(tempDir, "jar-run", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
        builder.environment().put("AWS_ACCESS_KEY_ID", "integration");
        builder.environment().put("AWS_SECRET_ACCESS_KEY", "integration");
        Process process = builder.start();
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "zmbackup timed out: " + command);
        return new JarRun(process.exitValue(), Files.readString(outputFile));
    }

    private record JarRun(int exitCode, String output) {}
}
