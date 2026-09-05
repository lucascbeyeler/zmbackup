package io.zmbackup.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.zmbackup.core.port.LockContentionException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamoDBLockTest {

    private static final String LOCK_TABLE = "zmbackup_lock";

    private WireMockServer wireMockServer;

    @BeforeAll
    static void setUpCredentials() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

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
    void acquireSucceedsWhenNoLockItemExists() throws IOException {
        stubTarget("PutItem", 200, "{}");
        stubTarget("DeleteItem", 200, "{}");

        DynamoDBLock lock = DynamoDBLock.acquire("us-east-1", LOCK_TABLE, endpoint(), Duration.ofHours(24));

        lock.close();
    }

    @Test
    void acquireThrowsLockContentionExceptionOnConditionalCheckFailure() {
        wireMockServer.stubFor(post(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.PutItem"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/x-amz-json-1.0")
                        .withHeader("x-amzn-errortype", "ConditionalCheckFailedException")
                        .withBody("{\"__type\":\"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException\","
                                + "\"message\":\"The conditional request failed\"}")));
        stubTarget(
                "GetItem",
                200,
                "{\"Item\":{\"lockId\":{\"S\":\"zmbackup\"},\"holder\":{\"S\":\"other-host:123\"}}}");

        LockContentionException exception = assertThrows(
                LockContentionException.class,
                () -> DynamoDBLock.acquire("us-east-1", LOCK_TABLE, endpoint(), Duration.ofHours(24)));

        assertTrue(exception.getMessage().contains("other-host:123"));
    }

    @Test
    void closeDeletesTheLockItem() throws IOException {
        stubTarget("PutItem", 200, "{}");
        stubTarget("DeleteItem", 200, "{}");
        DynamoDBLock lock = DynamoDBLock.acquire("us-east-1", LOCK_TABLE, endpoint(), Duration.ofHours(24));

        lock.close();

        wireMockServer.verify(postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.DeleteItem"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.ConditionExpression")));
    }

    @Test
    void closeDoesNotFailWhenLockWasAlreadyReclaimedByAnotherHolder() throws IOException {
        stubTarget("PutItem", 200, "{}");
        wireMockServer.stubFor(post(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810.DeleteItem"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/x-amz-json-1.0")
                        .withHeader("x-amzn-errortype", "ConditionalCheckFailedException")
                        .withBody("{\"__type\":\"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException\","
                                + "\"message\":\"The conditional request failed\"}")));
        DynamoDBLock lock = DynamoDBLock.acquire("us-east-1", LOCK_TABLE, endpoint(), Duration.ofHours(24));

        lock.close();
    }

    private URI endpoint() {
        return URI.create(wireMockServer.baseUrl());
    }

    private void stubTarget(String operation, int status, String body) {
        wireMockServer.stubFor(post(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                .withHeader("X-Amz-Target", equalTo("DynamoDB_20120810." + operation))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/x-amz-json-1.0")
                        .withBody(body)));
    }
}
