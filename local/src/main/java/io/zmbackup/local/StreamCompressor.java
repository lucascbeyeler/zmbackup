package io.zmbackup.local;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import io.zmbackup.core.domain.Compression;
import io.zmbackup.core.domain.CompressionSettings;
import io.zmbackup.core.port.Compressor;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

public final class StreamCompressor implements Compressor {

    static final int BUFFER_SIZE = 64 * 1024;

    @Override
    public OutputStream compress(OutputStream destination, String entryName, CompressionSettings settings)
            throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(entryName, "entryName must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        OutputStream encoder = switch (settings.algorithm()) {
            case NONE -> destination;
            case GZIP -> new LeveledGzipOutputStream(destination, settings.effectiveLevel());
            case BZIP2 -> new BZip2CompressorOutputStream(destination, settings.effectiveLevel());
            case XZ -> new XZCompressorOutputStream(destination, settings.effectiveLevel());
            case ZSTD -> zstd(destination, settings);
            case LZ4 -> new FramedLZ4CompressorOutputStream(destination);
            case ZIP -> zip(destination, entryName, settings.effectiveLevel());
        };
        return new BufferedOutputStream(encoder, BUFFER_SIZE);
    }

    @Override
    public InputStream decompress(InputStream source, Compression algorithm) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        InputStream buffered = new BufferedInputStream(source, BUFFER_SIZE);
        return switch (algorithm) {
            case NONE -> buffered;
            case GZIP -> new GZIPInputStream(buffered, BUFFER_SIZE);
            case BZIP2 -> new BZip2CompressorInputStream(buffered, true);
            case XZ -> new XZCompressorInputStream(buffered, true);
            case ZSTD -> unzstd(buffered);
            case LZ4 -> new FramedLZ4CompressorInputStream(buffered, true);
            case ZIP -> unzip(buffered);
        };
    }

    private static OutputStream zstd(OutputStream destination, CompressionSettings settings) throws IOException {
        try {
            ZstdOutputStream zstd = new ZstdOutputStream(destination, settings.effectiveLevel());
            if (settings.threads() > 0) {
                zstd.setWorkers(settings.threads());
            }
            return zstd;
        } catch (LinkageError e) {
            throw zstdUnavailable(e);
        }
    }

    private static InputStream unzstd(InputStream source) throws IOException {
        try {
            return new ZstdInputStream(source);
        } catch (LinkageError e) {
            throw zstdUnavailable(e);
        }
    }

    private static IOException zstdUnavailable(LinkageError e) {
        return new IOException("zstd is not available on this platform (" + System.getProperty("os.name") + "/"
                + System.getProperty("os.arch") + "): " + e.getMessage(), e);
    }

    private static OutputStream zip(OutputStream destination, String entryName, int level) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(destination);
        zip.setLevel(level);
        zip.putNextEntry(new ZipEntry(entryName));
        return new SingleEntryZipOutputStream(zip);
    }

    private static InputStream unzip(InputStream source) throws IOException {
        ZipInputStream zip = new ZipInputStream(source);
        if (zip.getNextEntry() == null) {
            zip.close();
            throw new IOException("The zip archive holds no entry");
        }
        return zip;
    }

    private static final class LeveledGzipOutputStream extends GZIPOutputStream {

        LeveledGzipOutputStream(OutputStream out, int level) throws IOException {
            super(out, BUFFER_SIZE);
            def.setLevel(level);
        }
    }

    private static final class SingleEntryZipOutputStream extends FilterOutputStream {

        private final ZipOutputStream zip;

        SingleEntryZipOutputStream(ZipOutputStream zip) {
            super(zip);
            this.zip = zip;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            zip.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try (ZipOutputStream closing = zip) {
                closing.closeEntry();
            }
        }
    }
}
