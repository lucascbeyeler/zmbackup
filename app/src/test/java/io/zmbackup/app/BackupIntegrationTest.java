package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.service.BackupService;
import io.zmbackup.core.service.HousekeepService;
import io.zmbackup.core.service.SessionService;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@link BackupService}, {@link HousekeepService}, and {@link
 * SessionService} wired against real port implementations: {@link UnboundIdLdapAdapter} backed by
 * an in-memory LDAP directory, {@link LocalStorageProvider} backed by a temp directory, and {@link
 * SqliteMetadataStore} backed by an in-memory SQLite database. Complements {@code
 * BackupServiceTest}'s mocked-port unit tests by proving the real adapters cooperate correctly.
 */
class BackupIntegrationTest {

    private static final String BIND_DN = "uid=zimbra,cn=admins,cn=zimbra";
    private static final String BIND_PASSWORD = "secret";

    @TempDir
    Path tempDir;

    private InMemoryDirectoryServer directoryServer;
    private Connection sqliteAnchor;
    private BackupService backupService;
    private HousekeepService housekeepService;
    private SessionService sessionService;
    private LocalStorageProvider storageProvider;
    private SqliteMetadataStore metadataStore;

    @BeforeEach
    void setUp() throws Exception {
        directoryServer = startDirectoryServer();
        UnboundIdLdapAdapter ldapAdapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        String sqliteUrl = "jdbc:sqlite:file::memory:?cache=shared";
        sqliteAnchor = DriverManager.getConnection(sqliteUrl);
        metadataStore = new SqliteMetadataStore(Path.of("file::memory:?cache=shared"));

        storageProvider = new LocalStorageProvider(tempDir);

        backupService = new BackupService(ldapAdapter, ldapAdapter, storageProvider, metadataStore);
        housekeepService = new HousekeepService(storageProvider, metadataStore);
        sessionService = new SessionService(storageProvider, metadataStore);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
        }
        if (sqliteAnchor != null) {
            sqliteAnchor.close();
        }
    }

    @Test
    void backsUpDiscoveredAccountsWritingRealLdifAndSqliteMetadata() throws Exception {
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        directoryServer.add(
                "uid=bob,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "bob"),
                new Attribute("zimbraMailDeliveryAddress", "bob@example.com"),
                new Attribute("mail", "bob@example.com"));

        Optional<BackupSession> result = backupService.backup(BackupType.LDAP);

        assertTrue(result.isPresent());
        BackupSession session = result.get();
        assertEquals(SessionStatus.FINISHED, session.status());

        Path aliceLdif = tempDir.resolve(session.sessionId()).resolve("alice@example.com.ldiff");
        Path bobLdif = tempDir.resolve(session.sessionId()).resolve("bob@example.com.ldiff");
        assertTrue(Files.exists(aliceLdif));
        assertTrue(Files.exists(bobLdif));
        assertTrue(Files.readString(aliceLdif).contains("dn: uid=alice,dc=example,dc=com"));
        assertTrue(Files.readString(bobLdif).contains("mail: bob@example.com"));

        assertEquals(Optional.of(session), metadataStore.findSession(session.sessionId()));
        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(session.sessionId());
        assertEquals(2, records.size());
    }

    @Test
    void backsUpDomainWritingRealLdifAndSqliteMetadata() throws Exception {
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));

        Optional<BackupSession> result = backupService.backup(BackupType.DOMAIN);

        assertTrue(result.isPresent());
        BackupSession session = result.get();
        assertEquals(SessionStatus.FINISHED, session.status());

        Path domainLdif = tempDir.resolve(session.sessionId()).resolve("other.com.ldiff");
        assertTrue(Files.exists(domainLdif));
        assertTrue(Files.readString(domainLdif).contains("zimbraDomainName: other.com"));
    }

    @Test
    void marksSessionFailedWhenRealLdapExportFails() throws Exception {
        Optional<BackupSession> result =
                backupService.backup(BackupType.DOMAIN, List.of("nowhere.invalid"));

        assertTrue(result.isPresent());
        assertEquals(SessionStatus.FAILED, result.get().status());
        assertEquals(List.of(), metadataStore.findAccountsForSession(result.get().sessionId()));
    }

    @Test
    void sessionServiceListsRealSessionsMostRecentlyStartedFirst() throws Exception {
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));

        BackupSession first = backupService.backup(BackupType.LDAP, List.of("alice@example.com")).orElseThrow();
        BackupSession second = backupService.backup(BackupType.SIGNATURE, List.of("alice@example.com"))
                .orElseThrow();

        List<BackupSession> sessions = sessionService.listSessions();

        assertEquals(List.of(second, first), sessions);
    }

    @Test
    void sessionServiceDeletesRealFilesAndSqliteMetadata() throws Exception {
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        BackupSession session = backupService.backup(BackupType.LDAP, List.of("alice@example.com")).orElseThrow();
        Path ldif = tempDir.resolve(session.sessionId()).resolve("alice@example.com.ldiff");
        assertTrue(Files.exists(ldif));

        boolean deleted = sessionService.deleteSession(session.sessionId());

        assertTrue(deleted);
        assertFalse(Files.exists(ldif));
        assertEquals(Optional.empty(), metadataStore.findSession(session.sessionId()));
    }

    @Test
    void housekeepRotatesOldRealSessionsAcrossStorageAndMetadata() throws Exception {
        Instant now = Instant.now();
        BackupSession old = new BackupSession(
                "ldap-old", BackupType.LDAP, SessionStatus.FINISHED, now.minus(10, ChronoUnit.DAYS),
                now.minus(9, ChronoUnit.DAYS), "1K");
        metadataStore.save(old);
        metadataStore.recordAccountBackup(
                new BackupAccountRecord(null, "ldap-old", "alice@example.com", "1K", now, now));
        try (var writer = storageProvider.openWrite("ldap-old", "alice@example.com", "ldiff")) {
            writer.write("dn: uid=alice\n".getBytes());
        }
        Path oldLdif = tempDir.resolve("ldap-old").resolve("alice@example.com.ldiff");
        assertTrue(Files.exists(oldLdif));

        List<BackupSession> removed = housekeepService.rotateOldSessions(7);

        assertEquals(List.of(old), removed);
        assertFalse(Files.exists(oldLdif));
        assertEquals(Optional.empty(), metadataStore.findSession("ldap-old"));
    }

    private InMemoryDirectoryServer startDirectoryServer() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com", "dc=other,dc=com");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setSchema(null);
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.startListening();
        server.add("dc=example,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "example"));
        return server;
    }
}
