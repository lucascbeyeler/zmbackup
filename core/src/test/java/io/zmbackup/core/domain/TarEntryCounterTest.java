package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class TarEntryCounterTest {

    private static final int BLOCK_SIZE = 512;

    @Test
    void countsZeroEntriesInAnEmptyArchive() throws IOException {
        assertEquals(0, TarEntryCounter.countNestedFileEntries(tgz()));
    }

    @Test
    void countsOneFileEntryNestedUnderAFolder() throws IOException {
        byte[] tgz = tgz(regularFileEntry("Inbox/0000000257-hello.eml", "hello world"));

        assertEquals(1, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    @Test
    void doesNotCountTopLevelFolderDefinitionEntries() throws IOException {
        // Every mailbox - including a brand new one - already has one top-level "<id>-<name>.meta"
        // entry per default folder (Inbox, Sent, Trash, ...). None of that represents content that
        // could be silently dropped by a restore, so it must never be counted.
        byte[] tgz = tgz(
                regularFileEntry("0000000002-Inbox.meta", "{\"id\":2}"),
                regularFileEntry("0000000003-Trash.meta", "{\"id\":3}"));

        assertEquals(0, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    @Test
    void countsMultipleNestedFileEntriesButNotDirectoriesOrTopLevelEntries() throws IOException {
        byte[] tgz = tgz(
                regularFileEntry("0000000002-Inbox.meta", "{\"id\":2}"),
                directoryEntry("Inbox/"),
                regularFileEntry("Inbox/0000000257-a.eml", "message a"),
                regularFileEntry("Inbox/0000000258-b.eml", "message b"),
                regularFileEntry("Inbox/0000000258-b.eml.meta", "{\"id\":258}"));

        assertEquals(3, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    @Test
    void handlesEntryContentThatIsNotA512ByteMultiple() throws IOException {
        byte[] tgz = tgz(
                regularFileEntry("Inbox/0000000257-a.eml", "x".repeat(513)),
                regularFileEntry("Inbox/0000000258-b.eml", "y"));

        assertEquals(2, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    @Test
    void usesTheRealPathFromAGnuLongNameEntryRatherThanTheTruncatedHeaderName() throws IOException {
        // GNU tar emits a typeflag 'L' entry carrying the full path whenever it would not fit in the
        // header's 100-byte name field - real Zimbra archives hit this often, since the filename
        // embeds the message subject. The short/truncated name in the following header's own name
        // field is a decoy and must not be used once a long name is pending.
        String longName = "Inbox/" + "a".repeat(150) + ".eml";
        byte[] tgz = tgz(
                extensionEntry('L', longName),
                regularFileEntry("truncated-decoy-name.eml", "content"));

        assertEquals(1, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    @Test
    void aGnuLongNameEntryIsNeverCountedByItself() throws IOException {
        byte[] tgz = tgz(extensionEntry('L', "Inbox/" + "a".repeat(150) + ".eml"));

        assertEquals(0, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    @Test
    void aPaxExtendedHeaderEntryIsSkippedAndTheFollowingEntryUsesItsOwnHeaderName() throws IOException {
        // PAX ('x') path overrides are not decoded (standard Linux tar -czf produces GNU long-name
        // entries, not PAX, for an over-long path) - the entry following a PAX header is counted (or
        // not) using only its own short header name.
        byte[] tgz = tgz(
                extensionEntry('x', "30 path=some/pax/override/path.eml\n"),
                regularFileEntry("Inbox/own-short-name.eml", "content"));

        assertEquals(1, TarEntryCounter.countNestedFileEntries(new ByteArrayInputStream(tgz)));
    }

    private static byte[] tgz(byte[]... entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            tar.write(entry);
        }
        tar.write(new byte[BLOCK_SIZE * 2]); // two zero blocks mark end-of-archive

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(gzipped)) {
            gzipOut.write(tar.toByteArray());
        }
        return gzipped.toByteArray();
    }

    private static ByteArrayInputStream tgz() throws IOException {
        return new ByteArrayInputStream(tgz(new byte[0][]));
    }

    private static byte[] regularFileEntry(String name, String content) {
        return tarEntry(name, '0', content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] directoryEntry(String name) {
        return tarEntry(name, '5', new byte[0]);
    }

    private static byte[] extensionEntry(char typeflag, String payload) {
        return tarEntry("extension", typeflag, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] tarEntry(String name, char typeflag, byte[] content) {
        byte[] header = new byte[BLOCK_SIZE];
        writeField(header, 0, 100, name);
        writeField(header, 100, 8, "0000644");
        writeField(header, 108, 8, "0000000");
        writeField(header, 116, 8, "0000000");
        writeOctal(header, 124, 12, content.length);
        writeField(header, 136, 12, "00000000000");
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) typeflag;
        writeField(header, 257, 6, "ustar");
        writeField(header, 263, 2, "00");
        writeChecksum(header);

        int paddedContentSize = content.length % BLOCK_SIZE == 0
                ? content.length
                : content.length + (BLOCK_SIZE - content.length % BLOCK_SIZE);
        byte[] entry = new byte[BLOCK_SIZE + paddedContentSize];
        System.arraycopy(header, 0, entry, 0, BLOCK_SIZE);
        System.arraycopy(content, 0, entry, BLOCK_SIZE, content.length);
        return entry;
    }

    private static void writeField(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, length - 1 - octal.length())) + octal;
        writeField(header, offset, length, padded);
    }

    private static void writeChecksum(byte[] header) {
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        String checksum = String.format("%06o\0 ", sum);
        writeField(header, 148, 8, checksum);
    }
}
