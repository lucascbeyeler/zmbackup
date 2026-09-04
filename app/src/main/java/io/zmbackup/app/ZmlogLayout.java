package io.zmbackup.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
