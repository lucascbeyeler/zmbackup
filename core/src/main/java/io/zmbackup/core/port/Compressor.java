package io.zmbackup.core.port;

import io.zmbackup.core.domain.Compression;
import io.zmbackup.core.domain.CompressionSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface Compressor {

    OutputStream compress(OutputStream destination, String entryName, CompressionSettings settings)
            throws IOException;

    InputStream decompress(InputStream source, Compression algorithm) throws IOException;
}
