package io.zmbackup.aws;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

public final class DynamoDBMetadataStore implements MetadataStore {

    static final String SESSION_ID_INDEX = "sessionId-index";

    private static final int BATCH_GET_SIZE = 100;

    private final DynamoDbClient client;
    private final String sessionTable;
    private final String accountTable;

    public DynamoDBMetadataStore(String region, String sessionTable, String accountTable, URI endpointOverride) {
        this.sessionTable = Objects.requireNonNull(sessionTable, "sessionTable must not be null");
        this.accountTable = Objects.requireNonNull(accountTable, "accountTable must not be null");
        DynamoDbClientBuilder builder =
                DynamoDbClient.builder().region(Region.of(Objects.requireNonNull(region, "region must not be null")));
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }
        this.client = builder.build();
    }

    @Override
    public void save(BackupSession session) throws IOException {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("sessionId", AttributeValue.fromS(session.sessionId()));
        item.put("type", AttributeValue.fromS(session.type().sessionPrefix()));
        item.put("status", AttributeValue.fromS(session.status().dbValue()));
        item.put("initialDate", AttributeValue.fromS(toDb(session.startedAt())));
        if (session.completedAt() != null) {
            item.put("conclusionDate", AttributeValue.fromS(toDb(session.completedAt())));
        }
        if (session.size() != null) {
            item.put("size", AttributeValue.fromS(session.size()));
        }
        try {
            client.putItem(PutItemRequest.builder().tableName(sessionTable).item(item).build());
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Optional<BackupSession> findSession(String sessionId) throws IOException {
        try {
            GetItemResponse response = client.getItem(GetItemRequest.builder()
                    .tableName(sessionTable)
                    .key(Map.of("sessionId", AttributeValue.fromS(sessionId)))
                    .build());
            return response.hasItem() ? Optional.of(mapSession(response.item())) : Optional.empty();
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<BackupSession> listSessions() throws IOException {
        try {
            List<BackupSession> sessions = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest.Builder requestBuilder = ScanRequest.builder().tableName(sessionTable);
                if (lastKey != null) {
                    requestBuilder.exclusiveStartKey(lastKey);
                }
                ScanResponse response = client.scan(requestBuilder.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    sessions.add(mapSession(item));
                }
                lastKey = response.lastEvaluatedKey();
            } while (lastKey != null && !lastKey.isEmpty());
            return sessions;
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<BackupSession> findSessionsCompletedBefore(Instant cutoff) throws IOException {
        try {
            List<BackupSession> sessions = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest.Builder requestBuilder = ScanRequest.builder()
                        .tableName(sessionTable)
                        .filterExpression("attribute_exists(conclusionDate) AND conclusionDate < :cutoff")
                        .expressionAttributeValues(Map.of(":cutoff", AttributeValue.fromS(toDb(cutoff))));
                if (lastKey != null) {
                    requestBuilder.exclusiveStartKey(lastKey);
                }
                ScanResponse response = client.scan(requestBuilder.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    sessions.add(mapSession(item));
                }
                lastKey = response.lastEvaluatedKey();
            } while (lastKey != null && !lastKey.isEmpty());
            return sessions;
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void deleteSession(String sessionId) throws IOException {
        for (BackupAccountRecord record : findAccountsForSession(sessionId)) {
            deleteAccountItem(record.email(), sessionId);
        }
        deleteSessionItem(sessionId);
    }

    @Override
    public int truncate() throws IOException {
        List<BackupSession> sessions = listSessions();
        for (BackupSession session : sessions) {
            deleteSession(session.sessionId());
        }
        return sessions.size();
    }

    @Override
    public void recordAccountBackup(BackupAccountRecord record) throws IOException {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("email", AttributeValue.fromS(record.email()));
        item.put("sessionId", AttributeValue.fromS(record.sessionId()));
        item.put("accountSize", AttributeValue.fromS(record.size()));
        item.put("initialDate", AttributeValue.fromS(toDb(record.startedAt())));
        if (record.completedAt() != null) {
            item.put("conclusionDate", AttributeValue.fromS(toDb(record.completedAt())));
        }
        try {
            client.putItem(PutItemRequest.builder().tableName(accountTable).item(item).build());
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<BackupAccountRecord> findAccountsForSession(String sessionId) throws IOException {
        try {
            List<BackupAccountRecord> records = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest.Builder requestBuilder = QueryRequest.builder()
                        .tableName(accountTable)
                        .indexName(SESSION_ID_INDEX)
                        .keyConditionExpression("sessionId = :sessionId")
                        .expressionAttributeValues(Map.of(":sessionId", AttributeValue.fromS(sessionId)));
                if (lastKey != null) {
                    requestBuilder.exclusiveStartKey(lastKey);
                }
                QueryResponse response = client.query(requestBuilder.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    records.add(mapAccount(item));
                }
                lastKey = response.lastEvaluatedKey();
            } while (lastKey != null && !lastKey.isEmpty());
            return records;
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Optional<Instant> lastSuccessfulBackupTime(String email) throws IOException {
        List<BackupAccountRecord> candidates = queryAccountsByEmail(email);
        Set<String> mailboxPrefixes = new HashSet<>(BackupType.mailboxSessionPrefixes());
        List<BackupAccountRecord> mailboxCandidates = new ArrayList<>();
        for (BackupAccountRecord candidate : candidates) {
            if (mailboxPrefixes.contains(sessionPrefixOf(candidate.sessionId()))) {
                mailboxCandidates.add(candidate);
            }
        }
        if (mailboxCandidates.isEmpty()) {
            return Optional.empty();
        }
        Set<String> sessionIds = new HashSet<>();
        for (BackupAccountRecord candidate : mailboxCandidates) {
            sessionIds.add(candidate.sessionId());
        }
        Set<String> nonInProgress = nonInProgressSessionIds(sessionIds);
        Instant latest = null;
        for (BackupAccountRecord candidate : mailboxCandidates) {
            if (!nonInProgress.contains(candidate.sessionId()) || candidate.completedAt() == null) {
                continue;
            }
            if (latest == null || candidate.completedAt().isAfter(latest)) {
                latest = candidate.completedAt();
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public boolean backedUpSince(String identifier, Instant since) throws IOException {
        for (BackupAccountRecord record : queryAccountsByEmail(identifier)) {
            if (record.completedAt() != null && record.completedAt().isAfter(since)) {
                return true;
            }
        }
        return false;
    }

    private static String sessionPrefixOf(String sessionId) {
        int dash = sessionId.indexOf('-');
        return dash < 0 ? sessionId : sessionId.substring(0, dash);
    }

    private List<BackupAccountRecord> queryAccountsByEmail(String email) throws IOException {
        try {
            List<BackupAccountRecord> records = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest.Builder requestBuilder = QueryRequest.builder()
                        .tableName(accountTable)
                        .keyConditionExpression("email = :email")
                        .expressionAttributeValues(Map.of(":email", AttributeValue.fromS(email)));
                if (lastKey != null) {
                    requestBuilder.exclusiveStartKey(lastKey);
                }
                QueryResponse response = client.query(requestBuilder.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    records.add(mapAccount(item));
                }
                lastKey = response.lastEvaluatedKey();
            } while (lastKey != null && !lastKey.isEmpty());
            return records;
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    private Set<String> nonInProgressSessionIds(Set<String> sessionIds) throws IOException {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        List<String> ids = new ArrayList<>(sessionIds);
        for (int start = 0; start < ids.size(); start += BATCH_GET_SIZE) {
            result.addAll(batchGetNonInProgress(ids.subList(start, Math.min(start + BATCH_GET_SIZE, ids.size()))));
        }
        return result;
    }

    private Set<String> batchGetNonInProgress(List<String> sessionIds) throws IOException {
        try {
            List<Map<String, AttributeValue>> keys = new ArrayList<>();
            for (String sessionId : sessionIds) {
                keys.add(Map.of("sessionId", AttributeValue.fromS(sessionId)));
            }
            Set<String> result = new HashSet<>();
            Map<String, KeysAndAttributes> requestItems =
                    new HashMap<>(Map.of(sessionTable, KeysAndAttributes.builder().keys(keys).build()));
            while (!requestItems.isEmpty()) {
                BatchGetItemResponse response =
                        client.batchGetItem(BatchGetItemRequest.builder().requestItems(requestItems).build());
                for (Map<String, AttributeValue> item :
                        response.responses().getOrDefault(sessionTable, List.of())) {
                    if (!SessionStatus.IN_PROGRESS.dbValue().equals(item.get("status").s())) {
                        result.add(item.get("sessionId").s());
                    }
                }
                requestItems = response.unprocessedKeys();
            }
            return result;
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    private void deleteAccountItem(String email, String sessionId) throws IOException {
        try {
            client.deleteItem(DeleteItemRequest.builder()
                    .tableName(accountTable)
                    .key(Map.of("email", AttributeValue.fromS(email), "sessionId", AttributeValue.fromS(sessionId)))
                    .build());
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    private void deleteSessionItem(String sessionId) throws IOException {
        try {
            client.deleteItem(DeleteItemRequest.builder()
                    .tableName(sessionTable)
                    .key(Map.of("sessionId", AttributeValue.fromS(sessionId)))
                    .build());
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    private static BackupSession mapSession(Map<String, AttributeValue> item) {
        return new BackupSession(
                item.get("sessionId").s(),
                BackupType.fromSessionPrefix(item.get("type").s()),
                SessionStatus.fromDbValue(item.get("status").s()),
                fromDb(item.get("initialDate").s()),
                item.containsKey("conclusionDate") ? fromDb(item.get("conclusionDate").s()) : null,
                item.containsKey("size") ? item.get("size").s() : null);
    }

    private static BackupAccountRecord mapAccount(Map<String, AttributeValue> item) {
        return new BackupAccountRecord(
                null,
                item.get("sessionId").s(),
                item.get("email").s(),
                item.get("accountSize").s(),
                fromDb(item.get("initialDate").s()),
                item.containsKey("conclusionDate") ? fromDb(item.get("conclusionDate").s()) : null);
    }

    private static String toDb(Instant instant) {
        return instant.toString();
    }

    private static Instant fromDb(String value) {
        return Instant.parse(value);
    }
}
