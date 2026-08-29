package io.zmbackup.app;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A {@link Handler} that stores log records the same way the bash tool's {@code zmlog} (in {@code
 * MiscAction.sh}) does: every record is handed to the system {@code logger} command as {@code
 * logger -i -p local7.<severity> <message>} (the exact invocation {@code zmlog} makes), and
 * appended as a line to {@code $LOGFILE} in the same {@code "yyyy-MM-dd HH:mm:ss
 * [local7.<severity>] <message>"} format {@code zmlog} writes. The record's {@link Level} is
 * mapped to a severity the same way the bash tool's call sites pick between {@code
 * local7.info}/{@code local7.warn}/{@code local7.err}.
 *
 * <p>Both destinations are best-effort: a failure to invoke {@code logger} or to append to the
 * log file is reported via {@link #reportError} rather than propagated, since a logging failure
 * should never abort a backup or restore run.
 */
public final class ZmbackupLogHandler extends Handler {

    private static final String FACILITY = "local7";
    private static final List<String> DEFAULT_LOGGER_COMMAND = List.of("logger");
    private static final Duration LOGGER_TIMEOUT = Duration.ofSeconds(5);

    private static final DateTimeFormatter LOGFILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Path logFile;
    private final List<String> loggerCommand;
    private final Formatter messageFormatter = new SimpleFormatter();

    public ZmbackupLogHandler(Path logFile) {
        this(logFile, DEFAULT_LOGGER_COMMAND);
    }

    ZmbackupLogHandler(Path logFile, List<String> loggerCommand) {
        this.logFile = Objects.requireNonNull(logFile, "logFile must not be null");
        this.loggerCommand = List.copyOf(Objects.requireNonNull(loggerCommand, "loggerCommand must not be null"));
        if (this.loggerCommand.isEmpty()) {
            throw new IllegalArgumentException("loggerCommand must not be empty");
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String severity = severityOf(record.getLevel());
        String message = messageFormatter.formatMessage(record);
        appendToLogFile(ZonedDateTime.now(), severity, message);
        sendToSyslog(severity, message);
    }

    @Override
    public void flush() {
        // Every publish() call writes and closes its own resources; nothing to flush.
    }

    @Override
    public void close() {
        // No persistent resources are held between publish() calls.
    }

    /** Mirrors which {@code local7.<severity>} priority each bash {@code zmlog} call site picks. */
    private static String severityOf(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "err";
        }
        if (value >= Level.WARNING.intValue()) {
            return "warn";
        }
        return "info";
    }

    private void appendToLogFile(ZonedDateTime now, String severity, String message) {
        String line = LOGFILE_TIMESTAMP.format(now) + " [" + FACILITY + "." + severity + "] " + message + "\n";
        try {
            Files.write(
                    logFile,
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            reportError("Failed to append to " + logFile, e, ErrorManager.WRITE_FAILURE);
        }
    }

    private void sendToSyslog(String severity, String message) {
        String priority = FACILITY + "." + severity;
        List<String> command = new ArrayList<>(loggerCommand);
        command.add("-i");
        command.add("-p");
        command.add(priority);
        command.add(message);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(LOGGER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reportError("logger command timed out", null, ErrorManager.WRITE_FAILURE);
            } else if (process.exitValue() != 0) {
                reportError(
                        "logger command exited with status " + process.exitValue(),
                        null,
                        ErrorManager.WRITE_FAILURE);
            }
        } catch (IOException e) {
            reportError("Failed to invoke logger command " + command, e, ErrorManager.WRITE_FAILURE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reportError("Interrupted while invoking logger command " + command, e, ErrorManager.WRITE_FAILURE);
        }
    }
}
