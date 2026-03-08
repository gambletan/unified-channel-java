package io.github.gambletan.unifiedchannel.memory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed implementation of {@link MemoryStore}.
 * <p>
 * Uses the built-in JDBC SQLite driver (requires sqlite-jdbc on classpath).
 * Falls back gracefully if SQLite is not available.
 */
public final class SQLiteStore implements MemoryStore {

    private final String dbUrl;

    /**
     * Create a SQLite store at the given database file path.
     *
     * @param dbPath path to the SQLite database file (created if absent)
     */
    public SQLiteStore(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        initTable();
    }

    private void initTable() {
        try (var conn = getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        key TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sender TEXT,
                        timestamp TEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_key ON conversation_history(key)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite store", e);
        }
    }

    @Override
    public List<HistoryEntry> get(String key) {
        var entries = new ArrayList<HistoryEntry>();
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT role, content, sender, timestamp FROM conversation_history WHERE key = ? ORDER BY id")) {
            stmt.setString(1, key);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(new HistoryEntry(
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("sender"),
                            rs.getString("timestamp")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read history", e);
        }
        return entries;
    }

    @Override
    public void append(String key, HistoryEntry entry) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO conversation_history (key, role, content, sender, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, key);
            stmt.setString(2, entry.role());
            stmt.setString(3, entry.content());
            stmt.setString(4, entry.sender());
            stmt.setString(5, entry.timestamp());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to append history", e);
        }
    }

    @Override
    public void trim(String key, int maxEntries) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement("""
                     DELETE FROM conversation_history WHERE key = ? AND id NOT IN (
                         SELECT id FROM conversation_history WHERE key = ? ORDER BY id DESC LIMIT ?
                     )
                     """)) {
            stmt.setString(1, key);
            stmt.setString(2, key);
            stmt.setInt(3, maxEntries);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to trim history", e);
        }
    }

    @Override
    public void clear(String key) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement("DELETE FROM conversation_history WHERE key = ?")) {
            stmt.setString(1, key);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear history", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }
}
