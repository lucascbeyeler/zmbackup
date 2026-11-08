package io.zmbackup.zimbra;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The on-disk state of one account's batched export, so a re-run after a failure fetches only the
 * batches that are still missing.
 *
 * <pre>
 * &lt;root&gt;/&lt;account&gt;.lock      held while an export of the account runs
 * &lt;root&gt;/&lt;account&gt;/plan       the batches (item IDs) planned by the metadata pass
 * &lt;root&gt;/&lt;account&gt;/structure.gz  folders, tags and other structure entries, gzipped
 * &lt;root&gt;/&lt;account&gt;/batch-NNNNNN.gz  each fetched batch, gzipped, without an end-of-archive marker
 * &lt;root&gt;/&lt;account&gt;/done       the numbers of the batches already stored
 * </pre>
 *
 * The gzip members concatenate into one valid {@code .tgz}. Every file is non-empty, so housekeeping's
 * empty-file cleanup never removes one. Without a root, or when another export of the same account
 * holds the lock, the journal lives in a temporary folder deleted on close, so nothing resumes.
 */
final class ExportJournal implements Closeable {

    private static final Logger LOG = Logger.getLogger(ExportJournal.class.getName());
    private static final String PLAN_MAGIC = "zmbackup-export-plan 1";

    private final Path dir;
    private final boolean temporary;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private ExportJournal(Path dir, boolean temporary, FileChannel lockChannel, FileLock lock) {
        this.dir = dir;
        this.temporary = temporary;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    static ExportJournal open(Path root, String account) throws IOException {
        if (root != null) {
            Files.createDirectories(root);
            Path lockFile = root.resolve(account + ".lock");
            FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            FileLock lock = null;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock != null) {
                if (channel.size() == 0) {
                    channel.write(ByteBuffer.wrap(
                            "zmbackup mailbox export journal lock\n".getBytes(StandardCharsets.US_ASCII)));
                }
                Path dir = root.resolve(account);
                Files.createDirectories(dir);
                return new ExportJournal(dir, false, channel, lock);
            }
            channel.close();
            LOG.info(() -> "Another export of " + account + " holds its journal; this export will not be"
                    + " resumable");
        }
        return new ExportJournal(Files.createTempDirectory("zmbackup-export-"), true, null, null);
    }

    boolean resumable() {
        return !temporary;
    }

    Path structureFile() {
        return dir.resolve("structure.gz");
    }

    Path batchFile(int batch) {
        return dir.resolve(String.format("batch-%06d.gz", batch));
    }

    /** The plan of an earlier, unfinished export, if it matches this one and isn't too old. */
    Plan loadPlan(String account, Instant since, Instant now, Duration maxAge) throws IOException {
        Path file = dir.resolve("plan");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Plan plan;
        try {
            plan = Plan.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            LOG.warning(() -> "Discarding unreadable export journal for " + account + ": " + e.getMessage());
            return null;
        }
        if (!plan.account().equals(account) || !java.util.Objects.equals(plan.since(), since)) {
            LOG.info(() -> "Discarding the export journal for " + account + ": it was for a different export");
            return null;
        }
        if (plan.created().plus(maxAge).isBefore(now)) {
            LOG.info(() -> "Discarding the export journal for " + account + ": it is older than "
                    + maxAge.toHours() + " h");
            return null;
        }
        if (plan.hasStructure() && !Files.isRegularFile(structureFile())) {
            return null;
        }
        return plan;
    }

    void savePlan(Plan plan) throws IOException {
        writeAtomically(dir.resolve("plan"), plan.format());
    }

    Set<Integer> doneBatches() throws IOException {
        Path file = dir.resolve("done");
        Set<Integer> done = new HashSet<>();
        if (!Files.isRegularFile(file)) {
            return done;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                try {
                    int batch = Integer.parseInt(trimmed);
                    if (Files.isRegularFile(batchFile(batch))) {
                        done.add(batch);
                    }
                } catch (NumberFormatException e) {
                    // A torn last line from a crash: that batch is simply fetched again.
                }
            }
        }
        return done;
    }

    void markDone(int batch) throws IOException {
        Files.writeString(dir.resolve("done"), batch + "\n", StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                StandardOpenOption.SYNC);
    }

    /** Moves a fully written {@code .part} file into place. */
    static void commit(Path part, Path target) throws IOException {
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes everything the journal holds, keeping the folder. */
    void clear() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                deleteRecursively(file);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (temporary) {
                deleteRecursively(dir);
            } else {
                try (Stream<Path> files = Files.list(dir)) {
                    if (files.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
        } finally {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        }
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(part, content, StandardCharsets.UTF_8);
        commit(part, target);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** The batches of item IDs an export fetches, decided once by the metadata pass. */
    record Plan(String account, Instant since, Instant created, boolean hasStructure, List<List<Integer>> batches) {

        String format() {
            StringBuilder out = new StringBuilder(PLAN_MAGIC).append('\n');
            out.append("account ").append(account).append('\n');
            out.append("since ").append(since == null ? "-" : Long.toString(since.toEpochMilli())).append('\n');
            out.append("created ").append(created.toEpochMilli()).append('\n');
            out.append("structure ").append(hasStructure ? 1 : 0).append('\n');
            for (List<Integer> batch : batches) {
                out.append("batch ");
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(batch.get(i));
                }
                out.append('\n');
            }
            out.append("end\n");
            return out.toString();
        }

        static Plan parse(List<String> lines) {
            if (lines.isEmpty() || !PLAN_MAGIC.equals(lines.get(0)) || !"end".equals(lines.get(lines.size() - 1))) {
                throw new IllegalArgumentException("not a complete export plan");
            }
            String account = null;
            Instant since = null;
            Instant created = null;
            Boolean structure = null;
            List<List<Integer>> batches = new ArrayList<>();
            try {
                for (String line : lines.subList(1, lines.size() - 1)) {
                    int space = line.indexOf(' ');
                    String key = space < 0 ? line : line.substring(0, space);
                    String value = space < 0 ? "" : line.substring(space + 1);
                    switch (key) {
                        case "account" -> account = value;
                        case "since" -> since = "-".equals(value) ? null : Instant.ofEpochMilli(Long.parseLong(value));
                        case "created" -> created = Instant.ofEpochMilli(Long.parseLong(value));
                        case "structure" -> structure = "1".equals(value);
                        case "batch" -> {
                            List<Integer> ids = new ArrayList<>();
                            for (String id : value.split(",")) {
                                ids.add(Integer.parseInt(id));
                            }
                            batches.add(List.copyOf(ids));
                        }
                        default -> throw new IllegalArgumentException("unknown line: " + key);
                    }
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number in export plan", e);
            }
            if (account == null || created == null || structure == null) {
                throw new IllegalArgumentException("export plan is missing a field");
            }
            return new Plan(account, since, created, structure, List.copyOf(batches));
        }
    }
}
