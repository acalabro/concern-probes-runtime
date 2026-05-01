package it.cnr.isti.labsedc.concern.probes.buffer;

import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class SqliteBuffer implements OfflineBuffer {

    private static final Logger log = LoggerFactory.getLogger(SqliteBuffer.class);

    private final Connection conn;
    private final Object     lock = new Object();
    private final ConcurrentHashMap<String, Long> peeked = new ConcurrentHashMap<>();

    public SqliteBuffer(String path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS buffered_events (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    probe_id    TEXT    NOT NULL,
                    event_ts    INTEGER NOT NULL,
                    payload_b64 TEXT    NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_probe ON buffered_events(probe_id, id)");
        }
        log.info("SQLite buffer ready at {}", path);
    }

    @Override
    public void enqueue(String probeId, ConcernBaseEvent<?> event) throws Exception {
        byte[] bytes;
        try (var baos = new ByteArrayOutputStream(); var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(event);
            bytes = baos.toByteArray();
        }
        String b64 = Base64.getEncoder().encodeToString(bytes);
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO buffered_events(probe_id,event_ts,payload_b64) VALUES(?,?,?)")) {
                ps.setString(1, probeId);
                ps.setLong(2, event.getTimestamp());
                ps.setString(3, b64);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public ConcernBaseEvent<?> peek(String probeId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,payload_b64 FROM buffered_events WHERE probe_id=? ORDER BY id ASC LIMIT 1")) {
            ps.setString(1, probeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long rowId = rs.getLong("id");
                byte[] bytes = Base64.getDecoder().decode(rs.getString("payload_b64"));
                try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                    ConcernBaseEvent<?> e = (ConcernBaseEvent<?>) ois.readObject();
                    peeked.put(probeId, rowId);
                    return e;
                }
            }
        } catch (Exception e) {
            log.warn("peek failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void ack(String probeId, ConcernBaseEvent<?> event) {
        Long rowId = peeked.remove(probeId);
        if (rowId == null) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM buffered_events WHERE id=?")) {
                ps.setLong(1, rowId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("ack failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public long size(String probeId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM buffered_events WHERE probe_id=?")) {
            ps.setString(1, probeId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (SQLException e) { return 0L; }
    }

    @Override public void close() { try { conn.close(); } catch (SQLException ignored) {} }
}
