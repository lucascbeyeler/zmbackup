package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link ZmbackupLogHandler} against a real log file and a stub {@code logger} script
 * that records the arguments it was invoked with, standing in for the real syslog {@code logger}
 * command.
 */
class ZmbackupLogHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void publishAppendsLogfileLineFormattedLikeZmlog() throws Exception {
        Path logFile = tempDir.resolve("zmbackup.log");
        ZmbackupLogHandler handler = new ZmbackupLogHandler(logFile, stubLoggerCommand());

        handler.publish(new LogRecord(Level.INFO, "backup-session-1 started"));

        String content = Files.readString(logFile);
        assertTrue(
                content.matches(
                        "(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\[local7\\.info\\]"
                                + " backup-session-1 started\\n"),
                "unexpected log line: " + content);
    }

    @Test
    void severeMapsToLocal7Err() throws Exception {
        Path logFile = tempDir.resolve("zmbackup.log");
        ZmbackupLogHandler handler = new ZmbackupLogHandler(logFile, stubLoggerCommand());

        handler.publish(new LogRecord(Level.SEVERE, "boom"));

        assertTrue(Files.readString(logFile).contains("[local7.err] boom"));
    }

    @Test
    void warningMapsToLocal7Warn() throws Exception {
        Path logFile = tempDir.resolve("zmbackup.log");
        ZmbackupLogHandler handler = new ZmbackupLogHandler(logFile, stubLoggerCommand());

        handler.publish(new LogRecord(Level.WARNING, "careful"));

        assertTrue(Files.readString(logFile).contains("[local7.warn] careful"));
    }

    @Test
    void invokesLoggerWithMinusIMinusPAndTheLocal7Priority() throws Exception {
        Path logFile = tempDir.resolve("zmbackup.log");
        Path receivedArgs = tempDir.resolve("received-args.log");
        ZmbackupLogHandler handler = new ZmbackupLogHandler(logFile, stubLoggerCommand(receivedArgs));

        handler.publish(new LogRecord(Level.SEVERE, "disk full"));

        List<String> args = Files.readAllLines(receivedArgs);
        assertEquals(List.of("-i", "-p", "local7.err", "disk full"), args);
    }

    @Test
    void publishAppendsMultipleRecordsAsSeparateLines() throws Exception {
        Path logFile = tempDir.resolve("zmbackup.log");
        ZmbackupLogHandler handler = new ZmbackupLogHandler(logFile, stubLoggerCommand());

        handler.publish(new LogRecord(Level.INFO, "first"));
        handler.publish(new LogRecord(Level.INFO, "second"));

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("first"));
        assertTrue(lines.get(1).endsWith("second"));
    }

    @Test
    void aFailingLoggerCommandDoesNotStopTheLogfileWrite() throws Exception {
        Path logFile = tempDir.resolve("zmbackup.log");
        ZmbackupLogHandler handler = new ZmbackupLogHandler(logFile, List.of(tempDir.resolve("no-such-binary").toString()));

        handler.publish(new LogRecord(Level.INFO, "still written"));

        assertTrue(Files.readString(logFile).contains("still written"));
    }

    private List<String> stubLoggerCommand() throws IOException {
        return stubLoggerCommand(tempDir.resolve("received-args.log"));
    }

    /** Writes an executable script that appends every argument it receives, one per line, to {@code receivedArgs}. */
    private List<String> stubLoggerCommand(Path receivedArgs) throws IOException {
        Path script = tempDir.resolve("stub-logger-" + System.nanoTime() + ".sh");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "for arg in \"$@\"; do printf '%s\\n' \"$arg\" >> '"
                        + receivedArgs
                        + "'; done\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return List.of(script.toString());
    }
}
