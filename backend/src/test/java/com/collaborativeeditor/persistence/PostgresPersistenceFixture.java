package com.collaborativeeditor.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

final class PostgresPersistenceFixture {

    private static final List<String> PERS_TABLES = List.of(
            "document_operation_batches",
            "document_operation_ids"
    );

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    PostgresPersistenceFixture(
            String jdbcUrl,
            String username,
            String password,
            ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper;
    }

    void migrateProductionSchema() {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    void requirePers001Schema() throws SQLException {
        try (Connection connection = connection()) {
            for (String table : PERS_TABLES) {
                if (!tableExists(connection, table)) {
                    throw new AssertionError(
                            "PERS-001 production migration must create table " + table);
                }
            }
        }
    }

    void resetDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        document_operation_ids,
                        document_operation_batches,
                        document_permissions,
                        document_snapshots,
                        refresh_tokens,
                        documents,
                        users
                    CASCADE
                    """);
        }
    }

    void seedOwner(UUID ownerId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (
                    id, username, email, password_hash, display_name, account_status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, ownerId);
            statement.setString(2, "pers_owner_" + ownerId.toString().substring(0, 8));
            statement.setString(3, ownerId + "@example.test");
            statement.setString(4, "test-only-password-hash");
            statement.setString(5, "Persistence Owner");
            statement.executeUpdate();
        }
    }

    void seedDocument(UUID documentId, UUID ownerId, UUID syncEpoch, String title) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO documents (
                    id, owner_id, title, sync_epoch, current_revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, documentId);
            statement.setObject(2, ownerId);
            statement.setString(3, title);
            statement.setObject(4, syncEpoch);
            statement.executeUpdate();
        }
    }

    void replaceDocumentTimeline(UUID documentId, UUID syncEpoch) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE documents
                SET sync_epoch = ?, current_revision = 0, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """)) {
            statement.setObject(1, syncEpoch);
            statement.setObject(2, documentId);
            statement.executeUpdate();
        }
    }

    void commitBatch(
            UUID documentId,
            UUID syncEpoch,
            long expectedPreviousRevision,
            CanonicalBatch batch) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                insertBatch(connection, documentId, syncEpoch, batch);
                insertOperationIdentities(connection, documentId, syncEpoch, batch);

                int updated;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE documents
                        SET current_revision = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND sync_epoch = ? AND current_revision = ?
                        """)) {
                    statement.setLong(1, batch.lastRevision());
                    statement.setObject(2, documentId);
                    statement.setObject(3, syncEpoch);
                    statement.setLong(4, expectedPreviousRevision);
                    updated = statement.executeUpdate();
                }

                if (updated != 1) {
                    throw new SQLException("Conditional document revision fence rejected the batch", "40001");
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void insertBatch(UUID documentId, UUID syncEpoch, CanonicalBatch batch) throws SQLException {
        try (Connection connection = connection()) {
            insertBatch(connection, documentId, syncEpoch, batch);
        }
    }

    void insertSnapshot(
            UUID snapshotId,
            UUID documentId,
            UUID syncEpoch,
            long revision,
            String content) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document_snapshots (
                    id, document_id, sync_epoch, revision, content, content_hash, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, snapshotId);
            statement.setObject(2, documentId);
            statement.setObject(3, syncEpoch);
            statement.setLong(4, revision);
            statement.setString(5, content);
            statement.setString(6, "0".repeat(64));
            statement.executeUpdate();
        }
    }

    List<StoredBatch> loadBatchesAfter(UUID documentId, UUID syncEpoch, long revisionExclusive)
            throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, first_revision, last_revision, operations
                FROM document_operation_batches
                WHERE document_id = ? AND sync_epoch = ? AND last_revision > ?
                ORDER BY first_revision ASC
                """)) {
            statement.setObject(1, documentId);
            statement.setObject(2, syncEpoch);
            statement.setLong(3, revisionExclusive);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredBatch> batches = new ArrayList<>();
                while (resultSet.next()) {
                    batches.add(new StoredBatch(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getLong("first_revision"),
                            resultSet.getLong("last_revision"),
                            objectMapper.readTree(resultSet.getString("operations"))
                    ));
                }
                return batches;
            } catch (java.io.IOException error) {
                throw new SQLException("Stored canonical operation JSON is unreadable", error);
            }
        }
    }

    SnapshotRow loadLatestSnapshot(UUID documentId, UUID syncEpoch) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT sync_epoch, revision, content
                FROM document_snapshots
                WHERE document_id = ? AND sync_epoch = ?
                ORDER BY revision DESC
                LIMIT 1
                """)) {
            statement.setObject(1, documentId);
            statement.setObject(2, syncEpoch);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AssertionError("Expected a compatible snapshot for recovery");
                }
                return new SnapshotRow(
                        resultSet.getObject("sync_epoch", UUID.class),
                        resultSet.getLong("revision"),
                        resultSet.getString("content")
                );
            }
        }
    }

    OptionalLong findIdentityRevision(
            UUID documentId,
            UUID syncEpoch,
            UUID clientId,
            UUID clientOperationId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT revision
                FROM document_operation_ids
                WHERE document_id = ? AND sync_epoch = ?
                  AND client_id = ? AND client_operation_id = ?
                """)) {
            statement.setObject(1, documentId);
            statement.setObject(2, syncEpoch);
            statement.setObject(3, clientId);
            statement.setObject(4, clientOperationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? OptionalLong.of(resultSet.getLong("revision"))
                        : OptionalLong.empty();
            }
        }
    }

    long currentRevision(UUID documentId) throws SQLException {
        return queryLong("SELECT current_revision FROM documents WHERE id = ?", documentId);
    }

    long countRows(String table, UUID documentId) throws SQLException {
        if (!PERS_TABLES.contains(table) && !"document_snapshots".equals(table)) {
            throw new IllegalArgumentException("Unsupported persistence table: " + table);
        }
        return queryLong("SELECT COUNT(*) FROM " + table + " WHERE document_id = ?", documentId);
    }

    void deleteDocument(UUID documentId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM documents WHERE id = ?")) {
            statement.setObject(1, documentId);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?
                )
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private void insertBatch(
            Connection connection,
            UUID documentId,
            UUID syncEpoch,
            CanonicalBatch batch) throws SQLException {
        insertBatch(connection, documentId, syncEpoch, batch, batch.operationCount());
    }

    private void insertBatch(
            Connection connection,
            UUID documentId,
            UUID syncEpoch,
            CanonicalBatch batch,
            int operationCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document_operation_batches (
                    id, document_id, sync_epoch, first_revision, last_revision,
                    operations, operation_count, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, batch.id());
            statement.setObject(2, documentId);
            statement.setObject(3, syncEpoch);
            statement.setLong(4, batch.firstRevision());
            statement.setLong(5, batch.lastRevision());
            statement.setString(6, batch.payload().toString());
            statement.setInt(7, operationCount);
            statement.executeUpdate();
        }
    }

    private void insertOperationIdentities(
            Connection connection,
            UUID documentId,
            UUID syncEpoch,
            CanonicalBatch batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document_operation_ids (
                    document_id, sync_epoch, client_id, client_operation_id,
                    revision, batch_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            for (JsonNode operation : batch.payload().path("operations")) {
                statement.setObject(1, documentId);
                statement.setObject(2, syncEpoch);
                statement.setObject(3, UUID.fromString(operation.path("clientId").asText()));
                statement.setObject(4, UUID.fromString(operation.path("clientOperationId").asText()));
                statement.setLong(5, operation.path("revision").asLong());
                statement.setObject(6, batch.id());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private long queryLong(String sql, UUID documentId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, documentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AssertionError("Query returned no row: " + sql);
                }
                return resultSet.getLong(1);
            }
        }
    }

    record CanonicalBatch(UUID id, long firstRevision, long lastRevision, JsonNode payload) {
        int operationCount() {
            return payload.path("operations").size();
        }
    }

    record StoredBatch(UUID id, long firstRevision, long lastRevision, JsonNode payload) {
    }

    record SnapshotRow(UUID syncEpoch, long revision, String content) {
    }
}
