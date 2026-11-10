package io.zmbackup.core.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public enum Compression {
    NONE("tar", List.of("none", "tar", "off"), null, null, null),
    GZIP("tgz", List.of("gzip", "gz", "tgz"), 1, 9, 6),
    BZIP2("tbz2", List.of("bzip2", "bzip", "bz2", "tbz2"), 1, 9, 9),
    XZ("txz", List.of("xz", "txz"), 0, 9, 6),
    ZSTD("tzst", List.of("zstd", "zst", "zstandard", "tzst"), 1, 22, 3),
    LZ4("tlz4", List.of("lz4", "tlz4"), null, null, null),
    ZIP("zip", List.of("zip"), 0, 9, 6);

    private final String archiveSuffix;
    private final List<String> names;
    private final Integer minLevel;
    private final Integer maxLevel;
    private final Integer defaultLevel;

    Compression(String archiveSuffix, List<String> names, Integer minLevel, Integer maxLevel, Integer defaultLevel) {
        this.archiveSuffix = archiveSuffix;
        this.names = names;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.defaultLevel = defaultLevel;
    }

    public String archiveSuffix() {
        return archiveSuffix;
    }

    public String configName() {
        return names.get(0);
    }

    public boolean supportsLevel() {
        return defaultLevel != null;
    }

    public int minLevel() {
        requireLevelSupport();
        return minLevel;
    }

    public int maxLevel() {
        requireLevelSupport();
        return maxLevel;
    }

    public int defaultLevel() {
        requireLevelSupport();
        return defaultLevel;
    }

    public boolean supportsThreads() {
        return this == ZSTD;
    }

    public static List<String> archiveSuffixes() {
        return Arrays.stream(values()).map(Compression::archiveSuffix).toList();
    }

    public static Compression parse(String name) {
        String normalized = name.strip().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        for (Compression compression : values()) {
            if (compression.names.contains(normalized)) {
                return compression;
            }
        }
        throw new IllegalArgumentException("Unknown compression algorithm '" + name + "'; expected one of: "
                + Arrays.stream(values()).map(Compression::configName).collect(Collectors.joining(", ")));
    }

    private void requireLevelSupport() {
        if (!supportsLevel()) {
            throw new UnsupportedOperationException(configName() + " has no compression level");
        }
    }
}
