package io.zmbackup.app.config;

import java.util.Objects;

public record MetadataConfig(MetadataBackend backend, DynamoDbConfig dynamodb) {

    public MetadataConfig {
        Objects.requireNonNull(backend, "backend must not be null");
        if (backend == MetadataBackend.DYNAMODB) {
            Objects.requireNonNull(dynamodb, "metadata.dynamodb must be set when metadata.backend is dynamodb");
        }
    }
}
