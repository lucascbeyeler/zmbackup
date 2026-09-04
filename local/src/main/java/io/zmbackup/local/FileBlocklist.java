package io.zmbackup.local;

import io.zmbackup.core.port.Blocklist;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class FileBlocklist implements Blocklist {

    private final Set<String> blocked;

    public FileBlocklist(Path blockedListFile) throws IOException {
        Set<String> loaded = new HashSet<>();
        try {
            for (String line : Files.readAllLines(blockedListFile)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    loaded.add(normalize(trimmed));
                }
            }
        } catch (NoSuchFileException e) {
        }
        this.blocked = Set.copyOf(loaded);
    }

    @Override
    public boolean isBlocked(String identifier) {
        return blocked.contains(normalize(identifier));
    }

    private static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }
}
