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
