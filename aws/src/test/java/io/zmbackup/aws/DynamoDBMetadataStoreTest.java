package io.zmbackup.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamoDBMetadataStoreTest {

    private static final String SESSION_TABLE = "zmbackup_session";
    private static final String ACCOUNT_TABLE = "zmbackup_account";

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
    void saveSendsPutItemOmittingAbsentAttributes() throws IOException {
        stubTarget("PutItem", "{}");
        BackupSession session = new BackupSession(
                "full-20260101120000", BackupType.FULL, SessionStatus.IN_PROGRESS, Instant.parse(
                        "2026-01-01T12:00:00Z"), null, null);

        store().save(session);

        wireMockServer.verify(postRequestedFor(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.PutItem"))
                .withRequestBody(containing("\"full\""))
                .withRequestBody(containing("\"IN PROGRESS\""))
                .withRequestBody(new com.github.tomakehurst.wiremock.matching.NegativeRegexPattern(
                        ".*conclusionDate.*")));
    }

    @Test
    void findSessionReturnsEmptyWhenNoItem() throws IOException {
        stubTarget("GetItem", "{}");

        Optional<BackupSession> result = store().findSession("full-20260101120000");

        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionMapsItemBackToDomain() throws IOException {
        stubTarget(
                "GetItem",
                "{\"Item\":{"
                        + "\"sessionId\":{\"S\":\"full-20260101120000\"},"
                        + "\"type\":{\"S\":\"full\"},"
                        + "\"status\":{\"S\":\"FINISHED\"},"
                        + "\"initialDate\":{\"S\":\"2026-01-01T12:00:00Z\"},"
                        + "\"conclusionDate\":{\"S\":\"2026-01-01T13:00:00Z\"},"
                        + "\"size\":{\"S\":\"1.0M\"}"
                        + "}}");

        Optional<BackupSession> result = store().findSession("full-20260101120000");

        assertTrue(result.isPresent());
        assertEquals(BackupType.FULL, result.get().type());
        assertEquals(SessionStatus.FINISHED, result.get().status());
        assertEquals("1.0M", result.get().size());
    }

    @Test
    void listSessionsFollowsPagination() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Scan"))
                .withRequestBody(new com.github.tomakehurst.wiremock.matching.NegativeRegexPattern(
                        ".*ExclusiveStartKey.*"))
                .willReturn(jsonResponse(
                        "{\"Items\":[" + sessionItem("full-20260101120000") + "],"
                                + "\"LastEvaluatedKey\":{\"sessionId\":{\"S\":\"full-20260101120000\"}}}")));
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Scan"))
                .withRequestBody(containing("ExclusiveStartKey"))
                .willReturn(jsonResponse("{\"Items\":[" + sessionItem("inc-20260102120000") + "]}")));

        List<BackupSession> sessions = store().listSessions();

        assertEquals(2, sessions.size());
    }

    @Test
    void findSessionsCompletedBeforeSendsFilterExpression() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Scan"))
                .willReturn(jsonResponse("{\"Items\":[" + sessionItem("full-20260101120000") + "]}")));

        List<BackupSession> sessions = store().findSessionsCompletedBefore(Instant.parse("2026-06-01T00:00:00Z"));

        assertEquals(1, sessions.size());
        wireMockServer.verify(postRequestedFor(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Scan"))
                .withRequestBody(containing("attribute_exists(conclusionDate)")));
    }

    @Test
    void deleteSessionDeletesEachAccountThenTheSessionItself() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .willReturn(jsonResponse("{\"Items\":[" + accountItem("full-20260101120000", "alice@example.com")
                        + "]}")));
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.DeleteItem"))
                .willReturn(jsonResponse("{}")));

        store().deleteSession("full-20260101120000");

        wireMockServer.verify(2, postRequestedFor(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.DeleteItem")));
    }

    @Test
    void truncateDeletesEverySessionAndReturnsPreDeleteCount() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Scan"))
                .willReturn(jsonResponse("{\"Items\":[" + sessionItem("full-20260101120000") + ","
                        + sessionItem("inc-20260102120000") + "]}")));
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .willReturn(jsonResponse("{\"Items\":[]}")));
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.DeleteItem"))
                .willReturn(jsonResponse("{}")));

        int removed = store().truncate();

        assertEquals(2, removed);
    }

    @Test
    void recordAccountBackupSendsPutItemOnAccountTable() throws IOException {
        stubTarget("PutItem", "{}");
        BackupAccountRecord record = new BackupAccountRecord(
                null,
                "full-20260101120000",
                "alice@example.com",
                "1K",
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T12:05:00Z"));

        store().recordAccountBackup(record);

        wireMockServer.verify(postRequestedFor(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.PutItem"))
                .withRequestBody(containing("\"" + ACCOUNT_TABLE + "\""))
                .withRequestBody(containing("alice@example.com")));
    }

    @Test
    void findAccountsForSessionQueriesTheSessionIdIndex() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .willReturn(jsonResponse(
                        "{\"Items\":[" + accountItem("full-20260101120000", "alice@example.com") + "]}")));

        List<BackupAccountRecord> records = store().findAccountsForSession("full-20260101120000");

        assertEquals(1, records.size());
        assertEquals("alice@example.com", records.get(0).email());
        wireMockServer.verify(postRequestedFor(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .withRequestBody(containing(DynamoDBMetadataStore.SESSION_ID_INDEX)));
    }

    @Test
    void lastSuccessfulBackupTimeIgnoresInProgressAndNonMailboxSessions() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .willReturn(jsonResponse("{\"Items\":["
                        + accountItem("full-20260101120000", "alice@example.com", "2026-01-01T12:00:00Z")
                        + ","
                        + accountItem("mbox-20260201120000", "alice@example.com", "2026-02-01T12:00:00Z")
                        + ","
                        + accountItem("ldap-20260301120000", "alice@example.com", "2026-03-01T12:00:00Z")
                        + "]}")));
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.BatchGetItem"))
                .willReturn(jsonResponse("{\"Responses\":{\"" + SESSION_TABLE + "\":["
                        + "{\"sessionId\":{\"S\":\"full-20260101120000\"},\"status\":{\"S\":\"FINISHED\"}},"
                        + "{\"sessionId\":{\"S\":\"mbox-20260201120000\"},\"status\":{\"S\":\"IN PROGRESS\"}}"
                        + "]}}")));

        Optional<Instant> result = store().lastSuccessfulBackupTime("alice@example.com");

        assertTrue(result.isPresent());
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), result.get());
    }

    @Test
    void backedUpSinceReturnsTrueOnlyWhenARecordCompletedAfterTheCutoff() throws IOException {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.Query"))
                .willReturn(jsonResponse("{\"Items\":["
                        + accountItem("full-20260101120000", "alice@example.com", "2026-01-01T12:00:00Z")
                        + "]}")));

        assertTrue(store().backedUpSince("alice@example.com", Instant.parse("2025-12-01T00:00:00Z")));
        assertFalse(store().backedUpSince("alice@example.com", Instant.parse("2026-06-01T00:00:00Z")));
    }

    private DynamoDBMetadataStore store() {
        return new DynamoDBMetadataStore(
                "us-east-1", SESSION_TABLE, ACCOUNT_TABLE, URI.create(wireMockServer.baseUrl()));
    }

    private void stubTarget(String operation, String responseBody) {
        wireMockServer.stubFor(post(anyPath())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810." + operation))
                .willReturn(jsonResponse(responseBody)));
    }

    private static com.github.tomakehurst.wiremock.matching.UrlPattern anyPath() {
        return com.github.tomakehurst.wiremock.client.WireMock.anyUrl();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/x-amz-json-1.0").withBody(body);
    }

    private static String sessionItem(String sessionId) {
        return "{\"sessionId\":{\"S\":\"" + sessionId + "\"},"
                + "\"type\":{\"S\":\"" + sessionId.substring(0, sessionId.indexOf('-')) + "\"},"
                + "\"status\":{\"S\":\"FINISHED\"},"
                + "\"initialDate\":{\"S\":\"2026-01-01T12:00:00Z\"},"
                + "\"conclusionDate\":{\"S\":\"2026-01-01T13:00:00Z\"}}";
    }

    private static String accountItem(String sessionId, String email) {
        return accountItem(sessionId, email, "2026-01-01T12:00:00Z");
    }

    private static String accountItem(String sessionId, String email, String completedAt) {
        return "{\"email\":{\"S\":\"" + email + "\"},"
                + "\"sessionId\":{\"S\":\"" + sessionId + "\"},"
                + "\"accountSize\":{\"S\":\"1K\"},"
                + "\"initialDate\":{\"S\":\"2026-01-01T11:00:00Z\"},"
                + "\"conclusionDate\":{\"S\":\"" + completedAt + "\"}}";
    }
}
