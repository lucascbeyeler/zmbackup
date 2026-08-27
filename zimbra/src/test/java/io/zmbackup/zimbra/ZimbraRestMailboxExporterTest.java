package io.zmbackup.zimbra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ZimbraRestMailboxExporterTest {

    private static final String ADMIN_USER = "zimbra";
    private static final String ADMIN_PASSWORD = "secret";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exportsFullMailboxContentOnHttp200() throws Exception {
        byte[] tgzBytes = "tgz-content".getBytes(StandardCharsets.UTF_8);
        RecordingHandler handler = new RecordingHandler(200, tgzBytes);
        String baseUrl = startServer("/home/alice@example.com/", handler);
        ZimbraRestMailboxExporter exporter = new ZimbraRestMailboxExporter(baseUrl, ADMIN_USER, ADMIN_PASSWORD);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        boolean wroteContent = exporter.export("alice@example.com", destination, null);

        assertTrue(wroteContent);
        assertEquals("tgz-content", destination.toString(StandardCharsets.UTF_8));
        assertEquals("fmt=tgz&resolve=skip", handler.capturedQuery);
        assertEquals(
                "Basic " + Base64.getEncoder().encodeToString((ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes()),
                handler.capturedAuthorization);
    }

    @Test
    void appendsAfterQueryParameterWhenSinceIsGiven() throws Exception {
        RecordingHandler handler = new RecordingHandler(200, new byte[0]);
        String baseUrl = startServer("/home/alice@example.com/", handler);
        ZimbraRestMailboxExporter exporter = new ZimbraRestMailboxExporter(baseUrl, ADMIN_USER, ADMIN_PASSWORD);
        Instant since = ZonedDateTime.of(2026, 7, 2, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();

        exporter.export("alice@example.com", new ByteArrayOutputStream(), since);

        assertEquals("fmt=tgz&resolve=skip&query=after:%2207/02/2026%22", handler.capturedQuery);
    }

    @Test
    void returnsFalseWithoutWritingOnHttp204() throws Exception {
        RecordingHandler handler = new RecordingHandler(204, new byte[0]);
        String baseUrl = startServer("/home/alice@example.com/", handler);
        ZimbraRestMailboxExporter exporter = new ZimbraRestMailboxExporter(baseUrl, ADMIN_USER, ADMIN_PASSWORD);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        boolean wroteContent = exporter.export("alice@example.com", destination, Instant.now());

        assertFalse(wroteContent);
        assertEquals(0, destination.size());
    }

    @Test
    void throwsIOExceptionOnNonSuccessStatus() throws Exception {
        RecordingHandler handler = new RecordingHandler(500, "boom".getBytes(StandardCharsets.UTF_8));
        String baseUrl = startServer("/home/alice@example.com/", handler);
        ZimbraRestMailboxExporter exporter = new ZimbraRestMailboxExporter(baseUrl, ADMIN_USER, ADMIN_PASSWORD);

        IOException exception = assertThrows(
                IOException.class,
                () -> exporter.export("alice@example.com", new ByteArrayOutputStream(), null));
        assertTrue(exception.getMessage().contains("500"));
    }

    @Test
    void wrapsUnreachableServerInIOException() {
        ZimbraRestMailboxExporter exporter =
                new ZimbraRestMailboxExporter("http://127.0.0.1:1", ADMIN_USER, ADMIN_PASSWORD);

        assertThrows(
                IOException.class,
                () -> exporter.export("alice@example.com", new ByteArrayOutputStream(), null));
    }

    @Test
    void restoreIsNotYetImplemented() {
        ZimbraRestMailboxExporter exporter =
                new ZimbraRestMailboxExporter("http://127.0.0.1:1", ADMIN_USER, ADMIN_PASSWORD);

        assertThrows(
                UnsupportedOperationException.class,
                () -> exporter.restore("alice@example.com", InputStream.nullInputStream()));
    }

    private String startServer(String path, RecordingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Records the request it received and replies with a fixed status code and body. */
    private static final class RecordingHandler implements com.sun.net.httpserver.HttpHandler {
        private final int statusCode;
        private final byte[] responseBody;
        private volatile String capturedQuery;
        private volatile String capturedAuthorization;

        RecordingHandler(int statusCode, byte[] responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            capturedQuery = exchange.getRequestURI().getRawQuery();
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.sendResponseHeaders(statusCode, responseBody.length == 0 ? -1 : responseBody.length);
            if (responseBody.length > 0) {
                exchange.getResponseBody().write(responseBody);
            }
            exchange.close();
        }
    }
}
