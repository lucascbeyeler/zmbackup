package io.zmbackup.local;

import io.zmbackup.core.domain.BackupAccountRecord;
import io.zmbackup.core.domain.BackupSession;
import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import io.zmbackup.core.port.MetadataStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JDBC-backed {@link MetadataStore} using SQLite, with DDL identical to the bash tool's
 * {@code sessions.sqlite3} schema so existing backup databases remain readable.
 */
public class SqliteMetadataStore implements MetadataStore {

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

    /**
     * Prefix identifying a SQLite JDBC connection string (e.g. an in-memory or shared-cache URI)
     * rather than a real filesystem path, so permission hardening is skipped for it.
     */
    private static final String SQLITE_URI_PREFIX = "file:";

    private final String jdbcUrl;

    public SqliteMetadataStore(Path databaseFile) throws IOException {
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile;
        if (!databaseFile.toString().startsWith(SQLITE_URI_PREFIX)) {
            hardenDatabaseFile(databaseFile);
        }
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(CREATE_BACKUP_SESSION);
            statement.execute(CREATE_BACKUP_ACCOUNT);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    /**
     * Ensures the database's parent directory and the database file itself are restricted to
     * owner-only access, whether this is the first run (nothing exists yet) or a later one
     * against a database file that already exists.
     */
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
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.sessionId());
            statement.setString(2, toDb(session.startedAt()));
            statement.setString(3, toDb(session.completedAt()));
            statement.setString(4, session.size());
            statement.setString(5, session.type().sessionPrefix());
            statement.setString(6, session.status().dbValue());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Optional<BackupSession> findSession(String sessionId) throws IOException {
        String sql =
                "select sessionID, initial_date, conclusion_date, size, type, status "
                        + "from backup_session where sessionID = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<BackupSession> listSessions() throws IOException {
        String sql = "select sessionID, initial_date, conclusion_date, size, type, status from backup_session";
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            List<BackupSession> sessions = new ArrayList<>();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
            return sessions;
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<BackupSession> findSessionsCompletedBefore(Instant cutoff) throws IOException {
        String sql =
                "select sessionID, initial_date, conclusion_date, size, type, status from backup_session "
                        + "where conclusion_date is not null and conclusion_date < ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
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
        }
    }

    @Override
    public void deleteSession(String sessionId) throws IOException {
        try (Connection connection = connect()) {
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
        }
    }

    @Override
    public int truncate() throws IOException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
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
        }
    }

    @Override
    public void vacuum() throws IOException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("VACUUM");
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void recordAccountBackup(BackupAccountRecord record) throws IOException {
        String sql =
                "insert into backup_account (sessionID, account_size, email, initial_date, conclusion_date) "
                        + "values (?, ?, ?, ?, ?)";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.sessionId());
            statement.setString(2, record.size());
            statement.setString(3, record.email());
            statement.setString(4, toDb(record.startedAt()));
            statement.setString(5, toDb(record.completedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<BackupAccountRecord> findAccountsForSession(String sessionId) throws IOException {
        String sql =
                "select id, sessionID, email, account_size, initial_date, conclusion_date "
                        + "from backup_account where sessionID = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
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
                  and bs.status = ?
                  and (%s)
                """
                        .formatted(prefixClause);
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            statement.setString(2, SessionStatus.FINISHED.dbValue());
            int paramIndex = 3;
            for (String prefix : mailboxPrefixes) {
                statement.setString(paramIndex++, prefix + "%");
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(fromDb(rs.getString("last_backup"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean backedUpSince(String identifier, Instant since) throws IOException {
        String sql = "select 1 from backup_account where email = ? and conclusion_date > ? limit 1";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identifier);
            statement.setString(2, toDb(since));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        // Backup and restore sessions now run accounts through a thread pool (see
        // io.zmbackup.core.service.Parallel), so concurrent connections from the same process can
        // momentarily contend for SQLite's file lock; retry instead of failing immediately with
        // SQLITE_BUSY.
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private static BackupSession mapSession(ResultSet rs) throws SQLException {
        return new BackupSession(
                rs.getString("sessionID"),
                BackupType.fromSessionPrefix(rs.getString("type")),
                SessionStatus.fromDbValue(rs.getString("status")),
                fromDb(rs.getString("initial_date")),
                fromDb(rs.getString("conclusion_date")),
                rs.getString("size"));
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
