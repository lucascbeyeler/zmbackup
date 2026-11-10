package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zmbackup.core.domain.Compression;
import io.zmbackup.core.domain.CompressionSettings;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

class CompressionOptionsTest {

    private static final CompressionSettings CONFIGURED = new CompressionSettings(Compression.ZSTD, 19, 4);

    @Command(name = "probe")
    static final class Probe {
        @Mixin
        CompressionOptions options = new CompressionOptions();
    }

    private static CompressionSettings resolve(String... args) {
        Probe probe = new Probe();
        new CommandLine(probe).parseArgs(args);
        return probe.options.resolve(CONFIGURED);
    }

    @Test
    void withoutOptionsTheConfiguredSettingsApply() {
        assertSame(CONFIGURED, resolve());
    }

    @Test
    void aLevelAloneKeepsTheConfiguredAlgorithmAndThreads() {
        assertEquals(new CompressionSettings(Compression.ZSTD, 3, 4), resolve("--compression-level", "3"));
        assertEquals(new CompressionSettings(Compression.ZSTD, 19, 0), resolve("--compression-threads", "0"));
    }

    @Test
    void theSameAlgorithmKeepsTheConfiguredLevelAndThreads() {
        assertEquals(CONFIGURED, resolve("--compression", "zstandard"));
    }

    @Test
    void aDifferentAlgorithmStartsFromItsOwnDefaults() {
        assertEquals(CompressionSettings.of(Compression.BZIP2), resolve("--compression", "bzip"));
        assertEquals(new CompressionSettings(Compression.XZ, 9, 0),
                resolve("--compression", "xz", "--compression-level", "9"));
        assertEquals(CompressionSettings.of(Compression.NONE), resolve("--compression", "none"));
    }

    @Test
    void invalidCombinationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> resolve("--compression", "rar"));
        assertThrows(IllegalArgumentException.class, () -> resolve("--compression", "lz4", "--compression-level", "1"));
        assertThrows(IllegalArgumentException.class, () -> resolve("--compression", "gzip", "--compression-threads", "2"));
    }
}
