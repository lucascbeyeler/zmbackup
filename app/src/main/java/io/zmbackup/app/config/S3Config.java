package io.zmbackup.app.config;

import java.net.URI;
import java.util.Objects;

public record S3Config(String bucket, String region, String prefix, URI endpointOverride) {

    public static final String DEFAULT_PREFIX = "sessions/";

    public S3Config {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(prefix, "prefix must not be null");
    }
}
