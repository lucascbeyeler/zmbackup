package io.zmbackup.zimbra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three cases from the export strategy investigation (§3), which behaved differently on Java 17
 * and newer JDKs while the client relied on {@code HttpRequest.timeout()}.
 */
class ZimbraRestTimeoutTest {

    private static final String ACCOUNT = "alice@example.com";
    private static final String PATH = "/service/home/" + ACCOUNT + "/";

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void aHealthyExportLongerThanEveryDeadlineCompletes() throws Exception {
        byte[] body = TestTars.tar().message(1, "Inbox", "x".repeat(20_000)).bytes();
        // 3 s in total, a chunk every ~150 ms: longer than the 1 s header and inactivity limits.
        server.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body).withChunkedDribbleDelay(20, 3000)));

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertTrue(exporter(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))
                .export(ACCOUNT, destination, null));

        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(destination.toByteArray()))) {
            assertArrayEquals(body, in.readAllBytes());
        }
    }

    @Test
    void aStalledExportFailsWithinTheInactivityLimit() throws Exception {
        // Headers and the first part of the body at once, then nothing, with the socket left open.
        try (ServerSocket stalling = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CountDownLatch release = new CountDownLatch(1);
            Thread serverThread = new Thread(() -> serveThenStall(stalling, release));
            serverThread.setDaemon(true);
            serverThread.start();
            MailboxTransferSettings settings = new MailboxTransferSettings(Duration.ofSeconds(5),
                    Duration.ofSeconds(1), Duration.ofSeconds(5), false, false, null, 0, Duration.ZERO,
                    Duration.ofHours(1));
            ZimbraRestMailboxExporter exporter = new ZimbraRestMailboxExporter(
                    "http://127.0.0.1:" + stalling.getLocalPort(), "zimbra", "secret", null, false, settings);

            long start = System.nanoTime();
            IOException failure = assertThrows(
                    IOException.class, () -> exporter.export(ACCOUNT, new ByteArrayOutputStream(), null));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            release.countDown();

            assertInstanceOf(InactivityTimeoutBodySubscriber.InactivityTimeoutException.class, failure);
            assertTrue(elapsedMillis < 4_000, "took " + elapsedMillis + " ms");
        }
    }

    private static void serveThenStall(ServerSocket server, CountDownLatch release) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            StringBuilder request = new StringBuilder();
            while (!request.toString().contains("\r\n\r\n")) {
                int n = in.read(buffer);
                if (n < 0) {
                    return;
                }
                request.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-tar\r\nContent-Length: 1000000\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[1024]);
            out.flush();
            release.await(30, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            // The client gave up, as intended.
        }
    }

    @Test
    void anExportWhoseHeadersNeverArriveFailsAtTheHeaderDeadline() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(10_000)));
        ZimbraRestMailboxExporter exporter =
                exporter(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));

        long start = System.nanoTime();
        assertThrows(HttpTimeoutException.class, () -> exporter.export(ACCOUNT, new ByteArrayOutputStream(), null));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
    }

    @Test
    void aRestoreLongerThanTheExportDeadlinesSucceedsWithinTheRestoreDeadline() {
        // Zimbra replies only once the whole import is done.
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(3_000)));
        ZimbraRestMailboxExporter exporter =
                exporter(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10));

        assertDoesNotThrow(() -> exporter.restore(ACCOUNT, new ByteArrayInputStream("tgz".getBytes())));
    }

    @Test
    void aRestoreFailsAtTheRestoreDeadline() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
        ZimbraRestMailboxExporter exporter =
                exporter(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(1));

        assertThrows(HttpTimeoutException.class,
                () -> exporter.restore(ACCOUNT, new ByteArrayInputStream("tgz".getBytes())));
    }

    private ZimbraRestMailboxExporter exporter(Duration header, Duration inactivity, Duration restore) {
        MailboxTransferSettings settings = new MailboxTransferSettings(
                header, inactivity, restore, false, false, null, 0, Duration.ZERO, Duration.ofHours(1));
        return new ZimbraRestMailboxExporter(server.baseUrl(), "zimbra", "secret", null, false, settings);
    }
}
