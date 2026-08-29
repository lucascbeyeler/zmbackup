package io.zmbackup.zimbra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ZimbraRestMailboxExporter} against a WireMock server standing in for Zimbra's
 * REST mailbox endpoint.
 */
class ZimbraRestMailboxExporterTest {

    private static final String ADMIN_USER = "zimbra";
    private static final String ADMIN_PASSWORD = "secret";
    private static final String ACCOUNT = "alice@example.com";
    private static final String EXPORT_PATH = "/home/" + ACCOUNT + "/";

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void exportsFixtureTgzOnHttp200() throws Exception {
        byte[] tgzFixture = Files.readAllBytes(fixturePath("mailbox-export.tgz"));
        wireMockServer.stubFor(get(urlEqualTo(EXPORT_PATH + "?fmt=tgz&resolve=skip"))
                .willReturn(aResponse().withStatus(200).withBody(tgzFixture)));
        ZimbraRestMailboxExporter exporter = exporter();

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        boolean wroteContent = exporter.export(ACCOUNT, destination, null);

        assertTrue(wroteContent);
        assertArrayEquals(tgzFixture, destination.toByteArray());
        wireMockServer.verify(getRequestedFor(urlEqualTo(EXPORT_PATH + "?fmt=tgz&resolve=skip"))
                .withHeader("Authorization", equalTo(basicAuthHeader())));
    }

    @Test
    void appendsAfterQueryParameterWhenSinceIsGiven() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo(EXPORT_PATH)).willReturn(aResponse().withStatus(200)));
        ZimbraRestMailboxExporter exporter = exporter();
        Instant since = ZonedDateTime.of(2026, 7, 2, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();

        exporter.export(ACCOUNT, new ByteArrayOutputStream(), since);

        wireMockServer.verify(getRequestedFor(
                urlEqualTo(EXPORT_PATH + "?fmt=tgz&resolve=skip&query=after:%2207/02/2026%22")));
    }

    @Test
    void returnsFalseWithoutWritingOnHttp204() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo(EXPORT_PATH)).willReturn(aResponse().withStatus(204)));
        ZimbraRestMailboxExporter exporter = exporter();

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        boolean wroteContent = exporter.export(ACCOUNT, destination, Instant.now());

        assertFalse(wroteContent);
        assertEquals(0, destination.size());
    }

    @Test
    void throwsIOExceptionOnNonSuccessStatus() {
        wireMockServer.stubFor(get(urlPathEqualTo(EXPORT_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        ZimbraRestMailboxExporter exporter = exporter();

        IOException exception = assertThrows(
                IOException.class,
                () -> exporter.export(ACCOUNT, new ByteArrayOutputStream(), null));
        assertTrue(exception.getMessage().contains("500"));
    }

    @Test
    void wrapsUnreachableServerInIOException() {
        ZimbraRestMailboxExporter exporter =
                new ZimbraRestMailboxExporter("http://127.0.0.1:1", ADMIN_USER, ADMIN_PASSWORD);

        assertThrows(
                IOException.class,
                () -> exporter.export(ACCOUNT, new ByteArrayOutputStream(), null));
    }

    @Test
    void restorePostsContentToRestoreEndpoint() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo(EXPORT_PATH + "?fmt=tgz&resolve=skip"))
                .willReturn(aResponse().withStatus(200)));
        ZimbraRestMailboxExporter exporter = exporter();

        exporter.restore(ACCOUNT, new ByteArrayInputStream("tgz-content".getBytes(StandardCharsets.UTF_8)));

        wireMockServer.verify(postRequestedFor(urlEqualTo(EXPORT_PATH + "?fmt=tgz&resolve=skip"))
                .withHeader("Authorization", equalTo(basicAuthHeader()))
                .withRequestBody(equalTo("tgz-content")));
    }

    @Test
    void restorePostsIntoDestinationAccountForRestoreOnAccount() throws Exception {
        String destination = "bob@example.com";
        wireMockServer.stubFor(post(urlPathEqualTo("/home/" + destination + "/"))
                .willReturn(aResponse().withStatus(200)));
        ZimbraRestMailboxExporter exporter = exporter();

        exporter.restore(destination, new ByteArrayInputStream("tgz-content".getBytes(StandardCharsets.UTF_8)));

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/home/" + destination + "/")));
    }

    @Test
    void restoreThrowsIOExceptionOnNonSuccessStatus() {
        wireMockServer.stubFor(post(urlPathEqualTo(EXPORT_PATH)).willReturn(aResponse().withStatus(500)));
        ZimbraRestMailboxExporter exporter = exporter();

        IOException exception = assertThrows(
                IOException.class,
                () -> exporter.restore(ACCOUNT, new ByteArrayInputStream("tgz-content".getBytes())));
        assertTrue(exception.getMessage().contains("500"));
    }

    @Test
    void restoreWrapsUnreachableServerInIOException() {
        ZimbraRestMailboxExporter exporter =
                new ZimbraRestMailboxExporter("http://127.0.0.1:1", ADMIN_USER, ADMIN_PASSWORD);

        assertThrows(
                IOException.class,
                () -> exporter.restore(ACCOUNT, new ByteArrayInputStream("tgz-content".getBytes())));
    }

    private ZimbraRestMailboxExporter exporter() {
        return new ZimbraRestMailboxExporter(wireMockServer.baseUrl(), ADMIN_USER, ADMIN_PASSWORD);
    }

    private static String basicAuthHeader() {
        return "Basic " + Base64.getEncoder().encodeToString((ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes());
    }

    private static Path fixturePath(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                        ZimbraRestMailboxExporterTest.class.getResource("/fixtures/" + name),
                        "missing test fixture: " + name)
                .toURI());
    }
}
