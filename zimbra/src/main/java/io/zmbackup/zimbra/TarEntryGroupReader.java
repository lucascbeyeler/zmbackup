package io.zmbackup.zimbra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a tar stream one entry at a time, keeping every entry's raw blocks, so entries can be copied
 * byte for byte into another tar. GNU long-name/long-link and pax header blocks are kept with the
 * entry they describe. Reading stops at the first all-zero block (the end-of-archive marker), which is
 * never copied.
 */
final class TarEntryGroupReader {

    static final int BLOCK = 512;
    /** The end-of-archive marker: two zero blocks. */
    static final byte[] END_OF_ARCHIVE = new byte[2 * BLOCK];

    private static final int MAX_PREFIX_PAYLOAD = 1 << 20;

    private final InputStream in;
    private Entry current;
    private boolean ended;

    TarEntryGroupReader(InputStream in) {
        this.in = in;
    }

    /** Returns the next entry, or {@code null} at the end of the archive. Skips any unread body. */
    Entry next() throws IOException {
        if (current != null) {
            current.skip();
            current = null;
        }
        if (ended) {
            return null;
        }
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        String longName = null;
        while (true) {
            byte[] header = new byte[BLOCK];
            if (!readFully(header)) {
                ended = true;
                if (prefix.size() > 0) {
                    throw new IOException("Truncated tar: header blocks without an entry");
                }
                return null;
            }
            if (isZero(header)) {
                ended = true;
                if (prefix.size() > 0) {
                    throw new IOException("Truncated tar: header blocks without an entry");
                }
                return null;
            }
            char type = (char) header[156];
            long size = parseSize(header);
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                if (size > MAX_PREFIX_PAYLOAD) {
                    throw new IOException("Tar extended header too large: " + size + " bytes");
                }
                byte[] payload = new byte[Math.toIntExact(padded(size))];
                if (!readFully(payload)) {
                    throw new IOException("Truncated tar extended header");
                }
                prefix.write(header);
                prefix.write(payload);
                if (type == 'L') {
                    longName = trimNul(payload, (int) size);
                }
                continue;
            }
            String name = longName != null ? longName : headerName(header);
            String group = field(header, 297, 32);
            prefix.write(header);
            current = new Entry(prefix.toByteArray(), name, group, type, size);
            return current;
        }
    }

    final class Entry {
        private final byte[] headerBlocks;
        private final String name;
        private final String group;
        private final char type;
        private final long size;
        private boolean consumed;

        private Entry(byte[] headerBlocks, String name, String group, char type, long size) {
            this.headerBlocks = headerBlocks;
            this.name = name;
            this.group = group;
            this.type = type;
            this.size = size;
        }

        String name() {
            return name;
        }

        /** The tar group-name field, where Zimbra writes the item type ({@code message}, {@code folder}, ...). */
        String group() {
            return group;
        }

        long size() {
            return size;
        }

        boolean isRegularFile() {
            return type == '0' || type == '\0';
        }

        /** The entry's size in the tar: header blocks plus the padded body. */
        long rawSize() {
            return headerBlocks.length + padded(size);
        }

        /** Reads the body into memory; only for small entries such as {@code .meta}. */
        byte[] readBody(int maxBytes) throws IOException {
            if (size > maxBytes) {
                throw new IOException("Tar entry " + name + " too large to buffer: " + size + " bytes");
            }
            ensureUnread();
            byte[] body = new byte[(int) size];
            if (!readFully(body)) {
                throw new IOException("Truncated tar entry " + name);
            }
            skipBytes(padded(size) - size);
            return body;
        }

        /** Writes the header blocks, then the given body, which must be {@link #readBody}'s result. */
        void writeRaw(OutputStream out, byte[] body) throws IOException {
            out.write(headerBlocks);
            out.write(body);
            out.write(new byte[(int) (padded(size) - size)]);
        }

        /** Copies the entry's raw blocks (headers, body and padding) to {@code out}. */
        void copyRaw(OutputStream out) throws IOException {
            ensureUnread();
            out.write(headerBlocks);
            byte[] buffer = new byte[64 * 1024];
            long remaining = padded(size);
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new IOException("Truncated tar entry " + name + ": " + remaining + " byte(s) missing");
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }

        private void skip() throws IOException {
            if (!consumed) {
                consumed = true;
                skipBytes(padded(size));
            }
        }

        private void ensureUnread() throws IOException {
            if (consumed) {
                throw new IOException("Tar entry " + name + " already read");
            }
            consumed = true;
        }
    }

    static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private boolean readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new IOException("Truncated tar: expected " + buffer.length + " bytes, got " + offset);
            }
            offset += n;
        }
        return true;
    }

    private void skipBytes(long bytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = bytes;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new IOException("Truncated tar entry: " + remaining + " byte(s) missing");
            }
            remaining -= n;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static long parseSize(byte[] header) throws IOException {
        if ((header[124] & 0x80) != 0) {
            long value = 0;
            for (int i = 125; i < 136; i++) {
                value = (value << 8) | (header[i] & 0xff);
            }
            return value;
        }
        String field = new String(header, 124, 12, StandardCharsets.US_ASCII).replace('\0', ' ').trim();
        try {
            return field.isEmpty() ? 0 : Long.parseLong(field, 8);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid tar entry size: " + field, e);
        }
    }

    private static String headerName(byte[] header) {
        String name = field(header, 0, 100);
        boolean ustar = new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
        if (ustar) {
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String trimNul(byte[] payload, int size) {
        int end = Math.min(size, payload.length);
        while (end > 0 && payload[end - 1] == 0) {
            end--;
        }
        return new String(payload, 0, end, StandardCharsets.UTF_8);
    }
}
