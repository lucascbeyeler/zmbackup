package io.zmbackup.core.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;

public interface ZimbraMailboxExporter {

    default boolean export(String account, OutputStream destination) throws IOException {
        return export(account, destination, null);
    }

    boolean export(String account, OutputStream destination, Instant since) throws IOException;

    void restore(String account, InputStream source) throws IOException;
}
