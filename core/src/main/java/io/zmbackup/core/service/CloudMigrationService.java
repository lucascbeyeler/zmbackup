package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.CloudMigrationResult;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CloudMigrationService {

    private static final List<String> SUFFIXES = List.of("ldiff", "tgz");

    private final StorageProvider sourceStorage;
    private final MetadataStore sourceMetadata;
    private final StorageProvider destinationStorage;
    private final MetadataStore destinationMetadata;

    public CloudMigrationService(
            StorageProvider sourceStorage,
            MetadataStore sourceMetadata,
            StorageProvider destinationStorage,
            MetadataStore destinationMetadata) {
        this.sourceStorage = Objects.requireNonNull(sourceStorage, "sourceStorage must not be null");
        this.sourceMetadata = Objects.requireNonNull(sourceMetadata, "sourceMetadata must not be null");
        this.destinationStorage = Objects.requireNonNull(destinationStorage, "destinationStorage must not be null");
        this.destinationMetadata =
                Objects.requireNonNull(destinationMetadata, "destinationMetadata must not be null");
    }

    public CloudMigrationResult migrate() throws IOException {
        int sessionsMigrated = 0;
        int accountsMigrated = 0;
        for (BackupSession session : sourceMetadata.listSessions()) {
            destinationMetadata.save(session);
            sessionsMigrated++;
            accountsMigrated += migrateAccounts(session.sessionId());
        }
        return new CloudMigrationResult(sessionsMigrated, accountsMigrated);
    }

    private int migrateAccounts(String sessionId) throws IOException {
        Set<String> alreadyPresent = destinationMetadata.findAccountsForSession(sessionId).stream()
                .map(BackupAccountRecord::email)
                .collect(Collectors.toSet());
        int migrated = 0;
        for (BackupAccountRecord record : sourceMetadata.findAccountsForSession(sessionId)) {
            if (alreadyPresent.contains(record.email())) {
                continue;
            }
            migrateFiles(sessionId, record.email());
            destinationMetadata.recordAccountBackup(record);
            migrated++;
        }
        return migrated;
    }

    private void migrateFiles(String sessionId, String email) throws IOException {
        for (String suffix : SUFFIXES) {
            if (!sourceStorage.exists(sessionId, email, suffix)) {
                continue;
            }
            try (InputStream in = sourceStorage.openRead(sessionId, email, suffix);
                    OutputStream out = destinationStorage.openWrite(sessionId, email, suffix)) {
                in.transferTo(out);
            }
        }
    }
}
