package io.zmbackup.app.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import io.zmbackup.core.service.BackupService;
import io.zmbackup.core.service.HousekeepService;
import io.zmbackup.core.service.SessionService;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Step definitions backing {@code backup_integration.feature}: wires {@link BackupService},
 * {@link HousekeepService}, and {@link SessionService} against real port implementations rather
 * than the mocked ports used by {@code BackupServiceTest}.
 */
public class BackupIntegrationSteps {

    private static final String BIND_DN = "uid=zimbra,cn=admins,cn=zimbra";
    private static final String BIND_PASSWORD = "secret";

    private Path tempDir;
    private InMemoryDirectoryServer directoryServer;
    private Connection sqliteAnchor;
    private SqliteMetadataStore metadataStore;
    private LocalStorageProvider storageProvider;
    private BackupService backupService;
    private HousekeepService housekeepService;
    private SessionService sessionService;

    private BackupSession lastSession;
    private BackupSession firstNamedSession;
    private BackupSession secondNamedSession;
    private String oldSessionId;
    private String oldSessionAccount;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("zmbackup-cucumber");

        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com", "dc=other,dc=com");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setSchema(null);
        directoryServer = new InMemoryDirectoryServer(config);
        directoryServer.startListening();
        directoryServer.add(
                "dc=example,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "example"));

        UnboundIdLdapAdapter ldapAdapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        String cacheName = "cucumber-" + UUID.randomUUID();
        String sqliteUrl = "jdbc:sqlite:file:" + cacheName + "?mode=memory&cache=shared";
        sqliteAnchor = DriverManager.getConnection(sqliteUrl);
        metadataStore = new SqliteMetadataStore(Path.of("file:" + cacheName + "?mode=memory&cache=shared"));

        storageProvider = new LocalStorageProvider(tempDir);

        backupService =
                new BackupService(ldapAdapter, ldapAdapter, new NoOpMailboxExporter(), storageProvider, metadataStore);
        housekeepService = new HousekeepService(storageProvider, metadataStore);
        sessionService = new SessionService(storageProvider, metadataStore);
    }

    @After
    public void tearDown() throws IOException, SQLException {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
        }
        if (sqliteAnchor != null) {
            sqliteAnchor.close();
        }
        if (tempDir != null) {
            deleteRecursively(tempDir);
        }
    }

    @Given("an in-memory LDAP directory with no data")
    public void anInMemoryLdapDirectoryWithNoData() {
        // The directory is already running and empty from @Before.
    }

    @Given("an in-memory LDAP directory with accounts:")
    public void anInMemoryLdapDirectoryWithAccounts(DataTable table) throws Exception {
        for (String email : table.asList(String.class)) {
            String uid = email.substring(0, email.indexOf('@'));
            directoryServer.add(
                    "uid=" + uid + ",dc=example,dc=com",
                    new Attribute("objectClass", "zimbraAccount"),
                    new Attribute("uid", uid),
                    new Attribute("zimbraMailDeliveryAddress", email),
                    new Attribute("mail", email));
        }
    }

    @Given("an in-memory LDAP directory with domain {string}")
    public void anInMemoryLdapDirectoryWithDomain(String domain) throws Exception {
        String label = domain.substring(0, domain.indexOf('.'));
        directoryServer.add(
                "dc=" + label + ",dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", label),
                new Attribute("zimbraDomainName", domain));
    }

    @When("I run an LDAP backup")
    public void iRunAnLdapBackup() throws IOException {
        lastSession = backupService.backup(BackupType.LDAP).orElseThrow();
    }

    @When("I run an LDAP backup for {string}")
    public void iRunAnLdapBackupFor(String identifier) throws IOException {
        firstNamedSession = backupService.backup(BackupType.LDAP, List.of(identifier)).orElseThrow();
        lastSession = firstNamedSession;
    }

    @Given("I have run an LDAP backup for {string}")
    public void iHaveRunAnLdapBackupFor(String identifier) throws IOException {
        iRunAnLdapBackupFor(identifier);
    }

    @And("I run a signature backup for {string}")
    public void iRunASignatureBackupFor(String identifier) throws IOException {
        secondNamedSession = backupService.backup(BackupType.SIGNATURE, List.of(identifier)).orElseThrow();
        lastSession = secondNamedSession;
    }

    @When("I run a domain backup")
    public void iRunADomainBackup() throws IOException {
        lastSession = backupService.backup(BackupType.DOMAIN).orElseThrow();
    }

    @When("I run a domain backup for {string}")
    public void iRunADomainBackupFor(String identifier) throws IOException {
        lastSession = backupService.backup(BackupType.DOMAIN, List.of(identifier)).orElseThrow();
    }

    @When("I delete that session")
    public void iDeleteThatSession() throws IOException {
        assertTrue(sessionService.deleteSession(lastSession.sessionId()));
    }

    @Given("a stored session {string} completed {int} days ago with account {string}")
    public void aStoredSessionCompletedDaysAgoWithAccount(String sessionId, int daysAgo, String account)
            throws IOException {
        Instant now = Instant.now();
        Instant completedAt = now.minus(daysAgo, ChronoUnit.DAYS);
        BackupSession session = new BackupSession(
                sessionId, BackupType.LDAP, SessionStatus.FINISHED, completedAt.minusSeconds(60), completedAt, "1K");
        metadataStore.save(session);
        metadataStore.recordAccountBackup(new BackupAccountRecord(null, sessionId, account, "1K", now, now));
        try (var writer = storageProvider.openWrite(sessionId, account, "ldiff")) {
            writer.write(("dn: uid=" + account + "\n").getBytes());
        }
        oldSessionId = sessionId;
        oldSessionAccount = account;
    }

    @When("I rotate sessions older than {int} days")
    public void iRotateSessionsOlderThanDays(int days) throws IOException {
        housekeepService.rotateOldSessions(days);
    }

    @Then("the backup session status is {word}")
    public void theBackupSessionStatusIs(String status) {
        assertEquals(SessionStatus.valueOf(status), lastSession.status());
    }

    @Then("the LDIF file for {string} contains {string}")
    public void theLdifFileForContains(String identifier, String expectedContent) throws IOException {
        Path ldif = tempDir.resolve(lastSession.sessionId()).resolve(identifier + ".ldiff");
        assertTrue(Files.exists(ldif), "expected " + ldif + " to exist");
        assertTrue(Files.readString(ldif).contains(expectedContent));
    }

    @Then("the metadata store has {int} account records for the session")
    public void theMetadataStoreHasAccountRecordsForTheSession(int expectedCount) throws IOException {
        List<BackupAccountRecord> records = metadataStore.findAccountsForSession(lastSession.sessionId());
        assertEquals(expectedCount, records.size());
    }

    @Then("listing sessions returns the signature session before the LDAP session")
    public void listingSessionsReturnsTheSignatureSessionBeforeTheLdapSession() throws IOException {
        assertEquals(List.of(secondNamedSession, firstNamedSession), sessionService.listSessions());
    }

    @Then("the session's LDIF file no longer exists")
    public void theSessionsLdifFileNoLongerExists() {
        Path sessionDir = tempDir.resolve(lastSession.sessionId());
        assertFalse(Files.exists(sessionDir));
    }

    @Then("the metadata store has no record of the session")
    public void theMetadataStoreHasNoRecordOfTheSession() throws IOException {
        assertEquals(Optional.empty(), metadataStore.findSession(lastSession.sessionId()));
    }

    @Then("the old session's LDIF file no longer exists")
    public void theOldSessionsLdifFileNoLongerExists() {
        Path ldif = tempDir.resolve(oldSessionId).resolve(oldSessionAccount + ".ldiff");
        assertFalse(Files.exists(ldif));
    }

    @Then("the metadata store has no record of the old session")
    public void theMetadataStoreHasNoRecordOfTheOldSession() throws IOException {
        assertEquals(Optional.empty(), metadataStore.findSession(oldSessionId));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Unused by the current LDAP-only scenarios; satisfies {@link BackupService}'s constructor. */
    private static final class NoOpMailboxExporter implements ZimbraMailboxExporter {
        @Override
        public boolean export(String account, OutputStream destination, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restore(String account, InputStream source) {
            throw new UnsupportedOperationException();
        }
    }
}
