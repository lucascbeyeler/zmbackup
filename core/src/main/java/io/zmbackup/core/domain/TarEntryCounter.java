package io.zmbackup.core.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class TarEntryCounter {

    private static final int BLOCK_SIZE = 512;
    private static final int NAME_FIELD_LENGTH = 100;
    private static final int SIZE_FIELD_OFFSET = 124;
    private static final int SIZE_FIELD_LENGTH = 12;
    private static final int TYPEFLAG_OFFSET = 156;
    private static final char REGULAR_FILE_TYPEFLAG_USTAR = '0';
    private static final char REGULAR_FILE_TYPEFLAG_V7 = '\0';
    private static final char GNU_LONGNAME_TYPEFLAG = 'L';

    private TarEntryCounter() {
    }

    public static long countNestedFileEntries(InputStream tgz) throws IOException {
        long count = 0;
        String pendingLongName = null;
        byte[] header = new byte[BLOCK_SIZE];
        try (InputStream in = new GZIPInputStream(tgz)) {
            while (readFully(in, header)) {
                if (isZeroBlock(header)) {
                    break;
                }
                char typeflag = (char) header[TYPEFLAG_OFFSET];
                long size = parseOctalSize(header);
                if (typeflag == GNU_LONGNAME_TYPEFLAG) {
                    pendingLongName = readPaddedPayloadAsString(in, size);
                    continue;
                }
                String name = pendingLongName != null ? pendingLongName : extractHeaderName(header);
                pendingLongName = null;
                if (isRegularFile(typeflag) && name.indexOf('/') >= 0) {
                    count++;
                }
                skipFully(in, paddedSize(size));
            }
        }
        return count;
    }

    private static boolean isRegularFile(char typeflag) {
        return typeflag == REGULAR_FILE_TYPEFLAG_USTAR || typeflag == REGULAR_FILE_TYPEFLAG_V7;
    }

    private static String extractHeaderName(byte[] header) {
        int end = 0;
        while (end < NAME_FIELD_LENGTH && header[end] != 0) {
            end++;
        }
        return new String(header, 0, end, StandardCharsets.UTF_8);
    }

    private static String readPaddedPayloadAsString(InputStream in, long size) throws IOException {
        byte[] raw = new byte[Math.toIntExact(size)];
        int offset = 0;
        while (offset < raw.length) {
            int read = in.read(raw, offset, raw.length - offset);
            if (read < 0) {
                throw new IOException("Truncated tar GNU long-name payload");
            }
            offset += read;
        }
        skipFully(in, paddedSize(size) - size);
        int end = raw.length;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new IOException(
                        "Truncated tar header: expected " + buffer.length + " bytes, got " + offset);
            }
            offset += read;
        }
        return true;
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        byte[] buffer = new byte[BLOCK_SIZE];
        long remaining = bytes;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated tar entry: expected " + remaining + " more byte(s)");
            }
            remaining -= read;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static long parseOctalSize(byte[] header) {
        String field = new String(header, SIZE_FIELD_OFFSET, SIZE_FIELD_LENGTH, StandardCharsets.US_ASCII);
        String trimmed = field.replace('\0', ' ').trim();
        return trimmed.isEmpty() ? 0 : Long.parseLong(trimmed, 8);
    }

    private static long paddedSize(long size) {
        long remainder = size % BLOCK_SIZE;
        return remainder == 0 ? size : size + (BLOCK_SIZE - remainder);
    }
}
