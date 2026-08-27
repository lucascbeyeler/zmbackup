package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.port.MetadataStore;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-only queries over stored backup sessions, driving {@code zmbackup list}. Mirrors the
 * bash tool's {@code list_sessions} in {@code SessionAction.sh}.
 */
public class SessionService {

    private final MetadataStore metadataStore;

    public SessionService(MetadataStore metadataStore) {
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    /** All stored sessions, most recently started first. */
    public List<BackupSession> listSessions() throws IOException {
        return metadataStore.listSessions().stream()
                .sorted(Comparator.comparing(BackupSession::startedAt).reversed())
                .toList();
    }
}
