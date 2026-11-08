package io.zmbackup.zimbra;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Builds and reads small tars shaped like Zimbra's exports. */
final class TestTars {

    record Entry(String name, String group, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    static TestTars tar() {
        return new TestTars();
    }

    TestTars folder(int id, String path) {
        return meta(path.substring(1) + ".meta", "folder", id, 1);
    }

    TestTars tag(int id, String name) {
        return meta("Tags/" + name + ".meta", "tag", id, 3);
    }

    /** A message: its {@code .meta}, then its content. */
    TestTars message(int id, String folder, String content) {
        String name = folder + "/" + String.format("%010d", id) + "-msg.eml";
        meta(name + ".meta", "message", id, 5);
        return file(name, "message", content.getBytes(StandardCharsets.UTF_8));
    }

    /** A contact, which is {@code .meta}-only in a tar export. */
    TestTars contact(int id, String name) {
        return meta("Contacts/" + name + ".meta", "contact", id, 6);
    }

    TestTars meta(String name, String group, int id, int type) {
        String json = "{\"date\":1,\"Path\":\"/x\",\"metadata\":\"d1:f9:\\\"id\\\":999e\",\"type\":" + type
                + ",\"subject\":\"s\",\"id\":" + id + ",\"folder_id\":2}";
        return file(name, group, json.getBytes(StandardCharsets.UTF_8));
    }

    TestTars file(String name, String group, byte[] body) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            writeHeader("././@LongLink", "", 'L', nameBytes.length + 1);
            out.writeBytes(nameBytes);
            out.write(0);
            pad(nameBytes.length + 1);
            name = name.substring(0, 99);
        }
        writeHeader(name, group, '0', body.length);
        out.writeBytes(body);
        pad(body.length);
        return this;
    }

    /** The tar with its end-of-archive marker and Zimbra's record padding. */
    byte[] bytes() {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.writeBytes(out.toByteArray());
        copy.writeBytes(new byte[1024]);
        int record = 10240;
        int padding = (record - copy.size() % record) % record;
        copy.writeBytes(new byte[padding]);
        return copy.toByteArray();
    }

    byte[] tgz() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gz)) {
            gzip.write(bytes());
        }
        return gz.toByteArray();
    }

    static List<Entry> read(byte[] tar) throws IOException {
        return read(new ByteArrayInputStream(tar));
    }

    static List<Entry> readTgz(byte[] tgz) throws IOException {
        return read(new GZIPInputStream(new ByteArrayInputStream(tgz)));
    }

    private static List<Entry> read(InputStream in) throws IOException {
        TarEntryGroupReader reader = new TarEntryGroupReader(in);
        List<Entry> entries = new ArrayList<>();
        for (TarEntryGroupReader.Entry e = reader.next(); e != null; e = reader.next()) {
            entries.add(new Entry(e.name(), e.group(), e.readBody(Integer.MAX_VALUE)));
        }
        return entries;
    }

    static List<String> names(List<Entry> entries) {
        return entries.stream().map(Entry::name).toList();
    }

    private void writeHeader(String name, String group, char type, long size) {
        byte[] header = new byte[512];
        put(header, 0, name, 100);
        put(header, 100, "0000644", 8);
        put(header, 108, "0000000", 8);
        put(header, 116, "0000000", 8);
        put(header, 124, String.format("%011o", size), 12);
        put(header, 136, "00000000000", 12);
        header[156] = (byte) type;
        put(header, 257, "ustar", 6);
        put(header, 263, "00", 2);
        put(header, 297, group, 32);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, String.format("%06o", sum), 7);
        out.writeBytes(header);
    }

    private void pad(long size) {
        out.writeBytes(new byte[(int) (TarEntryGroupReader.padded(size) - size)]);
    }

    private static void put(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }
}
