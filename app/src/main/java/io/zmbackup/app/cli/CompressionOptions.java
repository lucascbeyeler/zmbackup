package io.zmbackup.app.cli;

import io.zmbackup.core.domain.Compression;
import io.zmbackup.core.domain.CompressionSettings;
import picocli.CommandLine.Option;

final class CompressionOptions {

    @Option(
            names = "--compression",
            paramLabel = "<algorithm>",
            description = "Compress the mailbox archives of this run with gzip, bzip2, xz, zstd, lz4, zip or none, "
                    + "instead of backup.compression.algorithm.")
    private String algorithm;

    @Option(
            names = "--compression-level",
            paramLabel = "<level>",
            description = "Compression level for this run (gzip/bzip2 1-9, xz/zip 0-9, zstd 1-22), "
                    + "instead of backup.compression.level.")
    private Integer level;

    @Option(
            names = "--compression-threads",
            paramLabel = "<threads>",
            description = "zstd worker threads for this run (0 = single-threaded), "
                    + "instead of backup.compression.threads.")
    private Integer threads;

    CompressionSettings resolve(CompressionSettings configured) {
        if (algorithm == null && level == null && threads == null) {
            return configured;
        }
        Compression chosen = algorithm == null ? configured.algorithm() : Compression.parse(algorithm);
        boolean sameAlgorithm = chosen == configured.algorithm();
        Integer chosenLevel = level != null ? level : sameAlgorithm ? configured.level() : null;
        int chosenThreads = threads != null ? threads : sameAlgorithm ? configured.threads() : 0;
        return new CompressionSettings(chosen, chosenLevel, chosenThreads);
    }
}
