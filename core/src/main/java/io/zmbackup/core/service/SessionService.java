package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Queries and deletion over stored backup sessions, driving {@code zmbackup list} and
 * {@code zmbackup delete}. Mirrors the bash tool's {@code list_sessions} in
 * {@code SessionAction.sh} and {@code delete_one} in {@code DeleteAction.sh}.
 */
public class SessionService {

    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public SessionService(StorageProvider storageProvider, MetadataStore metadataStore) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    /** All stored sessions, most recently started first. */
    public List<BackupSession> listSessions() throws IOException {
        return metadataStore.listSessions().stream()
                .sorted(Comparator.comparing(BackupSession::startedAt).reversed())
                .toList();
    }

    /**
     * Deletes the session with the given ID, mirroring {@code delete_one}: removes its stored
     * content and metadata.
     *
     * @return {@code true} if the session existed and was removed, {@code false} if no session
     *     with that ID was found
     */
    public boolean deleteSession(String sessionId) throws IOException {
        if (metadataStore.findSession(sessionId).isEmpty()) {
            return false;
        }
        storageProvider.deleteSession(sessionId);
        metadataStore.deleteSession(sessionId);
        return true;
    }

    /**
     * Empties the metadata store of every session and account record, mirroring the bash tool's
     * {@code -t}/{@code --truncate} ({@code leeroy_jenkins}) in spirit, but scoped to the database
     * only - the backup files on disk are left untouched. Irreversible, so intended only for
     * resetting a test/non-production installation; driven by {@code zmbackup truncate}.
     *
     * @return the number of sessions removed
     */
    public int truncateDatabase() throws IOException {
        return metadataStore.truncate();
    }
}
