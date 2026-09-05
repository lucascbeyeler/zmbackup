package io.zmbackup.aws;

import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public final class DynamoDBLock implements RunLock {

    private static final String LOCK_ID = "zmbackup";
    private static final Logger LOG = Logger.getLogger(DynamoDBLock.class.getName());

    private final DynamoDbClient client;
    private final String lockTable;

    private DynamoDBLock(DynamoDbClient client, String lockTable) {
        this.client = client;
        this.lockTable = lockTable;
    }

    public static DynamoDBLock acquire(String region, String lockTable, URI endpointOverride, Duration leaseDuration)
            throws IOException {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }
        DynamoDbClient client = builder.build();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(leaseDuration);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("lockId", AttributeValue.fromS(LOCK_ID));
        item.put("holder", AttributeValue.fromS(holderDescription()));
        item.put("expiresAt", AttributeValue.fromS(expiresAt.toString()));
        item.put("expiresAtEpochSeconds", AttributeValue.fromN(Long.toString(expiresAt.getEpochSecond())));
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(lockTable)
                    .item(item)
                    .conditionExpression("attribute_not_exists(lockId) OR expiresAt < :now")
                    .expressionAttributeValues(Map.of(":now", AttributeValue.fromS(now.toString())))
                    .build());
            return new DynamoDBLock(client, lockTable);
        } catch (ConditionalCheckFailedException e) {
            String holder = currentHolder(client, lockTable);
            client.close();
            throw new LockContentionException(
                    "Another zmbackup process (" + holder + ") is already running against " + lockTable);
        } catch (SdkException e) {
            client.close();
            throw new IOException(e);
        }
    }

    private static String currentHolder(DynamoDbClient client, String lockTable) {
        try {
            GetItemResponse response = client.getItem(GetItemRequest.builder()
                    .tableName(lockTable)
                    .key(Map.of("lockId", AttributeValue.fromS(LOCK_ID)))
                    .build());
            if (response.hasItem() && response.item().containsKey("holder")) {
                return response.item().get("holder").s();
            }
        } catch (SdkException e) {
            LOG.log(Level.WARNING, "Failed to read current lock holder from " + lockTable, e);
        }
        return "unknown";
    }

    private static String holderDescription() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "unknown-host";
        }
        return hostname + ":" + ProcessHandle.current().pid();
    }

    @Override
    public void close() throws IOException {
        try {
            client.deleteItem(DeleteItemRequest.builder()
                    .tableName(lockTable)
                    .key(Map.of("lockId", AttributeValue.fromS(LOCK_ID)))
                    .build());
        } catch (SdkException e) {
            throw new IOException(e);
        } finally {
            client.close();
        }
    }
}
