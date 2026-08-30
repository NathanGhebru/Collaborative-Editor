package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

final class DurableSequencingPostgresProbe {

    private static final Set<String> COUNTABLE_TABLES = Set.of(
            "document_operation_batches",
            "document_operation_ids",
            "document_snapshots"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    DurableSequencingPostgresProbe(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void reset() {
        jdbc.execute("""
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

    TestDocument createDocument(String initialContent) {
        UUID ownerId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID syncEpoch = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO users (
                    id, username, email, password_hash, display_name, account_status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                ownerId,
                "seq_" + ownerId.toString().substring(0, 8),
                ownerId + "@example.test",
                "test-only-password-hash",
                "Sequencing Test Owner");

        jdbc.update("""
                INSERT INTO documents (
                    id, owner_id, title, sync_epoch, current_revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                documentId,
                ownerId,
                "Durable sequencing acceptance",
                syncEpoch);

        jdbc.update("""
                INSERT INTO document_snapshots (
                    id, document_id, sync_epoch, revision, content, content_hash, created_at
                ) VALUES (?, ?, ?, 0, ?, ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                documentId,
                syncEpoch,
                initialContent,
                OperationPersistenceService.calculateSha256(initialContent));

        return new TestDocument(documentId, ownerId, syncEpoch, initialContent);
    }

    boolean pers001SchemaExists() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('document_operation_batches', 'document_operation_ids')
                """, Integer.class);
        return count != null && count == 2;
    }

    long currentRevision(UUID documentId) {
        Long revision = jdbc.queryForObject(
                "SELECT current_revision FROM documents WHERE id = ?",
                Long.class,
                documentId);
        if (revision == null) {
            throw new AssertionError("Document has no durable current revision: " + documentId);
        }
        return revision;
    }

    long countRows(String table, UUID documentId) {
        if (!COUNTABLE_TABLES.contains(table)) {
            throw new IllegalArgumentException("Unsupported persistence table: " + table);
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE document_id = ?",
                Long.class,
                documentId);
        return count == null ? 0 : count;
    }

    OptionalLong identityRevision(DurableSequencingTestAdapter.Submission submission) {
        List<Long> revisions = jdbc.query("""
                SELECT revision
                FROM document_operation_ids
                WHERE document_id = ?
                  AND sync_epoch = ?
                  AND client_id = ?
                  AND client_operation_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getLong("revision"),
                submission.documentId(),
                submission.syncEpoch(),
                submission.clientId(),
                submission.clientOperationId());
        return revisions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(revisions.getFirst());
    }

    List<PersistedCanonicalOperation> loadCanonicalOperations(UUID documentId, UUID syncEpoch) {
        return jdbc.query("""
                SELECT operations
                FROM document_operation_batches
                WHERE document_id = ? AND sync_epoch = ?
                ORDER BY first_revision ASC
                """,
                (resultSet, rowNumber) -> readBatch(resultSet),
                documentId,
                syncEpoch).stream()
                .flatMap(List::stream)
                .sorted((left, right) -> Long.compare(left.revision(), right.revision()))
                .toList();
    }

    private List<PersistedCanonicalOperation> readBatch(ResultSet resultSet) throws SQLException {
        try {
            JsonNode operations = objectMapper.readTree(resultSet.getString("operations")).path("operations");
            List<PersistedCanonicalOperation> parsed = new ArrayList<>();
            for (JsonNode operation : operations) {
                parsed.add(objectMapper.treeToValue(operation, PersistedCanonicalOperation.class));
            }
            return parsed;
        } catch (IOException error) {
            throw new SQLException("Stored canonical operation JSON is unreadable", error);
        }
    }

    record TestDocument(UUID documentId, UUID ownerId, UUID syncEpoch, String initialContent) {
    }
}
