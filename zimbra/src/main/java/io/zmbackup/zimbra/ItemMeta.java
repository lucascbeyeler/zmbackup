package io.zmbackup.zimbra;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The two fields zmbackup needs from a Zimbra export {@code .meta} entry, which holds the item's
 * {@code ItemData} as a JSON object: the item {@code id} and its {@code type} ({@code MailItem.Type}).
 */
record ItemMeta(int id, int type) {

    /**
     * Folder, search folder, tag, flag, mountpoint and smart folder. Zimbra exports these only when the
     * request has no query and no {@code list=}, so they must come from the metadata pass.
     */
    private static final Set<Integer> STRUCTURE_TYPES = Set.of(1, 2, 3, 10, 13, 19);

    /** Maximum size of a {@code .meta} entry zmbackup buffers in memory. */
    static final int MAX_META_BYTES = 16 * 1024 * 1024;

    boolean isStructure() {
        return STRUCTURE_TYPES.contains(type);
    }

    static boolean isMetaEntry(TarEntryGroupReader.Entry entry) {
        return entry.isRegularFile() && entry.name().endsWith(".meta") && entry.size() <= MAX_META_BYTES;
    }

    /** Parses the top-level {@code id} and {@code type} fields, or returns {@code null} if either is missing. */
    static ItemMeta parse(byte[] json) {
        String text = new String(json, StandardCharsets.UTF_8);
        Integer id = null;
        Integer type = null;
        int depth = 0;
        int i = 0;
        String lastKey = null;
        boolean expectingKey = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = endOfString(text, i);
                if (end < 0) {
                    return null;
                }
                if (depth == 1 && expectingKey) {
                    lastKey = text.substring(i + 1, end);
                    expectingKey = false;
                }
                i = end + 1;
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
                expectingKey = c == '{' && depth == 1;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 1) {
                expectingKey = true;
                lastKey = null;
            } else if (c == ':' && depth == 1 && lastKey != null) {
                int start = i + 1;
                while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                int end = start;
                while (end < text.length() && (text.charAt(end) == '-' || Character.isDigit(text.charAt(end)))) {
                    end++;
                }
                if (end > start && ("id".equals(lastKey) || "type".equals(lastKey))) {
                    try {
                        int value = Integer.parseInt(text.substring(start, end));
                        if ("id".equals(lastKey)) {
                            id = value;
                        } else {
                            type = value;
                        }
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                lastKey = null;
            }
            i++;
        }
        return id == null || type == null ? null : new ItemMeta(id, type);
    }

    private static int endOfString(String text, int openQuote) {
        for (int i = openQuote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }
}
