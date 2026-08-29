package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.PidLock;
import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HousekeepCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void removesOldAndEmptySessions() throws Exception {
        Path configFile = writeConfig(7);
        AppContext context = AppContext.fromConfigFile(configFile);
        Instant now = Instant.now();

        BackupSession old = session("ldap-old", now.minus(10, ChronoUnit.DAYS));
        context.metadataStore().save(old);
        context.metadataStore()
                .recordAccountBackup(
                        new BackupAccountRecord(null, "ldap-old", "alice@example.com", "1K", now, now));
        try (var writer = context.storageProvider().openWrite("ldap-old", "alice@example.com", "ldiff")) {
            writer.write("dn: uid=alice\n".getBytes());
        }

        BackupSession recent = session("ldap-recent", now.minus(1, ChronoUnit.DAYS));
        context.metadataStore().save(recent);
        context.metadataStore()
                .recordAccountBackup(
                        new BackupAccountRecord(null, "ldap-recent", "bob@example.com", "1K", now, now));

        BackupSession empty = session("ldap-empty", now);
        context.metadataStore().save(empty);

        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out);

        int exitCode = cmd.execute("--config", configFile.toString(), "housekeep");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("Backup session ldap-old removed."));
        assertTrue(output.contains("Backup session ldap-empty removed."));
        assertFalse(output.contains("ldap-recent removed"));
        assertTrue(context.metadataStore().findSession("ldap-old").isEmpty());
        assertTrue(context.metadataStore().findSession("ldap-empty").isEmpty());
        assertTrue(context.metadataStore().findSession("ldap-recent").isPresent());
    }

    @Test
    void failsWithoutRunningWhenAnotherProcessHoldsTheLock() throws Exception {
        Path configFile = writeConfig(7);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        try (PidLock lock = PidLock.acquire(tempDir)) {
            int exitCode = cmd.execute("--config", configFile.toString(), "housekeep");

            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
            assertTrue(err.toString().contains("already running"));
        }
    }

    private static BackupSession session(String sessionId, Instant completedAt) {
        return new BackupSession(
                sessionId, BackupType.LDAP, SessionStatus.FINISHED, completedAt.minusSeconds(60), completedAt, "1K");
    }

    private Path writeConfig(int rotateDays) throws IOException {
        Path configFile = tempDir.resolve("zmbackup.yaml");
        Files.writeString(
                configFile,
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:389
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
                  rotateDays: %d
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """
                        .formatted(
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf"),
                                rotateDays));
        return configFile;
    }

    private static CommandLine commandLine(StringWriter out) {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd;
    }
}
