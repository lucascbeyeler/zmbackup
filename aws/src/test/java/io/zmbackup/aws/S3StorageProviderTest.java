package io.zmbackup.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class S3StorageProviderTest {

    private static final String BUCKET = "test-bucket";
    private static final String SESSION_ID = "full-20260101120000";
    private static final String ACCOUNT = "alice.smith";

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
    void openWriteOfSmallContentUsesPlainPutObject() throws IOException {
        wireMockServer.stubFor(put(urlEqualTo("/" + BUCKET + "/sessions/" + SESSION_ID + "/" + ACCOUNT + ".tgz"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"")));
        S3StorageProvider provider = provider();

        try (OutputStream out = provider.openWrite(SESSION_ID, ACCOUNT, "tgz")) {
            out.write("small content".getBytes(StandardCharsets.UTF_8));
        }

        wireMockServer.verify(putRequestedFor(
                urlEqualTo("/" + BUCKET + "/sessions/" + SESSION_ID + "/" + ACCOUNT + ".tgz")));
    }

    @Test
    void openWriteOfLargeContentUsesMultipartUpload() throws IOException {
        String key = "sessions/" + SESSION_ID + "/" + ACCOUNT + ".tgz";
        wireMockServer.stubFor(post(urlEqualTo("/" + BUCKET + "/" + key + "?uploads"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<InitiateMultipartUploadResult><Bucket>" + BUCKET + "</Bucket><Key>" + key
                                + "</Key><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>")));
        wireMockServer.stubFor(put(urlPathEqualTo("/" + BUCKET + "/" + key))
                .withQueryParam("uploadId", com.github.tomakehurst.wiremock.client.WireMock.equalTo("upload-1"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"")));
        wireMockServer.stubFor(post(urlPathEqualTo("/" + BUCKET + "/" + key))
                .withQueryParam("uploadId", com.github.tomakehurst.wiremock.client.WireMock.equalTo("upload-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<CompleteMultipartUploadResult><Bucket>" + BUCKET + "</Bucket><Key>" + key
                                + "</Key><ETag>\"cccccccccccccccccccccccccccccccc\"</ETag></CompleteMultipartUploadResult>")));
        S3StorageProvider provider = provider();
        byte[] content = randomBytes(S3MultipartOutputStream.PART_SIZE_BYTES + 1024);

        try (OutputStream out = provider.openWrite(SESSION_ID, ACCOUNT, "tgz")) {
            out.write(content);
        }

        wireMockServer.verify(postRequestedFor(urlEqualTo("/" + BUCKET + "/" + key + "?uploads")));
        wireMockServer.verify(2, putRequestedFor(urlPathEqualTo("/" + BUCKET + "/" + key)));
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/" + BUCKET + "/" + key)));
    }

    @Test
    void multipartUploadIsAbortedWhenAPartUploadFails() {
        String key = "sessions/" + SESSION_ID + "/" + ACCOUNT + ".tgz";
        wireMockServer.stubFor(post(urlEqualTo("/" + BUCKET + "/" + key + "?uploads"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<InitiateMultipartUploadResult><Bucket>" + BUCKET + "</Bucket><Key>" + key
                                + "</Key><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>")));
        wireMockServer.stubFor(put(urlPathEqualTo("/" + BUCKET + "/" + key))
                .willReturn(aResponse().withStatus(500)));
        wireMockServer.stubFor(delete(urlPathEqualTo("/" + BUCKET + "/" + key))
                .willReturn(aResponse().withStatus(204)));
        S3StorageProvider provider = provider();
        byte[] content = randomBytes(S3MultipartOutputStream.PART_SIZE_BYTES + 1024);

        assertThrows(IOException.class, () -> {
            try (OutputStream out = provider.openWrite(SESSION_ID, ACCOUNT, "tgz")) {
                out.write(content);
            }
        });

        wireMockServer.verify(deleteRequestedFor(urlPathEqualTo("/" + BUCKET + "/" + key)));
    }

    @Test
    void openReadReturnsObjectContent() throws IOException {
        String key = "sessions/" + SESSION_ID + "/" + ACCOUNT + ".ldiff";
        wireMockServer.stubFor(get(urlPathEqualTo("/" + BUCKET + "/" + key))
                .willReturn(aResponse().withStatus(200).withBody("ldiff-content")));
        S3StorageProvider provider = provider();

        try (InputStream in = provider.openRead(SESSION_ID, ACCOUNT, "ldiff")) {
            assertArrayEquals("ldiff-content".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
    }

    @Test
    void existsReturnsTrueOn200AndFalseOn404() {
        String key = "sessions/" + SESSION_ID + "/" + ACCOUNT + ".tgz";
        wireMockServer.stubFor(head(urlPathEqualTo("/" + BUCKET + "/" + key))
                .willReturn(aResponse().withStatus(200)));
        S3StorageProvider provider = provider();

        assertTrue(provider.exists(SESSION_ID, ACCOUNT, "tgz"));

        wireMockServer.resetAll();
        wireMockServer.stubFor(head(urlPathEqualTo("/" + BUCKET + "/" + key))
                .willReturn(aResponse().withStatus(404)));

        assertFalse(provider.exists(SESSION_ID, ACCOUNT, "tgz"));
    }

    @Test
    void sizeOfAccountSumsOnlyMatchingObjectsAndIgnoresExtendedAccountNames() throws IOException {
        String prefix = "sessions/" + SESSION_ID + "/";
        wireMockServer.stubFor(get(urlPathEqualTo("/" + BUCKET))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(listBucketResult(
                                false,
                                null,
                                entry(prefix + ACCOUNT + ".ldiff", 500),
                                entry(prefix + ACCOUNT + ".tgz", 1524),
                                entry(prefix + ACCOUNT + ".au.tgz", 999999)))));
        S3StorageProvider provider = provider();

        String size = provider.sizeOfAccount(SESSION_ID, ACCOUNT);

        assertEquals("2K", size);
    }

    @Test
    void sizeOfSessionSumsEveryObjectAcrossPages() throws IOException {
        String prefix = "sessions/" + SESSION_ID + "/";
        wireMockServer.stubFor(get(urlPathEqualTo("/" + BUCKET))
                .withQueryParam("continuation-token", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(listBucketResult(true, "next-token", entry(prefix + "a@example.com.tgz", 200)))));
        wireMockServer.stubFor(get(urlPathEqualTo("/" + BUCKET))
                .withQueryParam("continuation-token", com.github.tomakehurst.wiremock.client.WireMock.equalTo("next-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(listBucketResult(false, null, entry(prefix + "b@example.com.tgz", 312)))));
        S3StorageProvider provider = provider();

        String size = provider.sizeOfSession(SESSION_ID);

        assertEquals("512B", size);
    }

    @Test
    void deleteSessionBatchDeletesEveryListedObject() throws IOException {
        String prefix = "sessions/" + SESSION_ID + "/";
        wireMockServer.stubFor(get(urlPathEqualTo("/" + BUCKET))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(listBucketResult(false, null, entry(prefix + ACCOUNT + ".tgz", 10)))));
        wireMockServer.stubFor(post(urlPathEqualTo("/" + BUCKET))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<DeleteResult></DeleteResult>")));
        S3StorageProvider provider = provider();

        provider.deleteSession(SESSION_ID);

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/" + BUCKET)));
    }

    @Test
    void deleteEmptyFilesRemovesOnlyZeroByteObjects() throws IOException {
        wireMockServer.stubFor(get(urlPathEqualTo("/" + BUCKET))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(listBucketResult(
                                false, null, entry("sessions/x/a.tgz", 0), entry("sessions/x/b.tgz", 10)))));
        wireMockServer.stubFor(post(urlPathEqualTo("/" + BUCKET))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<DeleteResult></DeleteResult>")));
        S3StorageProvider provider = provider();

        int removed = provider.deleteEmptyFiles();

        assertEquals(1, removed);
    }

    private S3StorageProvider provider() {
        return new S3StorageProvider(BUCKET, "us-east-1", "sessions/", URI.create(wireMockServer.baseUrl()));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static String entry(String key, long size) {
        return "<Contents><Key>" + key + "</Key><Size>" + size + "</Size></Contents>";
    }

    private static String listBucketResult(boolean truncated, String nextToken, String... entries) {
        StringBuilder body = new StringBuilder("<ListBucketResult><Name>bucket</Name>");
        for (String entry : entries) {
            body.append(entry);
        }
        body.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (nextToken != null) {
            body.append("<NextContinuationToken>").append(nextToken).append("</NextContinuationToken>");
        }
        body.append("</ListBucketResult>");
        return body.toString();
    }
}
