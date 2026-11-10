package io.zmbackup.zimbra;

import static io.zmbackup.zimbra.TestTars.names;
import static io.zmbackup.zimbra.TestTars.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchedMailboxTransferTest {

    private static final String ACCOUNT = "alice@example.com";
    private static final String FULL_METADATA = "fmt=tar&body=0";

    @TempDir
    Path journalRoot;

    private final FakeTransport transport = new FakeTransport();


    @Test
    void fullExportKeepsStructureFromTheMetadataPassAndFetchesItemsInBatches() throws Exception {
        transport.respond(FULL_METADATA, tar()
                .folder(2, "/Inbox").folder(257, "/Projects").tag(259, "red")
                .message(260, "Inbox", "Subject: a").message(261, "Projects", "Subject: b")
                .contact(262, "Lovelace, Ada")
                .bytes());
        transport.respond("fmt=tar&list=260,261,262", tar()
                .message(260, "Inbox", "Subject: a\r\n\r\nfull body a")
                .message(261, "Projects", "Subject: b\r\n\r\nfull body b")
                .contact(262, "Lovelace, Ada")
                .bytes());

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertTrue(export(null, destination));

        List<TestTars.Entry> entries = TestTars.read(destination.toByteArray());
        assertEquals(List.of(
                        "Inbox.meta", "Projects.meta", "Tags/red.meta",
                        "Inbox/0000000260-msg.eml.meta", "Inbox/0000000260-msg.eml",
                        "Projects/0000000261-msg.eml.meta", "Projects/0000000261-msg.eml",
                        "Contacts/Lovelace, Ada.meta"),
                names(entries));
        assertEquals("Subject: a\r\n\r\nfull body a", entries.get(4).text());
        assertEquals("message", entries.get(4).group());
        assertEquals(List.of(FULL_METADATA, "fmt=tar&list=260,261,262"), transport.queries());
        assertJournalEmpty();
    }

    @Test
    void deduplicatesIdsSoBriefcaseRevisionsAreRequestedOnce() throws Exception {
        transport.respond(FULL_METADATA, tar()
                .meta("Briefcase/doc-00001.txt.meta", "document", 273, 8)
                .meta("Briefcase/doc-00002.txt.meta", "document", 273, 8)
                .bytes());
        transport.respond("fmt=tar&list=273", tar()
                .meta("Briefcase/doc-00001.txt.meta", "document", 273, 8)
                .meta("Briefcase/doc-00002.txt.meta", "document", 273, 8)
                .bytes());

        assertTrue(export(null, new ByteArrayOutputStream()));

        assertEquals(List.of(FULL_METADATA, "fmt=tar&list=273"), transport.queries());
    }

    @Test
    void incrementalExportUsesTheQueryOnlyForTheMetadataPass() throws Exception {
        String metadataQuery = "fmt=tar&body=0&query=after:\"07/02/2026\"";
        transport.respond(metadataQuery, tar().message(300, "Inbox", "h").bytes());
        transport.respond("fmt=tar&list=300", tar().message(300, "Inbox", "full").bytes());

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertTrue(exporter().export(ACCOUNT, destination, Instant.parse("2026-07-02T10:00:00Z"), metadataQuery,
                this::uri));

        assertEquals(List.of("Inbox/0000000300-msg.eml.meta", "Inbox/0000000300-msg.eml"),
                names(TestTars.read(destination.toByteArray())));
    }

    @Test
    void returnsFalseAndWritesNothingWhenTheMetadataPassIsEmpty() throws Exception {
        transport.respond(FULL_METADATA, 204);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertFalse(export(journalRoot, destination));

        assertEquals(0, destination.size());
        assertEquals(List.of(FULL_METADATA), transport.queries());
        assertJournalEmpty();
    }

    @Test
    void asksOnceMoreForMissingItemsThenCarriesOn() throws Exception {
        transport.respond(FULL_METADATA, tar().message(1, "Inbox", "h").message(2, "Inbox", "h")
                .message(3, "Inbox", "h").bytes());
        transport.respond("fmt=tar&list=1,2,3", tar().message(1, "Inbox", "a").message(3, "Inbox", "c").bytes());
        transport.respond("fmt=tar&list=2", tar().bytes());

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertTrue(export(null, destination));

        assertEquals(List.of(FULL_METADATA, "fmt=tar&list=1,2,3", "fmt=tar&list=2"), transport.queries());
        assertEquals(4, TestTars.read(destination.toByteArray()).size());
    }

    @Test
    void retriesAFailedBatchOnItsOwn() throws Exception {
        transport.respond(FULL_METADATA, tar().message(1, "Inbox", "h").bytes());
        transport.respondSequence("fmt=tar&list=1",
                new FakeResponse(503, null, null),
                new FakeResponse(200, null, new IOException("connection reset")),
                new FakeResponse(200, tar().message(1, "Inbox", "full").bytes(), null));

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertTrue(exporter(2).export(ACCOUNT, destination, null, FULL_METADATA, this::uri));

        assertEquals(List.of(FULL_METADATA, "fmt=tar&list=1", "fmt=tar&list=1", "fmt=tar&list=1"),
                transport.queries());
        assertEquals("full", TestTars.read(destination.toByteArray()).get(1).text());
    }

    @Test
    void doesNotRetryClientErrors() {
        transport.respond(FULL_METADATA, 401);

        assertThrows(RestTransport.HttpStatusException.class,
                () -> exporter(3).export(ACCOUNT, new ByteArrayOutputStream(), null, FULL_METADATA, this::uri));
        assertEquals(1, transport.queries().size());
    }

    @Test
    void aRerunResumesFromTheJournalAndFetchesOnlyTheMissingBatches() throws Exception {
        TestTars metadata = tar().folder(2, "/Inbox");
        TestTars batch1 = tar();
        TestTars batch2 = tar();
        for (int id = 1000; id < 2500; id++) {
            metadata.message(id, "Inbox", "h");
            (id < 2000 ? batch1 : batch2).message(id, "Inbox", "full " + id);
        }
        transport.respond(FULL_METADATA, metadata.bytes());
        String list1 = "fmt=tar&list=" + joined(1000, 2000);
        String list2 = "fmt=tar&list=" + joined(2000, 2500);
        transport.respond(list1, batch1.bytes());
        transport.respond(list2, 500);

        ByteArrayOutputStream failed = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> export(journalRoot, failed));
        assertEquals(0, failed.size(), "nothing reaches the destination until every batch is in");
        assertEquals(List.of(FULL_METADATA, list1, list2), transport.queries());

        transport.clearQueries();
        transport.respond(list2, batch2.bytes());
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertTrue(export(journalRoot, destination));

        assertEquals(List.of(list2), transport.queries(), "the rerun fetches only the batch that failed");
        List<TestTars.Entry> entries = TestTars.read(destination.toByteArray());
        assertEquals(1 + 2 * 1500, entries.size());
        assertEquals("Inbox.meta", entries.get(0).name());
        assertEquals("full 2499", entries.get(entries.size() - 1).text());
        assertJournalEmpty();
    }

    @Test
    void aStaleJournalIsDiscarded() throws Exception {
        transport.respond(FULL_METADATA, tar().message(1, "Inbox", "h").message(2000, "Inbox", "h").bytes());
        transport.respond("fmt=tar&list=1,2000", 500);
        assertThrows(IOException.class, () -> export(journalRoot, new ByteArrayOutputStream()));

        transport.clearQueries();
        transport.respond("fmt=tar&list=1,2000", tar().message(1, "Inbox", "a").message(2000, "Inbox", "b").bytes());
        Clock later = Clock.fixed(Instant.now().plus(Duration.ofHours(25)), ZoneOffset.UTC);
        new BatchedMailboxExport(transport, retrier(0), settings(journalRoot), later)
                .export(ACCOUNT, new ByteArrayOutputStream(), null, FULL_METADATA, this::uri);

        assertEquals(List.of(FULL_METADATA, "fmt=tar&list=1,2000"), transport.queries());
    }

    @Test
    void partitionKeepsEveryUrlUnderTheLimitAndNeverMakesAnEmptyBatch() throws Exception {
        Set<Integer> ids = new LinkedHashSet<>();
        IntStream.range(100_000_000, 100_003_000).forEach(ids::add);

        List<List<Integer>> batches = BatchedMailboxExport.partition(ids, this::uri);

        assertTrue(batches.size() > 3);
        int total = 0;
        for (List<Integer> batch : batches) {
            assertFalse(batch.isEmpty());
            StringBuilder list = new StringBuilder();
            batch.forEach(id -> list.append(list.length() == 0 ? "" : ",").append(id));
            assertTrue(uri("fmt=tar&list=" + list).toASCIIString().length() <= BatchedMailboxExport.MAX_URL_LENGTH);
            total += batch.size();
        }
        assertEquals(ids.size(), total);
        assertTrue(BatchedMailboxExport.partition(Set.of(), this::uri).isEmpty());
    }


    @Test
    void restorePostsTheStructureFirstThenItemBatchesKeepingEachMetaWithItsContent() throws Exception {
        byte[] archive = tar().folder(2, "/Inbox").folder(257, "/Projects").tag(259, "red")
                .message(1, "Inbox", "a").message(2, "Inbox", "b").contact(3, "Ada")
                .message(4, "Projects", "d").message(5, "Projects", "e")
                .bytes();

        new BatchedMailboxRestore(transport, retrier(0), 2, Long.MAX_VALUE)
                .restore(ACCOUNT, new ByteArrayInputStream(archive), uri("fmt=tar&resolve=skip"));

        List<List<String>> posts = transport.postedEntryNames();
        assertEquals(4, posts.size());
        assertEquals(List.of("Inbox.meta", "Projects.meta", "Tags/red.meta"), posts.get(0));
        assertEquals(List.of("Inbox/0000000001-msg.eml.meta", "Inbox/0000000001-msg.eml",
                "Inbox/0000000002-msg.eml.meta", "Inbox/0000000002-msg.eml"), posts.get(1));
        assertEquals(List.of("Contacts/Ada.meta", "Projects/0000000004-msg.eml.meta",
                "Projects/0000000004-msg.eml"), posts.get(2));
        assertEquals(List.of("Projects/0000000005-msg.eml.meta", "Projects/0000000005-msg.eml"), posts.get(3));
        assertTrue(transport.postedUris().stream().allMatch(u -> u.getQuery().equals("fmt=tar&resolve=skip")));
    }

    @Test
    void restoreRetriesAFailedBatch() throws Exception {
        byte[] archive = tar().folder(2, "/Inbox").message(1, "Inbox", "a").bytes();
        transport.failNextPosts(1);

        new BatchedMailboxRestore(transport, retrier(1))
                .restore(ACCOUNT, new ByteArrayInputStream(archive), uri("fmt=tar&resolve=skip"));

        List<List<String>> posts = transport.postedEntryNames();
        assertEquals(List.of(List.of("Inbox.meta"), List.of("Inbox.meta"),
                List.of("Inbox/0000000001-msg.eml.meta", "Inbox/0000000001-msg.eml")), posts);
    }

    @Test
    void restoreOfAnIncrementalWithoutStructurePostsOnlyItems() throws Exception {
        byte[] archive = tar().message(1, "Inbox", "a").bytes();

        new BatchedMailboxRestore(transport, retrier(0))
                .restore(ACCOUNT, new ByteArrayInputStream(archive), uri("fmt=tar&resolve=skip"));

        assertEquals(List.of(List.of("Inbox/0000000001-msg.eml.meta", "Inbox/0000000001-msg.eml")),
                transport.postedEntryNames());
    }

    @Test
    void restoreAcceptsAnArchiveAssembledFromTheJournal() throws Exception {
        transport.respond(FULL_METADATA, tar().folder(2, "/Inbox").message(1, "Inbox", "h").bytes());
        transport.respond("fmt=tar&list=1", tar().message(1, "Inbox", "full").bytes());
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        export(null, exported);

        new BatchedMailboxRestore(transport, retrier(0))
                .restore(ACCOUNT, new ByteArrayInputStream(exported.toByteArray()), uri("fmt=tar&resolve=skip"));

        assertEquals(List.of(List.of("Inbox.meta"),
                List.of("Inbox/0000000001-msg.eml.meta", "Inbox/0000000001-msg.eml")), transport.postedEntryNames());
    }


    @Test
    void metaParserReadsOnlyTopLevelFields() {
        ItemMeta meta = ItemMeta.parse(("{\"metadata\":\"d2:id\\\"id\\\":5e\",\"nested\":{\"id\":7,\"type\":1},"
                + "\"type\":5,\"id\":260}").getBytes());
        assertEquals(new ItemMeta(260, 5), meta);
        assertNull(ItemMeta.parse("{\"type\":5}".getBytes()));
    }


    private boolean export(Path journal, ByteArrayOutputStream destination) throws IOException {
        return new BatchedMailboxExport(transport, retrier(0), settings(journal), Clock.systemUTC())
                .export(ACCOUNT, destination, null, FULL_METADATA, this::uri);
    }

    private BatchedMailboxExport exporter() {
        return exporter(0);
    }

    private BatchedMailboxExport exporter(int retries) {
        return new BatchedMailboxExport(transport, retrier(retries), settings(null), Clock.systemUTC());
    }

    private static RestTransport.Retrier retrier(int retries) {
        return new RestTransport.Retrier(retries, Duration.ZERO, Logger.getLogger("test"));
    }

    private static MailboxTransferSettings settings(Path journal) {
        return MailboxTransferSettings.defaults().withExportJournalDir(journal);
    }

    private URI uri(String query) throws IOException {
        try {
            return new URI("https", "zimbra.example.com:7071", "/service/home/" + ACCOUNT + "/", query, null);
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static String joined(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int id = from; id < to; id++) {
            out.append(out.length() == 0 ? "" : ",").append(id);
        }
        return out.toString();
    }

    private void assertJournalEmpty() throws IOException {
        try (var files = Files.walk(journalRoot)) {
            assertEquals(List.of(), files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".lock")).toList());
        }
    }

    record FakeResponse(int status, byte[] body, IOException failure) {}

    static final class FakeTransport implements RestTransport {
        private final Map<String, List<FakeResponse>> responses = new ConcurrentHashMap<>();
        private final List<String> queries = new ArrayList<>();
        private final List<byte[]> posted = new ArrayList<>();
        private final List<URI> postedUris = new ArrayList<>();
        private int failPosts;

        void respond(String query, byte[] tar) {
            respondSequence(query, new FakeResponse(200, tar, null));
        }

        void respond(String query, int status) {
            respondSequence(query, new FakeResponse(status, null, null));
        }

        void respondSequence(String query, FakeResponse... sequence) {
            responses.put(query, new ArrayList<>(List.of(sequence)));
        }

        void failNextPosts(int count) {
            failPosts = count;
        }

        List<String> queries() {
            return List.copyOf(queries);
        }

        void clearQueries() {
            queries.clear();
        }

        List<URI> postedUris() {
            return postedUris;
        }

        List<List<String>> postedEntryNames() throws IOException {
            List<List<String>> names = new ArrayList<>();
            for (byte[] tar : posted) {
                names.add(names(TestTars.read(tar)));
            }
            return names;
        }

        @Override
        public InputStream get(URI uri, String description) throws IOException {
            String query = uri.getQuery();
            queries.add(query);
            List<FakeResponse> sequence = responses.get(query);
            if (sequence == null) {
                throw new HttpStatusException("no stub for " + query, 404);
            }
            FakeResponse response = sequence.size() > 1 ? sequence.remove(0) : sequence.get(0);
            if (response.status() == 204) {
                return null;
            }
            if (response.status() != 200) {
                throw new HttpStatusException("HTTP " + response.status(), response.status());
            }
            if (response.failure() != null) {
                throw response.failure();
            }
            return new ByteArrayInputStream(response.body());
        }

        @Override
        public void post(URI uri, HttpRequest.BodyPublisher body, String description) throws IOException {
            byte[] bytes = drain(body);
            posted.add(bytes);
            postedUris.add(uri);
            if (failPosts > 0) {
                failPosts--;
                throw new IOException("connection reset");
            }
        }

        private static byte[] drain(HttpRequest.BodyPublisher publisher) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    byte[] chunk = new byte[item.remaining()];
                    item.get(chunk);
                    out.writeBytes(chunk);
                }

                @Override
                public void onError(Throwable throwable) {
                    done.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    done.complete(null);
                }
            });
            done.join();
            return out.toByteArray();
        }
    }
}
