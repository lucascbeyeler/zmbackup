package io.zmbackup.local;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SqliteMetadataStore implements MetadataStore, Closeable {

    private static final Logger LOG = Logger.getLogger(SqliteMetadataStore.class.getName());

    private static final DateTimeFormatter LEGACY_BASH_TOOL_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CREATE_BACKUP_SESSION =
            """
            create table if not exists backup_session(
              sessionID varchar primary key,
              initial_date timestamp not null,
              conclusion_date timestamp,
              size varchar,
              type varchar not null,
              status varchar not null
            )
            """;

    private static final String CREATE_BACKUP_ACCOUNT =
            """
            create table if not exists backup_account(
              id integer primary key autoincrement,
              sessionID varchar not null,
              account_size varchar not null,
              email varchar not null,
              initial_date timestamp not null,
              conclusion_date timestamp,
              foreign key (sessionID) references backup_session(sessionID)
            )
            """;

    private static final String CREATE_INDEX_BACKUP_ACCOUNT_EMAIL =
            "create index if not exists idx_backup_account_email on backup_account(email)";

    private static final String CREATE_INDEX_BACKUP_ACCOUNT_SESSION_ID =
            "create index if not exists idx_backup_account_sessionID on backup_account(sessionID)";

    private static final String SQLITE_URI_PREFIX = "file:";

    private final ReentrantLock lock = new ReentrantLock();
    private final Connection connection;

    public SqliteMetadataStore(Path databaseFile) throws IOException {
        if (!databaseFile.toString().startsWith(SQLITE_URI_PREFIX)) {
            hardenDatabaseFile(databaseFile);
        }
        try {
            this.connection = openConnection("jdbc:sqlite:" + databaseFile);
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_BACKUP_SESSION);
                statement.execute(CREATE_BACKUP_ACCOUNT);
                statement.execute(CREATE_INDEX_BACKUP_ACCOUNT_EMAIL);
                statement.execute(CREATE_INDEX_BACKUP_ACCOUNT_SESSION_ID);
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private static void hardenDatabaseFile(Path databaseFile) throws IOException {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) {
            PosixFileHardening.createDirectories(parent);
        }
        if (Files.exists(databaseFile)) {
            PosixFileHardening.restrictExistingFile(databaseFile);
        } else {
            PosixFileHardening.createFile(databaseFile);
        }
    }

    @Override
    public void save(BackupSession session) throws IOException {
        String sql =
                """
                insert or replace into backup_session(sessionID, initial_date, conclusion_date, size, type, status)
                values (?, ?, ?, ?, ?, ?)
                """;
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.sessionId());
            statement.setString(2, toDb(session.startedAt()));
            statement.setString(3, toDb(session.completedAt()));
            statement.setString(4, session.size());
            statement.setString(5, session.type().sessionPrefix());
            statement.setString(6, session.status().dbValue());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<BackupSession> findSession(String sessionId) throws IOException {
        String sql =
                "select sessionID, initial_date, conclusion_date, size, type, status "
                        + "from backup_session where sessionID = ?";
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BackupSession> listSessions() throws IOException {
        String sql = "select sessionID, initial_date, conclusion_date, size, type, status from backup_session";
        lock.lock();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            List<BackupSession> sessions = new ArrayList<>();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
            return sessions;
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BackupSession> findSessionsCompletedBefore(Instant cutoff) throws IOException {
        String sql =
                "select sessionID, initial_date, conclusion_date, size, type, status from backup_session "
                        + "where conclusion_date is not null and conclusion_date < ?";
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toDb(cutoff));
            try (ResultSet rs = statement.executeQuery()) {
                List<BackupSession> sessions = new ArrayList<>();
                while (rs.next()) {
                    sessions.add(mapSession(rs));
                }
                return sessions;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteSession(String sessionId) throws IOException {
        lock.lock();
        try {
            try (PreparedStatement deleteAccounts =
                    connection.prepareStatement("delete from backup_account where sessionID = ?")) {
                deleteAccounts.setString(1, sessionId);
                deleteAccounts.executeUpdate();
            }
            try (PreparedStatement deleteSession =
                    connection.prepareStatement("delete from backup_session where sessionID = ?")) {
                deleteSession.setString(1, sessionId);
                deleteSession.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int truncate() throws IOException {
        lock.lock();
        try (Statement statement = connection.createStatement()) {
            int removed;
            try (ResultSet rs = statement.executeQuery("select count(*) from backup_session")) {
                rs.next();
                removed = rs.getInt(1);
            }
            statement.execute("delete from backup_account");
            statement.execute("delete from backup_session");
            statement.execute("VACUUM");
            return removed;
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void vacuum() throws IOException {
        lock.lock();
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM");
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int migrateLegacyRows() throws IOException {
        lock.lock();
        try {
            int converted = normalizeLegacySessionRows();
            converted += normalizeLegacyAccountRows();
            return converted;
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    private int normalizeLegacySessionRows() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select sessionID, initial_date, conclusion_date, type from backup_session")) {
            while (rs.next()) {
                rows.add(new String[] {
                    rs.getString("sessionID"), rs.getString("initial_date"), rs.getString("conclusion_date"),
                    rs.getString("type")
                });
            }
        }

        int converted = 0;
        String updateSql =
                "update backup_session set initial_date = ?, conclusion_date = ?, type = ? where sessionID = ?";
        try (PreparedStatement update = connection.prepareStatement(updateSql)) {
            for (String[] row : rows) {
                String sessionId = row[0];
                String initialDate = row[1];
                String conclusionDate = row[2];
                String type = row[3];
                String normalizedType = normalizeLegacyType(sessionId, type);
                String normalizedInitial = normalizeLegacyTimestamp(sessionId, "initial_date", initialDate);
                String normalizedConclusion = normalizeLegacyTimestamp(sessionId, "conclusion_date", conclusionDate);
                if (normalizedType.equals(type)
                        && normalizedInitial.equals(initialDate)
                        && Objects.equals(normalizedConclusion, conclusionDate)) {
                    continue;
                }
                update.setString(1, normalizedInitial);
                update.setString(2, normalizedConclusion);
                update.setString(3, normalizedType);
                update.setString(4, sessionId);
                update.executeUpdate();
                converted++;
            }
        }
        return converted;
    }

    private int normalizeLegacyAccountRows() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select id, sessionID, initial_date, conclusion_date from backup_account")) {
            while (rs.next()) {
                rows.add(new String[] {
                    rs.getString("id"), rs.getString("sessionID"), rs.getString("initial_date"),
                    rs.getString("conclusion_date")
                });
            }
        }

        int converted = 0;
        String updateSql = "update backup_account set initial_date = ?, conclusion_date = ? where id = ?";
        try (PreparedStatement update = connection.prepareStatement(updateSql)) {
            for (String[] row : rows) {
                String id = row[0];
                String sessionId = row[1];
                String initialDate = row[2];
                String conclusionDate = row[3];
                String normalizedInitial = normalizeLegacyTimestamp(sessionId, "initial_date", initialDate);
                String normalizedConclusion = normalizeLegacyTimestamp(sessionId, "conclusion_date", conclusionDate);
                if (normalizedInitial.equals(initialDate) && Objects.equals(normalizedConclusion, conclusionDate)) {
                    continue;
                }
                update.setString(1, normalizedInitial);
                update.setString(2, normalizedConclusion);
                update.setString(3, id);
                update.executeUpdate();
                converted++;
            }
        }
        return converted;
    }

    private static String normalizeLegacyType(String sessionId, String currentValue) {
        if (isKnownSessionPrefix(currentValue)) {
            return currentValue;
        }
        int dash = sessionId.indexOf('-');
        if (dash > 0) {
            String prefixFromSessionId = sessionId.substring(0, dash);
            if (isKnownSessionPrefix(prefixFromSessionId)) {
                return prefixFromSessionId;
            }
        }
        LOG.log(
                Level.WARNING,
                "Could not derive a known backup type for legacy session '" + sessionId + "' (type column was '"
                        + currentValue + "') - leaving it unchanged; run 'zmbackup list' to see the resulting error.");
        return currentValue;
    }

    private static boolean isKnownSessionPrefix(String value) {
        try {
            BackupType.fromSessionPrefix(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeLegacyTimestamp(String sessionId, String column, String value) {
        if (value == null) {
            return null;
        }
        try {
            Instant.parse(value);
            return value;
        } catch (DateTimeParseException isoUnparsable) {
            try {
                return LocalDateTime.parse(value, LEGACY_BASH_TOOL_DATETIME)
                        .atZone(ZoneOffset.UTC)
                        .toInstant()
                        .toString();
            } catch (DateTimeParseException stillUnparsable) {
                LOG.log(
                        Level.WARNING,
                        "Could not normalize legacy timestamp '" + value + "' in " + column + " for session '"
                                + sessionId + "' - leaving it unchanged; run 'zmbackup list' to see the resulting"
                                + " error.");
                return value;
            }
        }
    }

    @Override
    public void recordAccountBackup(BackupAccountRecord record) throws IOException {
        String sql =
                "insert into backup_account (sessionID, account_size, email, initial_date, conclusion_date) "
                        + "values (?, ?, ?, ?, ?)";
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.sessionId());
            statement.setString(2, record.size());
            statement.setString(3, record.email());
            statement.setString(4, toDb(record.startedAt()));
            statement.setString(5, toDb(record.completedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BackupAccountRecord> findAccountsForSession(String sessionId) throws IOException {
        String sql =
                "select id, sessionID, email, account_size, initial_date, conclusion_date "
                        + "from backup_account where sessionID = ?";
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                List<BackupAccountRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapAccount(rs));
                }
                return records;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Instant> lastSuccessfulBackupTime(String email) throws IOException {
        List<String> mailboxPrefixes = BackupType.mailboxSessionPrefixes();
        String prefixClause = mailboxPrefixes.stream()
                .map(prefix -> "ba.sessionID like ?")
                .collect(Collectors.joining(" or "));
        String sql =
                """
                select max(ba.conclusion_date) as last_backup
                from backup_account ba
                join backup_session bs on ba.sessionID = bs.sessionID
                where ba.email = ?
                  and bs.status != ?
                  and (%s)
                """
                        .formatted(prefixClause);
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            statement.setString(2, SessionStatus.IN_PROGRESS.dbValue());
            int paramIndex = 3;
            for (String prefix : mailboxPrefixes) {
                statement.setString(paramIndex++, prefix + "%");
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(fromDb(rs.getString("last_backup"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean backedUpSince(String identifier, BackupType type, Instant since) throws IOException {
        List<String> conflictingPrefixes = type.conflictingSessionPrefixes();
        String prefixClause = conflictingPrefixes.stream()
                .map(prefix -> "ba.sessionID like ?")
                .collect(Collectors.joining(" or "));
        String sql =
                """
                select 1
                from backup_account ba
                where ba.email = ?
                  and ba.conclusion_date > ?
                  and (%s)
                limit 1
                """
                        .formatted(prefixClause);
        lock.lock();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identifier);
            statement.setString(2, toDb(since));
            int paramIndex = 3;
            for (String prefix : conflictingPrefixes) {
                statement.setString(paramIndex++, prefix + "%");
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean supportsSelfBackup() {
        return true;
    }

    @Override
    public void exportSelfBackup(OutputStream destination) throws IOException {
        Path snapshotDir = PosixFileHardening.createTempDirectory("zmbackup-self-");
        Path snapshot = snapshotDir.resolve("sessions.sqlite3");
        try {
            lock.lock();
            try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, snapshot.toString());
                statement.execute();
            } catch (SQLException e) {
                throw new IOException(e);
            } finally {
                lock.unlock();
            }
            Files.copy(snapshot, destination);
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(snapshotDir);
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException(e);
        } finally {
            lock.unlock();
        }
    }

    private static Connection openConnection(String jdbcUrl) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private static BackupSession mapSession(ResultSet rs) throws SQLException {
        String sessionId = rs.getString("sessionID");
        BackupType type;
        try {
            type = BackupType.fromSessionPrefix(rs.getString("type"));
        } catch (IllegalArgumentException e) {
            throw unreadableLegacyRow(sessionId, "type", rs.getString("type"), e);
        }
        Instant initialDate;
        Instant conclusionDate;
        try {
            initialDate = fromDb(rs.getString("initial_date"));
            conclusionDate = fromDb(rs.getString("conclusion_date"));
        } catch (DateTimeParseException e) {
            throw unreadableLegacyRow(sessionId, "initial_date/conclusion_date", rs.getString("initial_date"), e);
        }
        return new BackupSession(
                sessionId, type, SessionStatus.fromDbValue(rs.getString("status")), initialDate, conclusionDate,
                rs.getString("size"));
    }

    private static SQLException unreadableLegacyRow(String sessionId, String column, String value, Exception cause) {
        return new SQLException(
                "backup_session row '" + sessionId + "' has an unreadable " + column + " column ('" + value
                        + "') - this is very likely a bash-tool SESSION_TYPE=SQLITE3 database that was never"
                        + " normalized by 'zmbackup migrate'; run 'zmbackup migrate' against this workDir to fix it.",
                cause);
    }

    private static BackupAccountRecord mapAccount(ResultSet rs) throws SQLException {
        return new BackupAccountRecord(
                rs.getLong("id"),
                rs.getString("sessionID"),
                rs.getString("email"),
                rs.getString("account_size"),
                fromDb(rs.getString("initial_date")),
                fromDb(rs.getString("conclusion_date")));
    }

    private static String toDb(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant fromDb(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
