package io.zmbackup.core.service;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MigrationService {

    private static final Logger LOG = Logger.getLogger(MigrationService.class.getName());

    private static final Pattern SESSION_LINE =
            Pattern.compile("^SESSION: (\\S+) (started on|completed in|failed to move staged data on) .*$");
    private static final Pattern ACCOUNT_LINE = Pattern.compile("^([^:]+):([^:]+):(\\d{2}/\\d{2}/\\d{2})$");
    private static final DateTimeFormatter SESSION_ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter ACCOUNT_LINE_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    private final StorageProvider storageProvider;
    private final MetadataStore metadataStore;

    public MigrationService(StorageProvider storageProvider, MetadataStore metadataStore) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    public int importSessionsText(List<String> sessionsTxtLines) throws IOException {
        TreeSet<String> started = new TreeSet<>();
        TreeSet<String> completed = new TreeSet<>();
        TreeSet<String> failed = new TreeSet<>();
        Map<String, List<String[]>> accountLinesBySession = new LinkedHashMap<>();

        for (String line : sessionsTxtLines) {
            Matcher sessionMatch = SESSION_LINE.matcher(line);
            if (sessionMatch.matches()) {
                String sessionId = sessionMatch.group(1);
                switch (sessionMatch.group(2)) {
                    case "started on" -> started.add(sessionId);
                    case "completed in" -> completed.add(sessionId);
                    case "failed to move staged data on" -> failed.add(sessionId);
                    default -> throw new AssertionError("Unreachable: unmatched group " + sessionMatch.group(2));
                }
                continue;
            }
            Matcher accountMatch = ACCOUNT_LINE.matcher(line);
            if (accountMatch.matches()) {
                accountLinesBySession
                        .computeIfAbsent(accountMatch.group(1), key -> new ArrayList<>())
                        .add(new String[] {accountMatch.group(2), accountMatch.group(3)});
            }
        }

        int imported = 0;
        for (String sessionId : started) {
            BackupType type;
            Instant startedAt;
            try {
                type = BackupType.fromSessionPrefix(sessionId.substring(0, sessionId.indexOf('-')));
                startedAt = parseSessionTimestamp(sessionId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Skipping unparsable session ID '" + sessionId + "' from sessions.txt", e);
                continue;
            }

            SessionStatus status;
            Instant completedAt;
            if (completed.contains(sessionId)) {
                status = SessionStatus.FINISHED;
                completedAt = startedAt;
            } else if (failed.contains(sessionId)) {
                status = SessionStatus.FAILED;
                completedAt = startedAt;
            } else {
                status = SessionStatus.IN_PROGRESS;
                completedAt = null;
            }

            String size = storageProvider.sizeOfSession(sessionId);
            metadataStore.save(new BackupSession(sessionId, type, status, startedAt, completedAt, size));
            imported++;

            Set<String> alreadyRecorded = metadataStore.findAccountsForSession(sessionId).stream()
                    .map(BackupAccountRecord::email)
                    .collect(Collectors.toSet());

            for (String[] accountLine : accountLinesBySession.getOrDefault(sessionId, List.of())) {
                String email = accountLine[0];
                if (alreadyRecorded.contains(email)) {
                    continue;
                }
                Instant accountAt = parseAccountDate(accountLine[1], startedAt);
                String accountSize = storageProvider.sizeOfAccount(sessionId, email);
                metadataStore.recordAccountBackup(
                        new BackupAccountRecord(null, sessionId, email, accountSize, accountAt, accountAt));
            }
        }
        return imported;
    }

    private static Instant parseSessionTimestamp(String sessionId) {
        String timestamp = sessionId.substring(sessionId.indexOf('-') + 1);
        return LocalDateTime.parse(timestamp, SESSION_ID_TIMESTAMP)
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }

    private static Instant parseAccountDate(String date, Instant fallback) {
        try {
            return LocalDate.parse(date, ACCOUNT_LINE_DATE)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
