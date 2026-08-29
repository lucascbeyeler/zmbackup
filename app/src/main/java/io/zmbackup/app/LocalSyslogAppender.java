package io.zmbackup.app;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A Logback appender that sends each record to the local {@code local7} syslog facility the same
 * way the bash tool's {@code zmlog} (in {@code MiscAction.sh}) does: by invoking {@code logger -i
 * -p local7.<severity> <message>} (the exact command {@code zmlog} runs).
 *
 * <p>Java has no public API for {@code AF_UNIX} datagram sockets — {@link
 * java.nio.channels.DatagramChannel} only supports {@link java.net.StandardProtocolFamily#UNIX}
 * for {@link java.nio.channels.SocketChannel}/{@link java.nio.channels.ServerSocketChannel} (i.e.
 * stream sockets), and Linux's {@code /dev/log} is a datagram socket — so shelling out to {@code
 * logger} is the only way to reach syslog the way {@code zmlog} does.
 *
 * <p>A failure to invoke {@code logger} is reported via {@link #addError} rather than propagated:
 * a logging failure should never abort a backup or restore run.
 */
public final class LocalSyslogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String FACILITY = "local7";
    private static final List<String> DEFAULT_LOGGER_COMMAND = List.of("logger");
    private static final Duration LOGGER_TIMEOUT = Duration.ofSeconds(5);

    private final List<String> loggerCommand;

    public LocalSyslogAppender() {
        this(DEFAULT_LOGGER_COMMAND);
    }

    LocalSyslogAppender(List<String> loggerCommand) {
        this.loggerCommand = List.copyOf(Objects.requireNonNull(loggerCommand, "loggerCommand must not be null"));
        if (this.loggerCommand.isEmpty()) {
            throw new IllegalArgumentException("loggerCommand must not be empty");
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        String priority = FACILITY + "." + ZmlogLayout.severityOf(event.getLevel());
        List<String> command = new ArrayList<>(loggerCommand);
        command.add("-i");
        command.add("-p");
        command.add(priority);
        command.add(event.getFormattedMessage());
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(LOGGER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                addError("logger command timed out");
            } else if (process.exitValue() != 0) {
                addError("logger command exited with status " + process.exitValue());
            }
        } catch (IOException e) {
            addError("Failed to invoke logger command " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            addError("Interrupted while invoking logger command " + command, e);
        }
    }
}
