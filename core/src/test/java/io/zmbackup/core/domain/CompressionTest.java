package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompressionTest {

    @Test
    void parsesConfigNamesAndAliasesCaseInsensitively() {
        assertSame(Compression.GZIP, Compression.parse("gzip"));
        assertSame(Compression.GZIP, Compression.parse("GZ"));
        assertSame(Compression.BZIP2, Compression.parse("bzip"));
        assertSame(Compression.BZIP2, Compression.parse("bz2"));
        assertSame(Compression.XZ, Compression.parse(" xz "));
        assertSame(Compression.ZSTD, Compression.parse("Z Standard"));
        assertSame(Compression.ZSTD, Compression.parse("zst"));
        assertSame(Compression.LZ4, Compression.parse("lz4"));
        assertSame(Compression.ZIP, Compression.parse("ZIP"));
        assertSame(Compression.NONE, Compression.parse("none"));
        assertSame(Compression.NONE, Compression.parse("off"));
    }

    @Test
    void rejectsAnUnknownNameListingTheValidOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Compression.parse("rar"));
        assertTrue(e.getMessage().contains("rar"));
        assertTrue(e.getMessage().contains("none, gzip, bzip2, xz, zstd, lz4, zip"));
    }

    @Test
    void everyAlgorithmHasADistinctSingleSegmentArchiveSuffix() {
        List<String> suffixes = Compression.archiveSuffixes();
        assertEquals(Compression.values().length, new HashSet<>(suffixes).size());
        for (String suffix : suffixes) {
            assertFalse(suffix.contains("."), suffix);
        }
        assertEquals("tgz", Compression.GZIP.archiveSuffix());
        assertEquals("tar", Compression.NONE.archiveSuffix());
    }

    @Test
    void exposesLevelRangesOnlyForAlgorithmsThatHaveThem() {
        assertEquals(1, Compression.ZSTD.minLevel());
        assertEquals(22, Compression.ZSTD.maxLevel());
        assertEquals(3, Compression.ZSTD.defaultLevel());
        assertFalse(Compression.LZ4.supportsLevel());
        assertThrows(UnsupportedOperationException.class, Compression.NONE::defaultLevel);
        assertThrows(UnsupportedOperationException.class, Compression.LZ4::minLevel);
        assertThrows(UnsupportedOperationException.class, Compression.LZ4::maxLevel);
        assertTrue(Compression.ZSTD.supportsThreads());
        assertFalse(Compression.XZ.supportsThreads());
    }

    @Test
    void settingsDefaultToGzipAtTheAlgorithmsOwnLevel() {
        assertSame(Compression.GZIP, CompressionSettings.DEFAULT.algorithm());
        assertNull(CompressionSettings.DEFAULT.level());
        assertEquals(6, CompressionSettings.DEFAULT.effectiveLevel());
        assertEquals(19, new CompressionSettings(Compression.ZSTD, 19, 4).effectiveLevel());
        assertEquals(0, CompressionSettings.of(Compression.XZ).threads());
    }

    @Test
    void settingsRejectInvalidCombinations() {
        assertThrows(NullPointerException.class, () -> new CompressionSettings(null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new CompressionSettings(Compression.GZIP, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CompressionSettings(Compression.GZIP, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new CompressionSettings(Compression.LZ4, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CompressionSettings(Compression.NONE, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CompressionSettings(Compression.XZ, null, 2));
        assertThrows(IllegalArgumentException.class, () -> new CompressionSettings(Compression.ZSTD, null, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionSettings(Compression.ZSTD, null, CompressionSettings.MAX_THREADS + 1));
        assertEquals(0, new CompressionSettings(Compression.XZ, 0, 0).effectiveLevel());
    }
}
