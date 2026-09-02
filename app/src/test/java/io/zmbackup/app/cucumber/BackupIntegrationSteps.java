package io.zmbackup.app.cucumber;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import io.zmbackup.core.domain.RestoreResult;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.service.BackupService;
import io.zmbackup.core.service.HousekeepService;
import io.zmbackup.core.service.RestoreService;
import io.zmbackup.core.service.SessionService;
import io.zmbackup.local.LocalStorageProvider;
import io.zmbackup.local.SqliteMetadataStore;
import io.zmbackup.zimbra.UnboundIdLdapAdapter;
import io.zmbackup.zimbra.ZimbraRestMailboxExporter;
import java.io.IOException;
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
 * Step definitions backing {@code backup_integration.feature} and {@code
 * restore_integration.feature}: wires {@link BackupService}, {@link RestoreService}, {@link
 * HousekeepService}, and {@link SessionService} against real port implementations rather than the
 * mocked ports used by {@code BackupServiceTest}/{@code RestoreServiceTest}.
 */
public class BackupIntegrationSteps {

    private static final String BIND_DN = "uid=zimbra,cn=admins,cn=zimbra";
    private static final String BIND_PASSWORD = "secret";
    private static final String MAILBOX_ADMIN_USER = "zimbra";
    private static final String MAILBOX_ADMIN_PASSWORD = "secret";

    private Path tempDir;
    private InMemoryDirectoryServer directoryServer;
    private WireMockServer mailboxServer;
    private Connection sqliteAnchor;
    private SqliteMetadataStore metadataStore;
    private LocalStorageProvider storageProvider;
    private BackupService backupService;
    private RestoreService restoreService;
    private HousekeepService housekeepService;
    private SessionService sessionService;

    private BackupSession lastSession;
    private BackupSession firstNamedSession;
    private BackupSession secondNamedSession;
    private String oldSessionId;
    private String oldSessionAccount;
    private RestoreResult lastRestoreResult;

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
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false, null, false);

        mailboxServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        mailboxServer.start();
        // Restore POSTs succeed by default; scenarios only need to stub the GET export endpoint.
        mailboxServer.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));
        ZimbraRestMailboxExporter mailboxExporter =
                new ZimbraRestMailboxExporter(mailboxServer.baseUrl(), MAILBOX_ADMIN_USER, MAILBOX_ADMIN_PASSWORD);

        String cacheName = "cucumber-" + UUID.randomUUID();
        String sqliteUrl = "jdbc:sqlite:file:" + cacheName + "?mode=memory&cache=shared";
        sqliteAnchor = DriverManager.getConnection(sqliteUrl);
        metadataStore = new SqliteMetadataStore(Path.of("file:" + cacheName + "?mode=memory&cache=shared"));

        storageProvider = new LocalStorageProvider(tempDir);

        backupService = new BackupService(ldapAdapter, ldapAdapter, mailboxExporter, storageProvider, metadataStore);
        restoreService = new RestoreService(ldapAdapter, mailboxExporter, storageProvider, metadataStore);
        housekeepService = new HousekeepService(storageProvider, metadataStore);
        sessionService = new SessionService(storageProvider, metadataStore);
    }

    @After
    public void tearDown() throws IOException, SQLException {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
        }
        if (mailboxServer != null) {
            mailboxServer.stop();
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

    @Given("the mailbox export endpoint for {string} returns tgz content {string}")
    public void theMailboxExportEndpointForReturnsTgzContent(String account, String tgzContent) {
        mailboxServer.stubFor(get(urlPathEqualTo("/home/" + account + "/"))
                .willReturn(aResponse().withStatus(200).withBody(tgzContent)));
    }

    @Given("the mailbox export endpoint for {string} returns HTTP {int}")
    public void theMailboxExportEndpointForReturnsHttp(String account, int status) {
        mailboxServer.stubFor(
                get(urlPathEqualTo("/home/" + account + "/")).willReturn(aResponse().withStatus(status)));
    }

    @When("I run a full backup")
    public void iRunAFullBackup() throws IOException {
        lastSession = backupService.backup(BackupType.FULL).orElseThrow();
    }

    @When("I run a domain backup")
    public void iRunADomainBackup() throws IOException {
        lastSession = backupService.backup(BackupType.DOMAIN).orElseThrow();
    }

    @When("I run a domain backup for {string}")
    public void iRunADomainBackupFor(String identifier) throws IOException {
        lastSession = backupService.backup(BackupType.DOMAIN, List.of(identifier)).orElseThrow();
    }

    @Given("I have run a domain backup for {string}")
    public void iHaveRunADomainBackupFor(String identifier) throws IOException {
        iRunADomainBackupFor(identifier);
    }

    @Given("I have run a full backup for {string}")
    public void iHaveRunAFullBackupFor(String identifier) throws IOException {
        lastSession = backupService.backup(BackupType.FULL, List.of(identifier)).orElseThrow();
    }

    @When("I delete that session")
    public void iDeleteThatSession() throws IOException {
        assertTrue(sessionService.deleteSession(lastSession.sessionId()));
    }

    @Given("the LDAP entry for {string} is deleted")
    public void theLdapEntryForIsDeleted(String account) throws Exception {
        directoryServer.delete(accountDn(account));
    }

    @When("I restore LDAP for {string}")
    public void iRestoreLdapFor(String account) throws IOException {
        lastRestoreResult = restoreService.restoreLdap(lastSession.sessionId(), List.of(account));
    }

    @When("I restore LDAP for {string} from session {string}")
    public void iRestoreLdapForFromSession(String account, String sessionId) throws IOException {
        lastRestoreResult = restoreService.restoreLdap(sessionId, List.of(account));
    }

    @When("I restore domain {string}")
    public void iRestoreDomain(String domain) throws IOException {
        lastRestoreResult = restoreService.restoreDomain(lastSession.sessionId(), List.of(domain));
    }

    @When("I restore the mailbox for {string}")
    public void iRestoreTheMailboxFor(String account) throws IOException {
        lastRestoreResult = restoreService.restoreMailbox(lastSession.sessionId(), List.of(account));
    }

    @When("I restore the mailbox for {string} into {string}")
    public void iRestoreTheMailboxForInto(String account, String destination) throws IOException {
        lastRestoreResult = restoreService.restoreMailbox(lastSession.sessionId(), List.of(account), destination);
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

    @Then("the mailbox archive for {string} contains {string}")
    public void theMailboxArchiveForContains(String identifier, String expectedContent) throws IOException {
        Path tgz = tempDir.resolve(lastSession.sessionId()).resolve(identifier + ".tgz");
        assertTrue(Files.exists(tgz), "expected " + tgz + " to exist");
        assertEquals(expectedContent, Files.readString(tgz));
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

    @Then("the {word} restore result has {int} failed accounts")
    public void theRestoreResultHasFailedAccounts(String kind, int expectedFailedCount) {
        assertEquals(expectedFailedCount, lastRestoreResult.failedAccounts().size());
    }

    @Then("the LDAP entry for {string} exists again")
    public void theLdapEntryForExistsAgain(String account) throws Exception {
        assertNotNull(directoryServer.getEntry(accountDn(account)));
    }

    @Then("the WireMock server received a mailbox restore POST for {string} with body {string}")
    public void theWireMockServerReceivedAMailboxRestorePostForWithBody(String account, String expectedBody) {
        mailboxServer.verify(postRequestedFor(urlPathEqualTo("/home/" + account + "/"))
                .withRequestBody(equalTo(expectedBody)));
    }

    private static String accountDn(String account) {
        String uid = account.substring(0, account.indexOf('@'));
        return "uid=" + uid + ",dc=example,dc=com";
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
}
