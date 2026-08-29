package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class BackupCommandTest {

    private static final String BIND_DN = "uid=zimbra,cn=admins,cn=zimbra";
    private static final String BIND_PASSWORD = "secret";

    @TempDir
    Path tempDir;

    private InMemoryDirectoryServer directoryServer;
    private HttpServer mailboxServer;
    private String mailboxRestBaseUrl = "https://127.0.0.1:7071";

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
    void noSubcommandPrintsUsage() {
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("backup");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(out.toString().contains("Usage: zmbackup backup"));
    }

    @Test
    void ldapBacksUpEveryDiscoveredAccount() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "ldap");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("ldap-"));
        assertTrue(output.contains("FINISHED"));
    }

    @Test
    void ldapWithAccountOptionBacksUpOnlyThatAccount() throws Exception {
        directoryServer = startDirectoryServer();
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
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode =
                cmd.execute("--config", configFile.toString(), "backup", "ldap", "--account", "alice@example.com");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(tempDir.resolve("ldap-" + sessionSuffixOf(out) + "/alice@example.com.ldiff")));
    }

    @Test
    void fullBacksUpEveryDiscoveredAccountIncludingMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer("/home/alice@example.com/", 200, "tgz-content".getBytes());
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "full");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("full-"));
        assertTrue(output.contains("FINISHED"));
    }

    @Test
    void fullWithAccountOptionBacksUpOnlyThatAccountsMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer("/home/alice@example.com/", 200, "tgz-content".getBytes());
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode =
                cmd.execute("--config", configFile.toString(), "backup", "full", "--account", "alice@example.com");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("FINISHED"));
    }

    @Test
    void incrementalBacksUpEveryDiscoveredAccountIncludingMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer("/home/alice@example.com/", 200, "tgz-content".getBytes());
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "incremental");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("inc-"));
        assertTrue(output.contains("FINISHED"));
    }

    @Test
    void incrementalWithAccountOptionBacksUpOnlyThatAccountsMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer("/home/alice@example.com/", 200, "tgz-content".getBytes());
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute(
                "--config", configFile.toString(), "backup", "incremental", "--account", "alice@example.com");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("FINISHED"));
    }

    @Test
    void mailboxBacksUpEveryDiscoveredAccountsMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer("/home/alice@example.com/", 200, "tgz-content".getBytes());
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "mailbox");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("mbox-"));
        assertTrue(output.contains("FINISHED"));
    }

    @Test
    void mailboxWithAccountOptionBacksUpOnlyThatAccountsMailbox() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        startMailboxServer("/home/alice@example.com/", 200, "tgz-content".getBytes());
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute(
                "--config", configFile.toString(), "backup", "mailbox", "--account", "alice@example.com");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("FINISHED"));
    }

    @Test
    void aliasBacksUpExplicitAlias() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alias1,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAlias"),
                new Attribute("uid", "alias@example.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode =
                cmd.execute("--config", configFile.toString(), "backup", "alias", "--account", "alias@example.com");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("FINISHED"));
    }

    @Test
    void distlistBacksUpDiscoveredList() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "cn=engineering,dc=example,dc=com",
                new Attribute("objectClass", "zimbraDistributionList"),
                new Attribute("cn", "engineering"),
                new Attribute("mail", "engineering@example.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "distlist");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("FINISHED"));
    }

    @Test
    void signaturePrintsNothingFoundWhenDirectoryHasNoSignatures() throws Exception {
        directoryServer = startDirectoryServer();
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "signature");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Nothing found to back up"));
    }

    @Test
    void domainBacksUpDiscoveredDomains() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "domain");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("domain-"));
        assertTrue(out.toString().contains("FINISHED"));
    }

    @Test
    void domainWithDomainOptionBacksUpOnlyThatDomain() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "backup", "domain", "--domain", "other.com");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("FINISHED"));
    }

    private static String sessionSuffixOf(StringWriter out) {
        String output = out.toString();
        int start = output.indexOf("ldap-") + "ldap-".length();
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

    private void startMailboxServer(String path, int statusCode, byte[] responseBody) throws IOException {
        mailboxServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mailboxServer.createContext(path, (HttpExchange exchange) -> {
            exchange.sendResponseHeaders(statusCode, responseBody.length == 0 ? -1 : responseBody.length);
            if (responseBody.length > 0) {
                exchange.getResponseBody().write(responseBody);
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
                  backupUser: zimbra
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
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
                """
                        .formatted(
                                directoryServer.getListenPort(),
                                mailboxRestBaseUrl,
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf")));
        return configFile;
    }

    private static CommandLine commandLine(StringWriter out, StringWriter err) {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }
}
