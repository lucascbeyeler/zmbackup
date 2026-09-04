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
    void versionPrintsVersionFromBundledFile() throws IOException {
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--version");

        assertEquals(0, exitCode);
        try (var in = Main.class.getResourceAsStream("/VERSION")) {
            String version = new String(in.readAllBytes()).trim();
            assertTrue(out.toString().contains("zmbackup version: " + version));
        }
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
    void housekeepRemovesOldSessions() throws IOException {
        Path configFile = writeConfig();
        new SqliteMetadataStore(tempDir.resolve("sessions.sqlite3"))
                .save(
                        new BackupSession(
                                "full-20200101120000",
                                BackupType.FULL,
                                SessionStatus.FINISHED,
                                Instant.parse("2020-01-01T12:00:00Z"),
                                Instant.parse("2020-01-01T12:05:00Z"),
                                "10M"));
        StringWriter out = new StringWriter();
        CommandLine cmd = commandLine(out, new StringWriter());

        int exitCode = cmd.execute("--config", configFile.toString(), "housekeep");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Backup session full-20200101120000 removed."));
        assertTrue(
                new SqliteMetadataStore(tempDir.resolve("sessions.sqlite3"))
                        .findSession("full-20200101120000")
                        .isEmpty());
    }

    @Test
    void deleteRemovesStoredSession() throws IOException {
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

        int exitCode =
                cmd.execute("--config", configFile.toString(), "delete", "--session", "full-20260101120000");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Backup session full-20260101120000 removed."));
        assertTrue(
                new SqliteMetadataStore(tempDir.resolve("sessions.sqlite3"))
                        .findSession("full-20260101120000")
                        .isEmpty());
    }

    @Test
    void deleteReportsSessionNotFound() throws IOException {
        Path configFile = writeConfig();
        StringWriter err = new StringWriter();
        CommandLine cmd = commandLine(new StringWriter(), err);

        int exitCode =
                cmd.execute("--config", configFile.toString(), "delete", "--session", "full-20260101999999");

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("full-20260101999999 not found in database"));
    }

    @Test
    void deleteRejectsMalformedSessionId() throws IOException {
        Path configFile = writeConfig();
        StringWriter err = new StringWriter();
        CommandLine cmd = commandLine(new StringWriter(), err);

        int exitCode = cmd.execute("--config", configFile.toString(), "delete", "--session", "does-not-exist");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("Error! Invalid session ID: does-not-exist"));
    }

    @Test
    void deniesAccessWhenBackupUserDoesNotMatchTheRunningUser() throws IOException {
        Path configFile = writeConfig("not-the-real-user");
        StringWriter err = new StringWriter();
        CommandLine cmd = commandLine(new StringWriter(), err);

        int exitCode = cmd.execute("--config", configFile.toString(), "list");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(err.toString().contains("You need to be not-the-real-user to run this software."));
    }

    private Path writeConfig() throws IOException {
        return writeConfig(System.getProperty("user.name"));
    }

    private Path writeConfig(String backupUser) throws IOException {
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
                                backupUser,
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
