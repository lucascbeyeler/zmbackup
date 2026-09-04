package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlocklistTest {

    @TempDir
    Path tempDir;

    @Test
    void isBlockedMatchesExactLinesFromTheFile() throws IOException {
        Path blockedListFile = tempDir.resolve("blockedlist.conf");
        Files.writeString(blockedListFile, "alice@example.com\nbob@example.com\n");

        FileBlocklist blocklist = new FileBlocklist(blockedListFile);

        assertTrue(blocklist.isBlocked("alice@example.com"));
        assertTrue(blocklist.isBlocked("bob@example.com"));
        assertFalse(blocklist.isBlocked("carol@example.com"));
    }

    @Test
    void ignoresBlankLines() throws IOException {
        Path blockedListFile = tempDir.resolve("blockedlist.conf");
        Files.writeString(blockedListFile, "alice@example.com\n\n   \n");

        FileBlocklist blocklist = new FileBlocklist(blockedListFile);

        assertTrue(blocklist.isBlocked("alice@example.com"));
        assertFalse(blocklist.isBlocked(""));
    }

    @Test
    void missingFileBlocksNothing() throws IOException {
        FileBlocklist blocklist = new FileBlocklist(tempDir.resolve("nonexistent.conf"));

        assertFalse(blocklist.isBlocked("alice@example.com"));
    }

    @Test
    void isBlockedMatchesRegardlessOfCase() throws IOException {
        Path blockedListFile = tempDir.resolve("blockedlist.conf");
        Files.writeString(blockedListFile, "Alice@Example.com\n");

        FileBlocklist blocklist = new FileBlocklist(blockedListFile);

        assertTrue(blocklist.isBlocked("alice@example.com"));
        assertTrue(blocklist.isBlocked("ALICE@EXAMPLE.COM"));
        assertTrue(blocklist.isBlocked("Alice@Example.com"));
    }

    @Test
    void doesNotMatchPartialLines() throws IOException {
        Path blockedListFile = tempDir.resolve("blockedlist.conf");
        Files.writeString(blockedListFile, "alice@example.com\n");

        FileBlocklist blocklist = new FileBlocklist(blockedListFile);

        assertFalse(blocklist.isBlocked("alice@example.co"));
        assertFalse(blocklist.isBlocked("alice"));
    }
}
