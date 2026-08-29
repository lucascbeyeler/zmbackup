package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.ContextBase;
import ch.qos.logback.core.status.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link LocalSyslogAppender} against a stub {@code logger} script that records the
 * arguments it was invoked with, standing in for the real syslog {@code logger} command.
 */
class LocalSyslogAppenderTest {

    @TempDir
    Path tempDir;

    @Test
    void invokesLoggerWithMinusIMinusPAndTheLocal7Priority() throws Exception {
        Path receivedArgs = tempDir.resolve("received-args.log");
        LocalSyslogAppender appender = new LocalSyslogAppender(stubLoggerCommand(receivedArgs));
        appender.start();

        appender.doAppend(eventOf(Level.ERROR, "disk full"));

        List<String> args = Files.readAllLines(receivedArgs);
        assertEquals(List.of("-i", "-p", "local7.err", "disk full"), args);
    }

    @Test
    void warnLevelMapsToLocal7Warn() throws Exception {
        Path receivedArgs = tempDir.resolve("received-args.log");
        LocalSyslogAppender appender = new LocalSyslogAppender(stubLoggerCommand(receivedArgs));
        appender.start();

        appender.doAppend(eventOf(Level.WARN, "careful"));

        assertEquals(List.of("-i", "-p", "local7.warn", "careful"), Files.readAllLines(receivedArgs));
    }

    @Test
    void aFailingLoggerCommandReportsAStatusRatherThanThrowing() {
        Context context = new ContextBase();
        LocalSyslogAppender appender =
                new LocalSyslogAppender(List.of(tempDir.resolve("no-such-binary").toString()));
        appender.setContext(context);
        appender.start();

        appender.doAppend(eventOf(Level.INFO, "still safe"));

        List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
        assertTrue(statuses.stream().anyMatch(s -> s.getLevel() == Status.ERROR), "expected an error status");
    }

    private static LoggingEvent eventOf(Level level, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
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
