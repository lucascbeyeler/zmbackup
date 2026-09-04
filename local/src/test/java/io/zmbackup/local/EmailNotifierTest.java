package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EmailNotifierTest {

    private FakeSmtpServer smtpServer;

    @AfterEach
    void tearDown() throws IOException {
        if (smtpServer != null) {
            smtpServer.close();
        }
    }

    @Test
    void notifyBeginSendsMessageWhenLevelEnablesBegin() throws Exception {
        smtpServer = FakeSmtpServer.start();
        EmailNotifier notifier = new EmailNotifier(
                "127.0.0.1", smtpServer.port(), "root@example.com", "admin@example.com", true, true, true);

        notifier.notifyBegin("full-1", BackupType.FULL);

        FakeSmtpServer.Message message = smtpServer.awaitMessage();
        assertEquals("root@example.com", message.from());
        assertEquals("admin@example.com", message.to());
        assertTrue(message.data().contains("Subject: Zmbackup - Backup routine for full started"));
        assertTrue(message.data().contains("full-1"));
    }

    @Test
    void notifyBeginSendsNothingWhenLevelDisablesBegin() throws Exception {
        smtpServer = FakeSmtpServer.start();
        EmailNotifier notifier = new EmailNotifier(
                "127.0.0.1", smtpServer.port(), "root@example.com", "admin@example.com", false, true, true);

        notifier.notifyBegin("full-1", BackupType.FULL);

        assertFalse(smtpServer.receivedAnyMessage());
    }

    @Test
    void notifyFinishSendsMessageOnSuccessWhenEnabled() throws Exception {
        smtpServer = FakeSmtpServer.start();
        EmailNotifier notifier = new EmailNotifier(
                "127.0.0.1", smtpServer.port(), "root@example.com", "admin@example.com", true, true, false);

        notifier.notifyFinish("full-1", BackupType.FULL, SessionStatus.FINISHED, "10M", 3);

        FakeSmtpServer.Message message = smtpServer.awaitMessage();
        assertTrue(message.data().contains("FINISHED"));
        assertTrue(message.data().contains("Size: 10M"));
        assertTrue(message.data().contains("Accounts: 3"));
    }

    @Test
    void notifyFinishSendsNothingOnSuccessWhenOnlyErrorEnabled() throws Exception {
        smtpServer = FakeSmtpServer.start();
        EmailNotifier notifier = new EmailNotifier(
                "127.0.0.1", smtpServer.port(), "root@example.com", "admin@example.com", true, false, true);

        notifier.notifyFinish("full-1", BackupType.FULL, SessionStatus.FINISHED, "10M", 3);

        assertFalse(smtpServer.receivedAnyMessage());
    }

    @Test
    void notifyFinishSendsMessageOnFailureWhenErrorEnabled() throws Exception {
        smtpServer = FakeSmtpServer.start();
        EmailNotifier notifier = new EmailNotifier(
                "127.0.0.1", smtpServer.port(), "root@example.com", "admin@example.com", true, false, true);

        notifier.notifyFinish("full-1", BackupType.FULL, SessionStatus.FAILED, "0", 0);

        FakeSmtpServer.Message message = smtpServer.awaitMessage();
        assertTrue(message.data().contains("FAILED"));
        assertTrue(message.data().contains("Size: 0"));
        assertTrue(message.data().contains("Accounts: 0"));
    }

    @Test
    void throwsIOExceptionWhenRelayIsUnreachable() {
        EmailNotifier notifier =
                new EmailNotifier("127.0.0.1", 1, "root@example.com", "admin@example.com", true, true, true);

        assertThrows(IOException.class, () -> notifier.notifyBegin("full-1", BackupType.FULL));
    }

    @Test
    void rejectsSenderContainingCrlf() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailNotifier(
                        "127.0.0.1", 25, "root@example.com\r\nRCPT TO:<hacked@evil.com>", "admin@example.com",
                        true, true, true));
    }

    @Test
    void rejectsRecipientContainingCrlf() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailNotifier(
                        "127.0.0.1", 25, "root@example.com", "admin@example.com\r\nRCPT TO:<hacked@evil.com>",
                        true, true, true));
    }

    private static final class FakeSmtpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final CompletableFuture<Message> received = new CompletableFuture<>();

        private FakeSmtpServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        static FakeSmtpServer start() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
            FakeSmtpServer server = new FakeSmtpServer(serverSocket);
            Thread thread = new Thread(server::acceptOnce, "fake-smtp-server");
            thread.setDaemon(true);
            thread.start();
            return server;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        Message awaitMessage() throws Exception {
            return received.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        boolean receivedAnyMessage() {
            try {
                received.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (Exception e) {
                return false;
            }
        }

        private void acceptOnce() {
            try (Socket socket = serverSocket.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream out = socket.getOutputStream()) {
                write(out, "220 fake-smtp ready\r\n");
                String from = null;
                String to = null;
                List<String> dataLines = new ArrayList<>();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("EHLO")) {
                        write(out, "250 ok\r\n");
                    } else if (line.startsWith("MAIL FROM:")) {
                        from = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                        write(out, "250 ok\r\n");
                    } else if (line.startsWith("RCPT TO:")) {
                        to = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                        write(out, "250 ok\r\n");
                    } else if (line.startsWith("DATA")) {
                        write(out, "354 send it\r\n");
                        String dataLine;
                        while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                            dataLines.add(dataLine);
                        }
                        write(out, "250 ok\r\n");
                        received.complete(new Message(from, to, String.join("\n", dataLines)));
                    } else if (line.startsWith("QUIT")) {
                        write(out, "221 bye\r\n");
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        private static void write(OutputStream out, String line) throws IOException {
            out.write(line.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }

        record Message(String from, String to, String data) {}
    }
}
