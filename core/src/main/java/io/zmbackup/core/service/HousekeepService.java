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

/**
 * Housekeeping over stored backup sessions, mirroring the bash tool's {@code delete_old} and
 * {@code clean_empty} functions in {@code DeleteAction.sh}, driven by {@code zmbackup housekeep}.
 */
public class HousekeepService {

    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public HousekeepService(StorageProvider storageProvider, MetadataStore metadataStore) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    /**
     * Deletes every session that completed more than {@code days} days ago, mirroring
     * {@code delete_old}'s {@code conclusion_date < datetime('now','-$ROTATE_TIME day')} cutoff.
     *
     * @param days how many days of backups to keep; sessions completed before this cutoff are removed
     * @return the sessions that were removed
     */
    public List<BackupSession> rotateOldSessions(int days) throws IOException {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<BackupSession> old = metadataStore.findSessionsCompletedBefore(cutoff);
        for (BackupSession session : old) {
            remove(session);
        }
        return old;
    }

    /**
     * Deletes every session with no account records, mirroring {@code clean_empty}'s removal of
     * empty leftovers from an interrupted or failed backup.
     *
     * @return the sessions that were removed
     */
    public List<BackupSession> cleanEmpty() throws IOException {
        List<BackupSession> removed = new ArrayList<>();
        for (BackupSession session : metadataStore.listSessions()) {
            if (metadataStore.findAccountsForSession(session.sessionId()).isEmpty()) {
                remove(session);
                removed.add(session);
            }
        }
        return removed;
    }

    private void remove(BackupSession session) throws IOException {
        storageProvider.deleteSession(session.sessionId());
        metadataStore.deleteSession(session.sessionId());
    }
}
