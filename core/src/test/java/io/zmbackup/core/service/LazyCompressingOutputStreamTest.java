package io.zmbackup.core.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LazyCompressingOutputStreamTest {

    @Test
    void opensTheEncoderOnTheFirstWriteOnly() throws IOException {
        TrackingStream stored = new TrackingStream();
        int[] opened = {0};
        try (LazyCompressingOutputStream out = new LazyCompressingOutputStream(stored, s -> {
            opened[0]++;
            s.write('[');
            return new FilterOutputStream(s);
        })) {
            out.flush();
            assertEquals(0, opened[0]);
            out.write(new byte[0], 0, 0);
            assertEquals(0, opened[0]);
            out.write('a');
            out.write("bc".getBytes(StandardCharsets.US_ASCII), 0, 2);
            out.flush();
        }
        assertEquals(1, opened[0]);
        assertArrayEquals("[abc".getBytes(StandardCharsets.US_ASCII), stored.toByteArray());
        assertTrue(stored.closed);
    }

    @Test
    void closesTheStoredStreamWithoutEncodingWhenNothingWasWritten() throws IOException {
        TrackingStream stored = new TrackingStream();
        LazyCompressingOutputStream out = new LazyCompressingOutputStream(stored, s -> {
            throw new AssertionError("must not open");
        });
        out.close();
        out.close();
        assertEquals(0, stored.size());
        assertTrue(stored.closed);
        assertThrows(IOException.class, () -> out.write('x'));
    }

    @Test
    void closesTheStoredStreamWhenTheEncoderCannotBeOpened() {
        TrackingStream stored = new TrackingStream();
        IOException failure = new IOException("no codec");
        LazyCompressingOutputStream out = new LazyCompressingOutputStream(stored, s -> {
            throw failure;
        });
        assertSame(failure, assertThrows(IOException.class, () -> out.write('x')));
        assertTrue(stored.closed);
    }

    private static final class TrackingStream extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
