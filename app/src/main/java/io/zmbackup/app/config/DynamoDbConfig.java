package io.zmbackup.app.config;

import java.net.URI;
import java.util.Objects;

public record DynamoDbConfig(
        String region, String sessionTable, String accountTable, String lockTable, URI endpointOverride) {

    public static final String DEFAULT_SESSION_TABLE = "zmbackup_session";
    public static final String DEFAULT_ACCOUNT_TABLE = "zmbackup_account";
    public static final String DEFAULT_LOCK_TABLE = "zmbackup_lock";

    public DynamoDbConfig {
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(sessionTable, "sessionTable must not be null");
        Objects.requireNonNull(accountTable, "accountTable must not be null");
        Objects.requireNonNull(lockTable, "lockTable must not be null");
    }
}
