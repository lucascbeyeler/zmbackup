package io.zmbackup.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats a log line exactly like the bash tool's {@code zmlog} (in {@code MiscAction.sh}) writes
 * to {@code $LOGFILE}: {@code "yyyy-MM-dd HH:mm:ss [local7.<severity>] <message>"}, where {@code
 * severity} is picked the same way the bash tool's call sites choose between {@code
 * local7.info}/{@code local7.warn}/{@code local7.err}.
 */
public final class ZmlogLayout extends LayoutBase<ILoggingEvent> {

    private static final String FACILITY = "local7";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    @Override
    public String doLayout(ILoggingEvent event) {
        String timestamp = TIMESTAMP.format(
                Instant.ofEpochMilli(event.getTimeStamp()).atZone(ZoneId.systemDefault()));
        return timestamp + " [" + FACILITY + "." + severityOf(event.getLevel()) + "] " + event.getFormattedMessage()
                + System.lineSeparator();
    }

    /** Mirrors which {@code local7.<severity>} priority each bash {@code zmlog} call site picks. */
    static String severityOf(Level level) {
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return "err";
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            return "warn";
        }
        return "info";
    }
}
