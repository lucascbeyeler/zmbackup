package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.PidLock;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MigrateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void importsSessionsTxtAndRenamesItOnceMigrated() throws Exception {
        Path configFile = writeConfig();
        Files.writeString(
                tempDir.resolve("sessions.txt"),
                """
                SESSION: full-20260101120000 started on Thu Jan  1 12:00:00 UTC 2026
                full-20260101120000:alice@example.com:01/01/26
                SESSION: full-20260101120000 completed in Thu Jan  1 12:05:00 UTC 2026
                """);
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out);

        int exitCode = cmd.execute("--config", configFile.toString(), "migrate");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Imported 1 backup session(s)"));
        assertFalse(Files.exists(tempDir.resolve("sessions.txt")));
        assertTrue(Files.exists(tempDir.resolve("sessions.txt.migrated")));

        AppContext context = AppContext.fromConfigFile(configFile);
        var session = context.metadataStore().findSession("full-20260101120000").orElseThrow();
        assertEquals(BackupType.FULL, session.type());
        assertEquals(SessionStatus.FINISHED, session.status());
        assertEquals(1, context.metadataStore().findAccountsForSession("full-20260101120000").size());
    }

    @Test
    void doesNothingWhenNoSessionsTxtExists() throws Exception {
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out);

        int exitCode = cmd.execute("--config", configFile.toString(), "migrate");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("nothing to migrate"));
    }

    @Test
    void runningTwiceOnlyImportsOnce() throws Exception {
        Path configFile = writeConfig();
        Files.writeString(
                tempDir.resolve("sessions.txt"),
                "SESSION: ldap-20260101120000 started on Thu Jan  1 12:00:00 UTC 2026\n"
                        + "SESSION: ldap-20260101120000 completed in Thu Jan  1 12:05:00 UTC 2026\n");
        commandLine(new StringWriter()).execute("--config", configFile.toString(), "migrate");

        StringWriter out = new StringWriter();
        int exitCode = commandLine(out).execute("--config", configFile.toString(), "migrate");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("nothing to migrate"));
        AppContext context = AppContext.fromConfigFile(configFile);
        assertEquals(1, context.metadataStore().listSessions().size());
    }

    @Test
    void failsWithoutRunningWhenAnotherProcessHoldsTheLock() throws Exception {
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        try (PidLock lock = PidLock.acquire(tempDir)) {
            int exitCode = cmd.execute("--config", configFile.toString(), "migrate");

            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
            assertTrue(err.toString().contains("already running"));
        }
    }

    private Path writeConfig() throws IOException {
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
                                System.getProperty("user.name"),
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf")));
        return configFile;
    }

    private static CommandLine commandLine(StringWriter out) {
        CommandLine cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd;
    }
}
