package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zmbackup.core.domain.Compression;
import io.zmbackup.core.domain.CompressionSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StreamCompressorTest {

    private final StreamCompressor compressor = new StreamCompressor();

    @ParameterizedTest
    @EnumSource(Compression.class)
    void roundTripsEveryAlgorithmAtItsDefaultLevel(Compression algorithm) throws IOException {
        byte[] content = sample();

        byte[] stored = compress(CompressionSettings.of(algorithm), content);

        assertArrayEquals(content, decompress(stored, algorithm));
        if (algorithm != Compression.NONE) {
            assertTrue(stored.length < content.length, algorithm + " did not shrink compressible content");
        }
    }

    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"GZIP", "BZIP2", "XZ", "ZSTD", "ZIP"})
    void roundTripsAtTheLowestAndAHighLevel(Compression algorithm) throws IOException {
        byte[] content = sample();
        int highest = algorithm == Compression.XZ ? algorithm.defaultLevel() : algorithm.maxLevel();
        for (int level : new int[] {algorithm.minLevel(), highest}) {
            byte[] stored = compress(new CompressionSettings(algorithm, level, 0), content);
            assertArrayEquals(content, decompress(stored, algorithm), algorithm + " level " + level);
        }
    }

    @Test
    void zstdUsesWorkerThreadsWhenAsked() throws IOException {
        byte[] content = sample();

        byte[] stored = compress(new CompressionSettings(Compression.ZSTD, 5, 2), content);

        assertArrayEquals(content, decompress(stored, Compression.ZSTD));
    }

    @Test
    void theLevelChangesTheGzipOutput() throws IOException {
        byte[] content = sample();

        byte[] fastest = compress(new CompressionSettings(Compression.GZIP, 1, 0), content);
        byte[] smallest = compress(new CompressionSettings(Compression.GZIP, 9, 0), content);

        assertTrue(smallest.length < fastest.length);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(smallest))) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    void zipHoldsOneEntryNamedAfterTheArchive() throws IOException {
        byte[] content = sample();

        byte[] stored = compress(CompressionSettings.of(Compression.ZIP), content, "alice@example.com.tar");

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(stored))) {
            ZipEntry entry = zip.getNextEntry();
            assertEquals("alice@example.com.tar", entry.getName());
            assertArrayEquals(content, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }

    @Test
    void readsAMultiMemberGzipAsOneStream() throws IOException {
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        for (String part : new String[] {"first ", "second ", "third"}) {
            try (GZIPOutputStream member = new GZIPOutputStream(new NonClosing(stored))) {
                member.write(part.getBytes(StandardCharsets.US_ASCII));
            }
        }

        assertEquals("first second third",
                new String(decompress(stored.toByteArray(), Compression.GZIP), StandardCharsets.US_ASCII));
    }

    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"BZIP2", "XZ", "ZSTD", "LZ4"})
    void readsConcatenatedStreams(Compression algorithm) throws IOException {
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        stored.writeBytes(compress(CompressionSettings.of(algorithm), "one ".getBytes(StandardCharsets.US_ASCII)));
        stored.writeBytes(compress(CompressionSettings.of(algorithm), "two".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("one two",
                new String(decompress(stored.toByteArray(), algorithm), StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsAZipWithoutEntries() throws IOException {
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        new ZipOutputStream(empty).close();

        IOException e = assertThrows(IOException.class,
                () -> compressor.decompress(new ByteArrayInputStream(empty.toByteArray()), Compression.ZIP));
        assertTrue(e.getMessage().contains("no entry"));
    }

    @Test
    void rejectsContentThatIsNotInTheNamedFormat() {
        byte[] plain = "not compressed".getBytes(StandardCharsets.US_ASCII);

        assertThrows(IOException.class, () -> decompress(plain, Compression.GZIP));
        assertThrows(IOException.class, () -> decompress(plain, Compression.XZ));
        assertThrows(IOException.class, () -> decompress(plain, Compression.BZIP2));
    }

    @Test
    void rejectsNullArguments() {
        OutputStream sink = new ByteArrayOutputStream();
        CompressionSettings settings = CompressionSettings.DEFAULT;
        assertThrows(NullPointerException.class, () -> compressor.compress(null, "a.tar", settings));
        assertThrows(NullPointerException.class, () -> compressor.compress(sink, null, settings));
        assertThrows(NullPointerException.class, () -> compressor.compress(sink, "a.tar", null));
        assertThrows(NullPointerException.class, () -> compressor.decompress(null, Compression.GZIP));
        assertThrows(NullPointerException.class,
                () -> compressor.decompress(new ByteArrayInputStream(new byte[0]), null));
    }

    private byte[] compress(CompressionSettings settings, byte[] content) throws IOException {
        return compress(settings, content, "archive.tar");
    }

    private byte[] compress(CompressionSettings settings, byte[] content, String entryName) throws IOException {
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        try (OutputStream out = compressor.compress(stored, entryName, settings)) {
            out.write(content, 0, content.length / 2);
            for (int i = content.length / 2; i < content.length; i++) {
                out.write(content[i]);
            }
        }
        return stored.toByteArray();
    }

    private byte[] decompress(byte[] stored, Compression algorithm) throws IOException {
        try (InputStream in = compressor.decompress(new ByteArrayInputStream(stored), algorithm)) {
            return in.readAllBytes();
        }
    }

    private static byte[] sample() {
        Random random = new Random(42);
        StringBuilder text = new StringBuilder();
        String[] words = {"Subject:", "Inbox", "meeting", "invoice", "From:", "zimbra", "\r\n", "the", "backup"};
        while (text.length() < 200_000) {
            text.append(words[random.nextInt(words.length)]).append(' ');
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
