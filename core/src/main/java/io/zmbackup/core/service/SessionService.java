package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class SessionService {

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
