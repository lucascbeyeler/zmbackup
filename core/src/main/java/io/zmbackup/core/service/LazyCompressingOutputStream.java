package io.zmbackup.core.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class LazyCompressingOutputStream extends OutputStream {

    @FunctionalInterface
    interface Opener {
        OutputStream open(OutputStream stored) throws IOException;
    }

    private final OutputStream stored;
    private final Opener opener;
    private OutputStream compressed;
    private boolean closed;

    LazyCompressingOutputStream(OutputStream stored, Opener opener) {
        this.stored = Objects.requireNonNull(stored, "stored must not be null");
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
    }

    @Override
    public void write(int b) throws IOException {
        target().write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }
        target().write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        if (compressed != null) {
            compressed.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (compressed != null) {
            compressed.close();
        } else {
            stored.close();
        }
    }

    private OutputStream target() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (compressed == null) {
            try {
                compressed = opener.open(stored);
            } catch (IOException | RuntimeException e) {
                closed = true;
                try {
                    stored.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
        return compressed;
    }
}
