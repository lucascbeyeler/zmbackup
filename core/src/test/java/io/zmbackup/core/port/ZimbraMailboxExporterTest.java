package io.zmbackup.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ZimbraMailboxExporterTest {

    @Test
    void fullExportDelegatesToIncrementalExportWithNullSince() throws IOException {
        Instant[] capturedSince = new Instant[1];
        ZimbraMailboxExporter exporter = new ZimbraMailboxExporter() {
            @Override
            public boolean export(String account, OutputStream destination, Instant since) throws IOException {
                capturedSince[0] = since;
                destination.write("content".getBytes());
                return true;
            }

            @Override
            public void restore(String account, InputStream source) {
                throw new UnsupportedOperationException();
            }
        };

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        boolean wroteContent = exporter.export("user@example.com", destination);

        assertTrue(wroteContent);
        assertEquals("content", destination.toString());
        assertNull(capturedSince[0]);
    }
}
