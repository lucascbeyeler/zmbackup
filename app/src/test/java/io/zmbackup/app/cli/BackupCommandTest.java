package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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

    @AfterEach
    void tearDown() {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
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
                                directoryServer.getListenPort(),
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
