package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Exercises the {@code restore}/{@code restore ldap}/{@code restore domain}/{@code restore
 * mailbox} commands end to end: a real backup is taken first (against an in-memory LDAP directory
 * and a plain {@link HttpServer} standing in for Zimbra's REST mailbox endpoint), the underlying
 * data is then mutated, and the restore command's effect is verified.
 */
class RestoreCommandTest {

    private static final String BIND_DN = "uid=zimbra,cn=admins,cn=zimbra";
    private static final String BIND_PASSWORD = "secret";

    @TempDir
    Path tempDir;

    private InMemoryDirectoryServer directoryServer;
    private HttpServer mailboxServer;
    private String mailboxRestBaseUrl = "https://127.0.0.1:7071";
    private final List<String> mailboxRestorePosts = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
        }
        if (mailboxServer != null) {
            mailboxServer.stop(0);
        }
    }

    @Test
    void ldapSubcommandRejectsMalformedSessionId() throws Exception {
        directoryServer = startDirectoryServer();
        Path configFile = writeConfig();
        StringWriter err = new StringWriter();

        int exitCode = commandLine(new StringWriter(), err)
                .execute("--config", configFile.toString(), "restore", "ldap", "--session", "not-a-session");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("Error! Invalid session ID: not-a-session"));
    }

    @Test
    void ldapSubcommandRejectsMalformedAccount() throws Exception {
        directoryServer = startDirectoryServer();
        Path configFile = writeConfig();
        StringWriter err = new StringWriter();

        int exitCode = commandLine(new StringWriter(), err)
                .execute(
                        "--config", configFile.toString(), "restore", "ldap", "--session",
                        "ldap-20260101120000", "--account", "not-an-email");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("Error! Invalid email address: not-an-email"));
    }

    @Test
    void topLevelRestoreRejectsMalformedIntoDestination() throws Exception {
        directoryServer = startDirectoryServer();
        Path configFile = writeConfig();
        StringWriter err = new StringWriter();

        int exitCode = commandLine(new StringWriter(), err)
                .execute(
                        "--config", configFile.toString(), "restore", "--session", "full-20260101120000",
                        "--account", "alice@example.com", "--into", "not-an-email");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("Error! Invalid email address: not-an-email"));
    }

    @Test
    void ldapSubcommandRestoresAccountEntry() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"),
                new Attribute("description", "original"));
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        StringWriter backupErr = new StringWriter();
        int backupExit = commandLine(backupOut, backupErr)
                .execute("--config", configFile.toString(), "backup", "ldap", "--account", "alice@example.com");
        assertEquals(0, backupExit, backupErr.toString());
        String sessionId = sessionIdOf(backupOut, "ldap-");

        directoryServer.modify(
                "uid=alice,dc=example,dc=com",
                new Modification(ModificationType.REPLACE, "description", "corrupted"));

        StringWriter restoreOut = new StringWriter();
        int restoreExit = commandLine(restoreOut, new StringWriter())
                .execute(
                        "--config", configFile.toString(), "restore", "ldap", "--session", sessionId, "--account",
                        "alice@example.com");

        assertEquals(0, restoreExit);
        assertTrue(restoreOut.toString().contains("completed"));
        Entry restored = directoryServer.getEntry("uid=alice,dc=example,dc=com");
        assertEquals("original", restored.getAttributeValue("description"));
    }

    @Test
    void domainSubcommandRestoresDomainEntry() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        int backupExit = commandLine(backupOut, new StringWriter())
                .execute("--config", configFile.toString(), "backup", "domain", "--domain", "other.com");
        assertEquals(0, backupExit);
        String sessionId = sessionIdOf(backupOut, "domain-");

        StringWriter restoreOut = new StringWriter();
        int restoreExit = commandLine(restoreOut, new StringWriter())
                .execute(
                        "--config", configFile.toString(), "restore", "domain", "--session", sessionId, "--domain",
                        "other.com");

        assertEquals(0, restoreExit);
        assertTrue(restoreOut.toString().contains("completed"));
    }

    @Test
    void mailboxSubcommandPostsArchiveToRestoreEndpoint() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer();
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        int backupExit = commandLine(backupOut, new StringWriter())
                .execute("--config", configFile.toString(), "backup", "mailbox", "--account", "alice@example.com");
        assertEquals(0, backupExit);
        String sessionId = sessionIdOf(backupOut, "mbox-");

        StringWriter restoreOut = new StringWriter();
        int restoreExit = commandLine(restoreOut, new StringWriter())
                .execute(
                        "--config", configFile.toString(), "restore", "mailbox", "--session", sessionId, "--account",
                        "alice@example.com");

        assertEquals(0, restoreExit);
        assertEquals(List.of("POST /service/home/alice@example.com/"), mailboxRestorePosts);
    }

    @Test
    void mailboxSubcommandWithIntoRestoresIntoDestinationAccount() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer();
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        commandLine(backupOut, new StringWriter())
                .execute("--config", configFile.toString(), "backup", "mailbox", "--account", "alice@example.com");
        String sessionId = sessionIdOf(backupOut, "mbox-");

        StringWriter restoreOut = new StringWriter();
        int restoreExit = commandLine(restoreOut, new StringWriter())
                .execute(
                        "--config", configFile.toString(), "restore", "mailbox", "--session", sessionId, "--account",
                        "alice@example.com", "--into", "bob@example.com");

        assertEquals(0, restoreExit);
        assertEquals(List.of("POST /service/home/bob@example.com/"), mailboxRestorePosts);
    }

    @Test
    void topLevelRestoreWithIntoRequiresExactlyOneAccount() throws Exception {
        directoryServer = startDirectoryServer();
        Path configFile = writeConfig();
        StringWriter err = new StringWriter();

        int exitCode = commandLine(new StringWriter(), err)
                .execute(
                        "--config", configFile.toString(), "restore", "--session", "full-20260101120000",
                        "--account", "alice@example.com", "--account", "bob@example.com", "--into",
                        "carol@example.com");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("--into requires exactly one --account"));
    }

    @Test
    void topLevelRestoreWithIntoBypassesTheFullIncrementalSessionCheck() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer();
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        commandLine(backupOut, new StringWriter())
                .execute("--config", configFile.toString(), "backup", "mailbox", "--account", "alice@example.com");
        String sessionId = sessionIdOf(backupOut, "mbox-");

        StringWriter restoreOut = new StringWriter();
        int restoreExit = commandLine(restoreOut, new StringWriter())
                .execute(
                        "--config", configFile.toString(), "restore", "--session", sessionId, "--account",
                        "alice@example.com", "--into", "bob@example.com");

        assertEquals(0, restoreExit);
        assertEquals(List.of("POST /service/home/bob@example.com/"), mailboxRestorePosts);
    }

    @Test
    void topLevelRestoreRejectsNonFullIncrementalSession() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        int backupExit = commandLine(backupOut, new StringWriter())
                .execute("--config", configFile.toString(), "backup", "ldap", "--account", "alice@example.com");
        assertEquals(0, backupExit);
        String sessionId = sessionIdOf(backupOut, "ldap-");
        StringWriter err = new StringWriter();

        int exitCode = commandLine(new StringWriter(), err)
                .execute("--config", configFile.toString(), "restore", "--session", sessionId);

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("not a full/incremental session"));
    }

    @Test
    void topLevelRestoreRestoresLdapAndMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer();
        Path configFile = writeConfig();
        StringWriter backupOut = new StringWriter();
        int backupExit = commandLine(backupOut, new StringWriter())
                .execute("--config", configFile.toString(), "backup", "full", "--account", "alice@example.com");
        assertEquals(0, backupExit);
        String sessionId = sessionIdOf(backupOut, "full-");

        StringWriter restoreOut = new StringWriter();
        int restoreExit = commandLine(restoreOut, new StringWriter())
                .execute("--config", configFile.toString(), "restore", "--session", sessionId);

        assertEquals(0, restoreExit);
        assertEquals(List.of("POST /service/home/alice@example.com/"), mailboxRestorePosts);
    }

    private static String sessionIdOf(StringWriter out, String prefix) {
        String output = out.toString();
        int start = output.indexOf(prefix);
        int end = output.indexOf(' ', start);
        return output.substring(start, end);
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

    private void startMailboxServer() throws IOException {
        mailboxServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mailboxServer.createContext("/", (HttpExchange exchange) -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method)) {
                byte[] body = "tgz-content".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else if ("POST".equals(method)) {
                mailboxRestorePosts.add("POST " + path);
                ByteArrayOutputStream drained = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(drained);
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        mailboxServer.start();
        mailboxRestBaseUrl = "http://127.0.0.1:" + mailboxServer.getAddress().getPort();
    }

    private Path writeConfig() throws IOException {
        Path configFile = tempDir.resolve("zmbackup.yaml");
        Files.writeString(
                configFile,
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:%d
                  bindDn: uid=zimbra,cn=admins,cn=zimbra
                  bindPassword: secret
                  sslEnabled: false
                zimbraMailbox:
                  backupUser: %s
                  restBaseUrl: %s
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: %s
                  logFile: %s
                  blockedListFile: %s
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                allowInsecure: true
                """
                        .formatted(
                                directoryServer.getListenPort(),
                                System.getProperty("user.name"),
                                mailboxRestBaseUrl,
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf")));
        return configFile;
    }

    private static CommandLine commandLine(StringWriter out, StringWriter err) {
        CommandLine cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }
}
