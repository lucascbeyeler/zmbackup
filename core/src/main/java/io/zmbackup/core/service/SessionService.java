package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionService {

    private static final Logger LOG = Logger.getLogger(SessionService.class.getName());

    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public SessionService(StorageProvider storageProvider, MetadataStore metadataStore) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    public List<BackupSession> listSessions() throws IOException {
        return metadataStore.listSessions().stream()
                .sorted(Comparator.comparing(BackupSession::startedAt).reversed())
                .toList();
    }

    public List<BackupSession> findGhostSessions() throws IOException {
        List<BackupSession> ghosts = new ArrayList<>();
        for (BackupSession session : metadataStore.listSessions()) {
            if (session.completedAt() == null) {
                continue;
            }
            try {
                if (!storageProvider.sessionExists(session.sessionId())) {
                    ghosts.add(session);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to check backup storage for session " + session.sessionId(), e);
            }
        }
        return ghosts;
    }

    public boolean deleteSession(String sessionId) throws IOException {
        if (metadataStore.findSession(sessionId).isEmpty()) {
            return false;
        }
        storageProvider.deleteSession(sessionId);
        metadataStore.deleteSession(sessionId);
        return true;
    }

    public int truncateDatabase() throws IOException {
        return metadataStore.truncate();
    }
}
