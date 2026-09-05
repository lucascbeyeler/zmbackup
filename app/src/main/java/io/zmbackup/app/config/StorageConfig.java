package io.zmbackup.app.config;

import java.util.Objects;

public record StorageConfig(StorageBackend backend, S3Config s3) {

    public StorageConfig {
        Objects.requireNonNull(backend, "backend must not be null");
        if (backend == StorageBackend.S3) {
            Objects.requireNonNull(s3, "storage.s3 must be set when storage.backend is s3");
        }
    }
}
