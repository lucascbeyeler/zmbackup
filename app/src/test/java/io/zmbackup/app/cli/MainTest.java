package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.local.SqliteMetadataStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MainTest {

    @TempDir
    Path tempDir;

    @Test
    void noSubcommandPrintsUsage() {
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute();

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(out.toString().contains("Usage: zmbackup"));
    }

    @Test
    void listPrintsEmptyTableWhenNoSessionsStored() throws IOException {
        Path configFile = writeConfig();
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "list");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("Session ID"));
        assertTrue(output.contains("Type"));
    }

    @Test
    void listPrintsStoredSessions() throws IOException {
        Path configFile = writeConfig();
        new SqliteMetadataStore(tempDir.resolve("sessions.sqlite3"))
                .save(
                        new BackupSession(
                                "full-20260101120000",
                                BackupType.FULL,
                                SessionStatus.FINISHED,
                                Instant.parse("2026-01-01T12:00:00Z"),
                                Instant.parse("2026-01-01T12:05:00Z"),
                                "10M"));
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "list");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("full-20260101120000"));
    }

    @Test
    void backupIsStubbed() {
        assertStubbed("backup");
    }

    @Test
    void restoreIsStubbed() {
        assertStubbed("restore");
    }

    @Test
    void deleteIsStubbed() {
        assertStubbed("delete");
    }

    @Test
    void housekeepIsStubbed() {
        assertStubbed("housekeep");
    }

    private void assertStubbed(String subcommand) {
        StringWriter err = new StringWriter();
        CommandLine cmd = commandLine(new StringWriter(), err);

        int exitCode = cmd.execute(subcommand);

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("not yet implemented"));
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
                                tempDir, tempDir.resolve("zmbackup.log"), tempDir.resolve("blockedlist.conf")));
        return configFile;
    }

    private static CommandLine commandLine(StringWriter out, StringWriter err) {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }
}
