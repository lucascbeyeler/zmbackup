package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.PidLock;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class TruncateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void refusesToRunWithoutForceCleanAndLeavesTheDatabaseUntouched() throws Exception {
        Path configFile = writeConfig();
        AppContext.fromConfigFile(configFile).metadataStore().save(session("ldap-1"));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = commandLine(out, err);

        int exitCode = cmd.execute("--config", configFile.toString(), "truncate");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("--force-clean"));
        assertEquals(1, AppContext.fromConfigFile(configFile).metadataStore().listSessions().size());
    }

    @Test
    void forceCleanEmptiesTheDatabase() throws Exception {
        Path configFile = writeConfig();
        AppContext.fromConfigFile(configFile).metadataStore().save(session("ldap-1"));
        AppContext.fromConfigFile(configFile).metadataStore().save(session("ldap-2"));
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "truncate", "--force-clean");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("2 backup session(s) removed from the database."));
        assertTrue(out.toString().contains("TEST/DEV USE ONLY"));
        assertEquals(0, AppContext.fromConfigFile(configFile).metadataStore().listSessions().size());
    }

    @Test
    void failsWithoutRunningWhenAnotherProcessHoldsTheLock() throws Exception {
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = commandLine(out, err);

        try (PidLock lock = PidLock.acquire(tempDir)) {
            int exitCode = cmd.execute("--config", configFile.toString(), "truncate", "--force-clean");

            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
            assertTrue(err.toString().contains("already running"));
        }
    }

    private static BackupSession session(String sessionId) {
        Instant now = Instant.now();
        return new BackupSession(sessionId, BackupType.LDAP, SessionStatus.FINISHED, now, now, "1K");
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

    private static CommandLine commandLine(StringWriter out, StringWriter err) {
        CommandLine cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }
}
