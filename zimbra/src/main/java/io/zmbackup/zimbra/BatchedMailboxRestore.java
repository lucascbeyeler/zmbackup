package io.zmbackup.zimbra;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

final class BatchedMailboxRestore {

    private static final Logger LOG = Logger.getLogger(BatchedMailboxRestore.class.getName());

    static final int MAX_ITEMS_PER_BATCH = 1000;
    static final long MAX_BYTES_PER_BATCH = 64L * 1024 * 1024;

    private final RestTransport transport;
    private final RestTransport.Retrier retrier;
    private final int maxItems;
    private final long maxBytes;

    BatchedMailboxRestore(RestTransport transport, RestTransport.Retrier retrier) {
        this(transport, retrier, MAX_ITEMS_PER_BATCH, MAX_BYTES_PER_BATCH);
    }

    BatchedMailboxRestore(RestTransport transport, RestTransport.Retrier retrier, int maxItems, long maxBytes) {
        this.transport = transport;
        this.retrier = retrier;
        this.maxItems = maxItems;
        this.maxBytes = maxBytes;
    }

    void restore(String account, InputStream tar, URI tarImportUri) throws IOException {
        TarEntryGroupReader reader = new TarEntryGroupReader(new BufferedInputStream(tar, 64 * 1024));
        Spool spool = new Spool();
        boolean structurePhase = true;
        boolean lastWasMeta = false;
        int[] posted = {0};
        try {
            for (TarEntryGroupReader.Entry entry = reader.next(); entry != null; entry = reader.next()) {
                if (ItemMeta.isMetaEntry(entry)) {
                    byte[] json = entry.readBody(ItemMeta.MAX_META_BYTES);
                    ItemMeta meta = ItemMeta.parse(json);
                    if (structurePhase && meta != null && meta.isStructure()) {
                        spool.add(entry, json);
                        continue;
                    }
                    if (structurePhase) {
                        postStructure(account, spool, tarImportUri);
                        structurePhase = false;
                    }
                    if (spool.full()) {
                        postBatch(account, spool, tarImportUri, posted);
                    }
                    spool.add(entry, json);
                    lastWasMeta = true;
                } else {
                    if (structurePhase) {
                        postStructure(account, spool, tarImportUri);
                        structurePhase = false;
                    }
                    if (!lastWasMeta && spool.full()) {
                        postBatch(account, spool, tarImportUri, posted);
                    }
                    spool.copy(entry, !lastWasMeta);
                    lastWasMeta = false;
                }
            }
            if (structurePhase) {
                postStructure(account, spool, tarImportUri);
            } else if (spool.entries > 0) {
                postBatch(account, spool, tarImportUri, posted);
            }
        } finally {
            spool.discard();
        }
    }

    private void postStructure(String account, Spool spool, URI uri) throws IOException {
        if (spool.entries == 0) {
            return;
        }
        int entries = spool.entries;
        post(spool, uri, "restore of the folder structure (" + entries + " entr" + (entries == 1 ? "y" : "ies")
                + ") for " + account);
    }

    private void postBatch(String account, Spool spool, URI uri, int[] posted) throws IOException {
        int items = spool.items;
        post(spool, uri, "restore batch " + (++posted[0]) + " (" + items + " item(s)) for " + account);
    }

    private void post(Spool spool, URI uri, String description) throws IOException {
        Path file = spool.finish();
        try {
            retrier.run(description, () -> {
                transport.post(uri, BodyPublishers.ofFile(file), description);
                return null;
            });
            LOG.fine(() -> description + " done");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private final class Spool {
        private Path file;
        private OutputStream out;
        private int entries;
        private int items;
        private long bytes;

        boolean full() {
            return items >= maxItems || bytes >= maxBytes;
        }

        void add(TarEntryGroupReader.Entry entry, byte[] body) throws IOException {
            open();
            entry.writeRaw(out, body);
            entries++;
            items++;
            bytes += entry.rawSize();
        }

        void copy(TarEntryGroupReader.Entry entry, boolean countsAsItem) throws IOException {
            open();
            entry.copyRaw(out);
            entries++;
            if (countsAsItem) {
                items++;
            }
            bytes += entry.rawSize();
        }

        Path finish() throws IOException {
            out.write(TarEntryGroupReader.END_OF_ARCHIVE);
            out.close();
            Path finished = file;
            file = null;
            out = null;
            entries = 0;
            items = 0;
            bytes = 0;
            return finished;
        }

        void discard() throws IOException {
            if (out != null) {
                out.close();
            }
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }

        private void open() throws IOException {
            if (out == null) {
                file = Files.createTempFile("zmbackup-restore-", ".tar");
                out = new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024);
            }
        }
    }
}
