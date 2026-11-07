package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
        return rotateOldSessions(days, false);
    }

    public List<BackupSession> rotateOldSessions(int days, boolean preserveIncrementalChains) throws IOException {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<BackupSession> old = metadataStore.findSessionsCompletedBefore(cutoff);
        if (preserveIncrementalChains) {
            old = withoutUnexpiredChainMembers(old, cutoff);
        }
        List<BackupSession> removed = new ArrayList<>();
        for (BackupSession session : old) {
            if (remove(session)) {
                removed.add(session);
            }
        }
        metadataStore.vacuum();
        return removed;
    }

    private List<BackupSession> withoutUnexpiredChainMembers(List<BackupSession> old, Instant cutoff)
            throws IOException {
        Set<String> retained = new HashSet<>();
        for (List<BackupSession> chain : incrementalChains(metadataStore.listSessions())) {
            if (!chainExpired(chain, cutoff)) {
                chain.forEach(member -> retained.add(member.sessionId()));
            }
        }
        return old.stream()
                .filter(session -> !retained.contains(session.sessionId()))
                .toList();
    }

    private static List<List<BackupSession>> incrementalChains(List<BackupSession> sessions) {
        List<BackupSession> ordered = sessions.stream()
                .filter(session -> session.type() == BackupType.FULL || session.type() == BackupType.INCREMENTAL)
                .sorted(Comparator.comparing(BackupSession::startedAt).thenComparing(BackupSession::sessionId))
                .toList();
        List<List<BackupSession>> chains = new ArrayList<>();
        List<BackupSession> current = new ArrayList<>();
        for (BackupSession session : ordered) {
            if (startsNewChain(session) && !current.isEmpty()) {
                chains.add(current);
                current = new ArrayList<>();
            }
            current.add(session);
        }
        if (!current.isEmpty()) {
            chains.add(current);
        }
        return chains;
    }

    private static boolean startsNewChain(BackupSession session) {
        return session.type() == BackupType.FULL && session.status() == SessionStatus.FINISHED;
    }

    private static boolean chainExpired(List<BackupSession> chain, Instant cutoff) {
        return chain.stream()
                .map(BackupSession::completedAt)
                .filter(Objects::nonNull)
                .allMatch(completedAt -> completedAt.isBefore(cutoff));
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
