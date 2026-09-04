package io.zmbackup.local;

import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.Notifier;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Sends plain-text e-mail notifications by speaking SMTP directly over a socket to a local relay,
 * mirroring {@code notify_begin}/{@code notify_finish} in the bash tool's {@code NotifyAction.sh},
 * which submits through the {@code sendmail} command to the same local MTA.
 */
public class EmailNotifier implements Notifier {

    private final String smtpHost;
    private final int smtpPort;
    private final String sender;
    private final String recipient;
    private final boolean notifyOnBegin;
    private final boolean notifyOnFinishSuccess;
    private final boolean notifyOnFinishError;

    /**
     * @param smtpHost              host of the local SMTP relay to submit through
     * @param smtpPort              port of the local SMTP relay
     * @param sender                the {@code From} address, mirroring {@code EMAIL_SENDER}
     * @param recipient             the {@code To} address, mirroring {@code EMAIL_NOTIFY}
     * @param notifyOnBegin         whether to send a notification when a session starts
     * @param notifyOnFinishSuccess whether to send a notification when a session finishes successfully
     * @param notifyOnFinishError   whether to send a notification when a session fails
     */
    public EmailNotifier(
            String smtpHost,
            int smtpPort,
            String sender,
            String recipient,
            boolean notifyOnBegin,
            boolean notifyOnFinishSuccess,
            boolean notifyOnFinishError) {
        this.smtpHost = Objects.requireNonNull(smtpHost, "smtpHost must not be null");
        this.smtpPort = smtpPort;
        this.sender = requireNoCrlf(sender, "sender");
        this.recipient = requireNoCrlf(recipient, "recipient");
        this.notifyOnBegin = notifyOnBegin;
        this.notifyOnFinishSuccess = notifyOnFinishSuccess;
        this.notifyOnFinishError = notifyOnFinishError;
    }

    @Override
    public void notifyBegin(String sessionId, BackupType type) throws IOException {
        if (!notifyOnBegin) {
            return;
        }
        String subject = "Zmbackup - Backup routine for " + type.sessionPrefix() + " started";
        String body = "This is an automatic message to inform you that the " + type.sessionPrefix()
                + " backup session " + sessionId + " started at " + Instant.now() + ".\n";
        send(subject, body);
    }

    @Override
    public void notifyFinish(
            String sessionId, BackupType type, SessionStatus status, String size, int accountCount)
            throws IOException {
        boolean shouldNotify = status == SessionStatus.FINISHED ? notifyOnFinishSuccess : notifyOnFinishError;
        if (!shouldNotify) {
            return;
        }
        String subject = "Zmbackup - Backup routine for " + type.sessionPrefix() + " " + status.dbValue();
        String body = "This is an automatic message to inform you that the " + type.sessionPrefix()
                + " backup session " + sessionId + " completed at " + Instant.now() + ".\n\n"
                + "Here some information about this session:\n\n"
                + "Size: " + size + "\n"
                + "Accounts: " + accountCount + "\n"
                + "Status: " + status.dbValue() + "\n";
        send(subject, body);
    }

    private static String requireNoCrlf(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(fieldName + " must not contain CR or LF characters");
        }
        return value;
    }

    private void send(String subject, String body) throws IOException {
        try (Socket socket = new Socket(smtpHost, smtpPort);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = socket.getOutputStream()) {
            readResponse(in, 220);
            command(out, in, "EHLO localhost", 250);
            command(out, in, "MAIL FROM:<" + sender + ">", 250);
            command(out, in, "RCPT TO:<" + recipient + ">", 250);
            command(out, in, "DATA", 354);

            String message = "From: " + sender + "\r\n"
                    + "To: " + recipient + "\r\n"
                    + "Subject: " + subject + "\r\n"
                    + "\r\n"
                    + body
                    + "\r\n.\r\n";
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.flush();
            readResponse(in, 250);

            command(out, in, "QUIT", 221);
        }
    }

    private static void command(OutputStream out, BufferedReader in, String line, int expectedCode)
            throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        readResponse(in, expectedCode);
    }

    private static void readResponse(BufferedReader in, int expectedCode) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("SMTP server closed the connection unexpectedly");
            }
        } while (line.length() > 3 && line.charAt(3) == '-'); // keep reading a multi-line response
        if (!line.startsWith(Integer.toString(expectedCode))) {
            throw new IOException("Unexpected SMTP response: " + line);
        }
    }
}
