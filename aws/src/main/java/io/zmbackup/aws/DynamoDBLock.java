package io.zmbackup.aws;

import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private static final String LOCK_ID_PREFIX = "zmbackup-";
    private static final Logger LOG = Logger.getLogger(DynamoDBLock.class.getName());
    private static final int HEARTBEAT_INTERVAL_DIVISOR = 3;
    private static final Duration HEARTBEAT_SHUTDOWN_WAIT = Duration.ofSeconds(5);

    private final DynamoDbClient client;
    private final String lockTable;
    private final String lockToken;
    private final Duration leaseDuration;
    private final List<String> heldLockIds = new ArrayList<>();
    private final ScheduledExecutorService heartbeat;

    private DynamoDBLock(DynamoDbClient client, String lockTable, String lockToken, Duration leaseDuration) {
        this.client = client;
        this.lockTable = lockTable;
        this.lockToken = lockToken;
        this.leaseDuration = leaseDuration;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(DynamoDBLock::newHeartbeatThread);
    }

    private static Thread newHeartbeatThread(Runnable task) {
        Thread thread = new Thread(task, "zmbackup-dynamodb-lock-heartbeat");
        thread.setDaemon(true);
        return thread;
    }

    public static DynamoDBLock acquire(
            String region, String lockTable, URI endpointOverride, Duration leaseDuration, List<String> resources)
            throws IOException {
        return acquire(
                region,
                lockTable,
                endpointOverride,
                leaseDuration,
                leaseDuration.dividedBy(HEARTBEAT_INTERVAL_DIVISOR),
                resources);
    }

    static DynamoDBLock acquire(
            String region,
            String lockTable,
            URI endpointOverride,
            Duration leaseDuration,
            Duration heartbeatInterval,
            List<String> resources)
            throws IOException {
        List<String> lockIds = resources.stream().distinct().sorted().map(r -> LOCK_ID_PREFIX + r).toList();
        if (lockIds.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }
        DynamoDbClient client = builder.build();
        DynamoDBLock lock = new DynamoDBLock(client, lockTable, UUID.randomUUID().toString(), leaseDuration);
        String current = lockIds.get(0);
        try {
            for (String lockId : lockIds) {
                current = lockId;
                lock.putItem(lockId, "attribute_not_exists(lockId) OR expiresAt < :now",
                        Map.of(":now", AttributeValue.fromS(Instant.now().toString())));
                lock.heldLockIds.add(lockId);
            }
        } catch (ConditionalCheckFailedException e) {
            String holder = currentHolder(client, lockTable, current);
            lock.releaseQuietly();
            throw new LockContentionException("Another zmbackup process (" + holder + ") is already running against "
                    + lockTable + " (a conflicting \"" + current.substring(LOCK_ID_PREFIX.length())
                    + "\" operation)");
        } catch (SdkException e) {
            lock.releaseQuietly();
            throw new IOException(e);
        }
        lock.startHeartbeat(heartbeatInterval);
        return lock;
    }

    private void putItem(String lockId, String condition, Map<String, AttributeValue> values) {
        Instant expiresAt = Instant.now().plus(leaseDuration);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("lockId", AttributeValue.fromS(lockId));
        item.put("holder", AttributeValue.fromS(holderDescription()));
        item.put("lockToken", AttributeValue.fromS(lockToken));
        item.put("expiresAt", AttributeValue.fromS(expiresAt.toString()));
        item.put("expiresAtEpochSeconds", AttributeValue.fromN(Long.toString(expiresAt.getEpochSecond())));
        client.putItem(PutItemRequest.builder()
                .tableName(lockTable)
                .item(item)
                .conditionExpression(condition)
                .expressionAttributeValues(values)
                .build());
    }

    private void releaseQuietly() {
        try {
            close();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to release partially acquired locks on " + lockTable, e);
        }
    }

    private void startHeartbeat(Duration heartbeatInterval) {
        long intervalMillis = heartbeatInterval.toMillis();
        heartbeat.scheduleAtFixedRate(this::renewLease, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void renewLease() {
        for (String lockId : List.copyOf(heldLockIds)) {
            try {
                putItem(lockId, "lockToken = :token", Map.of(":token", AttributeValue.fromS(lockToken)));
            } catch (ConditionalCheckFailedException e) {
                LOG.log(Level.WARNING, "Lease " + lockId + " on " + lockTable
                        + " was already reclaimed by another process; stopping heartbeat renewal for this lock");
                heartbeat.shutdown();
                return;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to renew lease " + lockId + " on " + lockTable
                        + "; will retry on the next heartbeat", e);
            }
        }
    }

    private static String currentHolder(DynamoDbClient client, String lockTable, String lockId) {
        try {
            GetItemResponse response = client.getItem(GetItemRequest.builder()
                    .tableName(lockTable)
                    .key(Map.of("lockId", AttributeValue.fromS(lockId)))
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
        stopHeartbeat();
        IOException failure = null;
        try {
            for (int i = heldLockIds.size() - 1; i >= 0; i--) {
                String lockId = heldLockIds.get(i);
                try {
                    client.deleteItem(DeleteItemRequest.builder()
                            .tableName(lockTable)
                            .key(Map.of("lockId", AttributeValue.fromS(lockId)))
                            .conditionExpression("lockToken = :token")
                            .expressionAttributeValues(Map.of(":token", AttributeValue.fromS(lockToken)))
                            .build());
                } catch (ConditionalCheckFailedException e) {
                    LOG.log(Level.WARNING, "Lease " + lockId + " on " + lockTable
                            + " was already reclaimed by another process; not deleting its lock item");
                } catch (SdkException e) {
                    if (failure == null) {
                        failure = new IOException(e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            heldLockIds.clear();
        } finally {
            client.close();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void stopHeartbeat() {
        heartbeat.shutdownNow();
        try {
            heartbeat.awaitTermination(HEARTBEAT_SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
