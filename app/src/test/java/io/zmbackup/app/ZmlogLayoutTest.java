package io.zmbackup.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ZmlogLayoutTest {

    @Test
    void infoLevelFormatsAsLocal7Info() {
        String line = layoutOf(Level.INFO, "backup-session-1 started");

        assertTrue(
                line.matches("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\[local7\\.info\\]"
                        + " backup-session-1 started\\R"),
                "unexpected line: " + line);
    }

    @Test
    void warnLevelFormatsAsLocal7Warn() {
        String line = layoutOf(Level.WARN, "careful");

        assertTrue(line.contains("[local7.warn] careful"), "unexpected line: " + line);
    }

    @Test
    void errorLevelFormatsAsLocal7Err() {
        String line = layoutOf(Level.ERROR, "boom");

        assertTrue(line.contains("[local7.err] boom"), "unexpected line: " + line);
    }

    @Test
    void debugLevelFormatsAsLocal7Info() {
        String line = layoutOf(Level.DEBUG, "detail");

        assertTrue(line.contains("[local7.info] detail"), "unexpected line: " + line);
    }

    @Test
    void appendsTheStackTraceWhenTheEventCarriesAThrowable() {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.WARN);
        event.setMessage("Backup failed for account@example.com");
        event.setTimeStamp(System.currentTimeMillis());
        event.setThrowableProxy(new ThrowableProxy(new IOException("connection reset")));

        String line = new ZmlogLayout().doLayout(event);

        assertTrue(
                line.contains("[local7.warn] Backup failed for account@example.com"
                        + System.lineSeparator() + "java.io.IOException: connection reset"),
                "unexpected line: " + line);
        assertTrue(line.contains("\tat "), "expected a stack frame in: " + line);
    }

    private static String layoutOf(Level level, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return new ZmlogLayout().doLayout(event);
    }
}
