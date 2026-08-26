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

class AccountsCommandTest {

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

        int exitCode = cmd.execute("accounts");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(out.toString().contains("Usage: zmbackup accounts"));
    }

    @Test
    void listPrintsEveryAccount() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"));
        directoryServer.add(
                "uid=carol,dc=other,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "carol"),
                new Attribute("zimbraMailDeliveryAddress", "carol@other.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "accounts", "list");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("alice@example.com"));
        assertTrue(output.contains("carol@other.com"));
    }

    @Test
    void listWithDomainFiltersToThatDomain() throws Exception {
        directoryServer = startDirectoryServer();
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"));
        directoryServer.add(
                "uid=carol,dc=other,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "carol"),
                new Attribute("zimbraMailDeliveryAddress", "carol@other.com"));
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "accounts", "list", "--domain", "example.com");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("alice@example.com"));
        assertTrue(!output.contains("carol@other.com"));
    }

    private InMemoryDirectoryServer startDirectoryServer() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com", "dc=other,dc=com");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setSchema(null);
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.startListening();
        server.add("dc=example,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "example"));
        server.add("dc=other,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "other"));
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
