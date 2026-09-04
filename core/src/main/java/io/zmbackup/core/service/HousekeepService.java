package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HousekeepService {

    private static final Logger LOG = Logger.getLogger(HousekeepService.class.getName());

    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public HousekeepService(StorageProvider storageProvider, MetadataStore metadataStore) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    public List<BackupSession> rotateOldSessions(int days) throws IOException {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<BackupSession> old = metadataStore.findSessionsCompletedBefore(cutoff);
        List<BackupSession> removed = new ArrayList<>();
        for (BackupSession session : old) {
            if (remove(session)) {
                removed.add(session);
            }
        }
        metadataStore.vacuum();
        return removed;
    }

    public int cleanEmpty() throws IOException {
        return storageProvider.deleteEmptyFiles();
    }

    private boolean remove(BackupSession session) {
        try {
            storageProvider.deleteSession(session.sessionId());
            metadataStore.deleteSession(session.sessionId());
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to remove backup session " + session.sessionId(), e);
            return false;
        }
    }
}
