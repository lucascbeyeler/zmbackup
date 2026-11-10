package io.zmbackup.core.domain;

import java.util.Objects;

public record CompressionSettings(Compression algorithm, Integer level, int threads) {

    public static final int MAX_THREADS = 256;

    public static final CompressionSettings DEFAULT = new CompressionSettings(Compression.GZIP, null, 0);

    public CompressionSettings {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        if (level != null) {
            if (!algorithm.supportsLevel()) {
                throw new IllegalArgumentException(
                        "compression level is not supported by " + algorithm.configName() + "; leave it unset");
            }
            if (level < algorithm.minLevel() || level > algorithm.maxLevel()) {
                throw new IllegalArgumentException("compression level for " + algorithm.configName()
                        + " must be between " + algorithm.minLevel() + " and " + algorithm.maxLevel()
                        + ", got " + level);
            }
        }
        if (threads < 0 || threads > MAX_THREADS) {
            throw new IllegalArgumentException("compression threads must be between 0 and " + MAX_THREADS);
        }
        if (threads > 0 && !algorithm.supportsThreads()) {
            throw new IllegalArgumentException(
                    "compression threads is only supported by zstd, not " + algorithm.configName());
        }
    }

    public static CompressionSettings of(Compression algorithm) {
        return new CompressionSettings(algorithm, null, 0);
    }

    public int effectiveLevel() {
        return level != null ? level : algorithm.defaultLevel();
    }
}
