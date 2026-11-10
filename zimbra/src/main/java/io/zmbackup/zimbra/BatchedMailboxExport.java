package io.zmbackup.zimbra;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class BatchedMailboxExport {

    private static final Logger LOG = Logger.getLogger(BatchedMailboxExport.class.getName());

    static final int MAX_URL_LENGTH = 6000;
    static final int MAX_IDS_PER_BATCH = 1000;

    @FunctionalInterface
    interface UriBuilder {
        URI build(String query) throws IOException;
    }

    private final RestTransport transport;
    private final RestTransport.Retrier retrier;
    private final MailboxTransferSettings settings;
    private final Clock clock;

    BatchedMailboxExport(
            RestTransport transport, RestTransport.Retrier retrier, MailboxTransferSettings settings, Clock clock) {
        this.transport = transport;
        this.retrier = retrier;
        this.settings = settings;
        this.clock = clock;
    }

    boolean export(String account, OutputStream destination, Instant since, String metadataQuery, UriBuilder uris)
            throws IOException {
        Instant normalizedSince = since == null ? null : Instant.ofEpochMilli(since.toEpochMilli());
        try (ExportJournal journal = ExportJournal.open(settings.exportJournalDir(), account)) {
            ExportJournal.Plan plan = journal.loadPlan(account, normalizedSince, clock.instant(), settings.journalMaxAge());
            Set<Integer> done;
            if (plan == null) {
                journal.clear();
                plan = metadataPass(account, normalizedSince, metadataQuery, uris, journal);
                if (plan == null) {
                    journal.clear();
                    return false;
                }
                if (journal.resumable()) {
                    journal.savePlan(plan);
                }
                done = Set.of();
            } else {
                done = journal.doneBatches();
                int remaining = plan.batches().size() - done.size();
                ExportJournal.Plan resumed = plan;
                LOG.info(() -> "Resuming the export of " + account + " from its journal (created "
                        + resumed.created() + "): " + remaining + " of " + resumed.batches().size()
                        + " batch(es) left");
            }

            List<List<Integer>> batches = plan.batches();
            for (int i = 0; i < batches.size(); i++) {
                if (done.contains(i)) {
                    continue;
                }
                int batch = i;
                List<Integer> ids = batches.get(i);
                String description = "export batch " + (batch + 1) + "/" + batches.size() + " (" + ids.size()
                        + " item(s)) for " + account;
                retrier.run(description, () -> {
                    fetchBatch(account, batch, ids, uris, journal, description);
                    return null;
                });
                if (journal.resumable()) {
                    journal.markDone(batch);
                }
            }

            assemble(plan, journal, destination);
            journal.clear();
            return true;
        }
    }

    private ExportJournal.Plan metadataPass(
            String account, Instant since, String metadataQuery, UriBuilder uris, ExportJournal journal)
            throws IOException {
        URI uri = uris.build(metadataQuery);
        String description = "metadata pass for " + account;
        Path part = partFile(journal.structureFile());
        MetadataResult result = retrier.run(description, () -> readMetadata(uri, description, part));
        if (result == null || (result.ids().isEmpty() && result.structureEntries() == 0)) {
            Files.deleteIfExists(part);
            return null;
        }
        boolean hasStructure = result.structureEntries() > 0;
        if (hasStructure) {
            ExportJournal.commit(part, journal.structureFile());
        } else {
            Files.deleteIfExists(part);
        }
        List<List<Integer>> batches = partition(result.ids(), uris);
        LOG.info(() -> "Metadata pass for " + account + ": " + result.structureEntries() + " structure entr"
                + (result.structureEntries() == 1 ? "y" : "ies") + ", " + result.ids().size() + " item(s) in "
                + batches.size() + " batch(es)");
        return new ExportJournal.Plan(account, since, clock.instant(), hasStructure, batches);
    }

    private record MetadataResult(LinkedHashSet<Integer> ids, int structureEntries) {}

    private MetadataResult readMetadata(URI uri, String description, Path structurePart) throws IOException {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        int structure = 0;
        try (InputStream body = transport.get(uri, description)) {
            if (body == null) {
                return null;
            }
            try (OutputStream out = gzipTo(structurePart)) {
                TarEntryGroupReader reader = new TarEntryGroupReader(new BufferedInputStream(body, 64 * 1024));
                for (TarEntryGroupReader.Entry entry = reader.next(); entry != null; entry = reader.next()) {
                    if (!ItemMeta.isMetaEntry(entry)) {
                        warnIfError(entry, description);
                        continue;
                    }
                    byte[] json = entry.readBody(ItemMeta.MAX_META_BYTES);
                    ItemMeta meta = ItemMeta.parse(json);
                    if (meta == null) {
                        String name = entry.name();
                        LOG.warning(() -> description + ": cannot read the item ID of " + name
                                + "; that entry is not backed up");
                    } else if (meta.isStructure()) {
                        entry.writeRaw(out, json);
                        structure++;
                    } else if (meta.id() > 0) {
                        ids.add(meta.id());
                    }
                }
            }
        }
        return new MetadataResult(ids, structure);
    }

    static List<List<Integer>> partition(Set<Integer> ids, UriBuilder uris) throws IOException {
        int baseLength = uris.build("fmt=tar&list=").toASCIIString().length();
        List<List<Integer>> batches = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int length = baseLength;
        for (int id : ids) {
            int added = Integer.toString(id).length() + (current.isEmpty() ? 0 : 1);
            if (!current.isEmpty() && (length + added > MAX_URL_LENGTH || current.size() >= MAX_IDS_PER_BATCH)) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                length = baseLength;
                added = Integer.toString(id).length();
            }
            current.add(id);
            length += added;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private void fetchBatch(
            String account, int batch, List<Integer> ids, UriBuilder uris, ExportJournal journal, String description)
            throws IOException {
        Path target = journal.batchFile(batch);
        Path part = partFile(target);
        Set<Integer> requested = new LinkedHashSet<>(ids);
        try (OutputStream out = gzipTo(part)) {
            Set<Integer> returned = fetchInto(requested, uris, out, description);
            Set<Integer> missing = new TreeSet<>(requested);
            missing.removeAll(returned);
            if (!missing.isEmpty()) {
                returned = fetchInto(missing, uris, out, description + ", second request for missing items");
                missing.removeAll(returned);
            }
            if (!missing.isEmpty()) {
                LOG.warning(() -> description + ": Zimbra returned no data for " + missing.size() + " of "
                        + requested.size() + " item(s) listed by the metadata pass, so they are not in this"
                        + " backup. They were most likely deleted during the export; otherwise check"
                        + " mailbox.log (for example \"missing blob\"). IDs: " + missing);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        ExportJournal.commit(part, target);
    }

    private Set<Integer> fetchInto(Set<Integer> ids, UriBuilder uris, OutputStream out, String description)
            throws IOException {
        if (ids.isEmpty()) {
            return Set.of();
        }
        StringBuilder list = new StringBuilder();
        for (int id : ids) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append(id);
        }
        URI uri = uris.build("fmt=tar&list=" + list);
        Set<Integer> returned = new LinkedHashSet<>();
        Set<Integer> unexpected = new TreeSet<>();
        try (InputStream body = transport.get(uri, description)) {
            if (body == null) {
                return returned;
            }
            TarEntryGroupReader reader = new TarEntryGroupReader(new BufferedInputStream(body, 64 * 1024));
            for (TarEntryGroupReader.Entry entry = reader.next(); entry != null; entry = reader.next()) {
                if (ItemMeta.isMetaEntry(entry)) {
                    byte[] json = entry.readBody(ItemMeta.MAX_META_BYTES);
                    ItemMeta meta = ItemMeta.parse(json);
                    if (meta != null) {
                        if (ids.contains(meta.id())) {
                            returned.add(meta.id());
                        } else {
                            unexpected.add(meta.id());
                        }
                    }
                    entry.writeRaw(out, json);
                } else {
                    warnIfError(entry, description);
                    entry.copyRaw(out);
                }
            }
        }
        if (!unexpected.isEmpty()) {
            LOG.warning(() -> description + ": Zimbra returned item(s) that were not requested: " + unexpected);
        }
        return returned;
    }

    private static void assemble(ExportJournal.Plan plan, ExportJournal journal, OutputStream destination)
            throws IOException {
        if (plan.hasStructure()) {
            copyUnzipped(journal.structureFile(), destination);
        }
        for (int i = 0; i < plan.batches().size(); i++) {
            copyUnzipped(journal.batchFile(i), destination);
        }
        destination.write(TarEntryGroupReader.END_OF_ARCHIVE);
        destination.flush();
    }

    private static void copyUnzipped(Path part, OutputStream destination) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(part), 64 * 1024)) {
            in.transferTo(destination);
        }
    }

    private static void warnIfError(TarEntryGroupReader.Entry entry, String description) {
        String name = entry.name();
        if (name.endsWith(".err")) {
            LOG.warning(() -> description + ": Zimbra flagged " + name.substring(0, name.length() - 4)
                    + " as unreadable (an .err entry); its content in this backup may be damaged. Check mailbox.log");
        }
    }

    private static Path partFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static OutputStream gzipTo(Path file) throws IOException {
        return new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024), 64 * 1024);
    }
}
