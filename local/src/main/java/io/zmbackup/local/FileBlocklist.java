package io.zmbackup.local;

import io.zmbackup.core.port.Blocklist;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads a one-identifier-per-line blocklist file into memory, mirroring {@code grep -Fxq "$1"
 * "$blockedlist"} in the bash tool's {@code ldap_filter}. A missing file is treated as an empty
 * blocklist, the same as {@code grep} against a nonexistent file failing to match.
 */
public class FileBlocklist implements Blocklist {

    private final Set<String> blocked;

    public FileBlocklist(Path blockedListFile) throws IOException {
        Set<String> loaded = new HashSet<>();
        try {
            for (String line : Files.readAllLines(blockedListFile)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    loaded.add(trimmed);
                }
            }
        } catch (NoSuchFileException e) {
            // Nothing is blocked, mirroring grep against a missing blockedlist.conf.
        }
        this.blocked = Set.copyOf(loaded);
    }

    @Override
    public boolean isBlocked(String identifier) {
        return blocked.contains(identifier);
    }
}
