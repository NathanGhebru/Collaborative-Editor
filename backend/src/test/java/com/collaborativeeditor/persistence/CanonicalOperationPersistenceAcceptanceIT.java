package com.collaborativeeditor.persistence;

import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.persistence.PostgresPersistenceFixture.CanonicalBatch;
import com.collaborativeeditor.persistence.PostgresPersistenceFixture.SnapshotRow;
import com.collaborativeeditor.persistence.PostgresPersistenceFixture.StoredBatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@DisplayName("PERS-001 canonical operation persistence acceptance")
class CanonicalOperationPersistenceAcceptanceIT {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DOCUMENT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EPOCH_A = UUID.fromString("e1111111-1111-1111-1111-111111111111");
    private static final UUID EPOCH_B = UUID.fromString("e2222222-2222-2222-2222-222222222222");
    private static final UUID EPOCH_C = UUID.fromString("e3333333-3333-3333-3333-333333333333");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pers001_acceptance")
            .withUsername("pers001")
            .withPassword("pers001");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static PostgresPersistenceFixture database;
    private static JsonNode history;
    private static List<CanonicalBatch> batches;

    @BeforeAll
    static void initialize() throws IOException {
        history = loadHistoryFixture();
        batches = readBatches(history);
        database = new PostgresPersistenceFixture(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                OBJECT_MAPPER
        );
        database.migrateProductionSchema();
    }

    @Test
    @DisplayName("canonical fixture preserves INSERT, DELETE, NO_OP, GROUP, BMP, and emoji semantics")
    void canonicalFixtureUsesExistingOtTypesWithoutSemanticCorruption() throws Exception {
        String document = history.path("initialDocument").asText();

        for (CanonicalBatch batch : batches) {
            for (JsonNode canonicalOperation : batch.payload().path("operations")) {
                document = DocumentApplier.apply(document, deserializeOperation(canonicalOperation));
            }
        }

        assertEquals("😀é", document);
        assertEquals(history.path("expectedFinalDocument").asText(), document);
        assertEquals(List.of("INSERT", "DELETE", "NO_OP", "GROUP"),
                operationKinds(batches.getFirst().payload()));
    }

    @Nested
    @DisplayName("against production Flyway migrations on PostgreSQL")
    class PostgreSqlContract {

        @BeforeEach
        void resetAndSeed() throws SQLException {
            database.requirePers001Schema();
            database.resetDatabase();
            database.seedOwner(OWNER_ID);
            database.seedDocument(DOCUMENT_A, OWNER_ID, EPOCH_A, "Persistence document A");
        }

        @Test
        @DisplayName("canonical batches round-trip exactly and advance the durable revision")
        void canonicalBatchesRoundTripExactly() throws SQLException {
            commitCompleteHistory(DOCUMENT_A, EPOCH_A);

            List<StoredBatch> stored = database.loadBatchesAfter(DOCUMENT_A, EPOCH_A, 0);

            assertEquals(2, stored.size());
            assertEquals(batches.get(0).payload(), stored.get(0).payload());
            assertEquals(batches.get(1).payload(), stored.get(1).payload());
            assertEquals(List.of(1L, 5L), stored.stream().map(StoredBatch::firstRevision).toList());
            assertEquals(List.of(4L, 6L), stored.stream().map(StoredBatch::lastRevision).toList());
            assertEquals(6L, database.currentRevision(DOCUMENT_A));
        }

        @Test
        @DisplayName("revision-range loading is deterministic after a fresh database connection")
        void revisionRangeLoadsStrictlyInCanonicalOrder() throws SQLException {
            commitCompleteHistory(DOCUMENT_A, EPOCH_A);

            List<Long> loadedRevisions = database.loadBatchesAfter(DOCUMENT_A, EPOCH_A, 2).stream()
                    .flatMap(batch -> operations(batch.payload()).stream())
                    .map(operation -> operation.path("revision").asLong())
                    .filter(revision -> revision > 2)
                    .toList();

            assertEquals(List.of(3L, 4L, 5L, 6L), loadedRevisions);
            assertEquals(loadedRevisions, database.loadBatchesAfter(DOCUMENT_A, EPOCH_A, 2).stream()
                    .flatMap(batch -> operations(batch.payload()).stream())
                    .map(operation -> operation.path("revision").asLong())
                    .filter(revision -> revision > 2)
                    .toList());
        }

        @Test
        @DisplayName("snapshot plus later canonical log recovers exact content and revision")
        void snapshotPlusOperationLogRecoversExactState() throws Exception {
            database.commitBatch(DOCUMENT_A, EPOCH_A, 0, batches.get(0));
            database.insertSnapshot(
                    UUID.fromString("a4444444-4444-4444-4444-444444444444"),
                    DOCUMENT_A,
                    EPOCH_A,
                    4,
                    history.path("expectedAfterRevision4").asText()
            );
            database.commitBatch(DOCUMENT_A, EPOCH_A, 4, batches.get(1));

            SnapshotRow snapshot = database.loadLatestSnapshot(DOCUMENT_A, EPOCH_A);
            String recovered = snapshot.content();
            long recoveredRevision = snapshot.revision();
            for (StoredBatch batch : database.loadBatchesAfter(
                    DOCUMENT_A, EPOCH_A, snapshot.revision())) {
                for (JsonNode canonicalOperation : operations(batch.payload())) {
                    if (canonicalOperation.path("revision").asLong() > snapshot.revision()) {
                        recovered = DocumentApplier.apply(
                                recovered,
                                deserializeOperation(canonicalOperation)
                        );
                        recoveredRevision = canonicalOperation.path("revision").asLong();
                    }
                }
            }

            assertEquals(EPOCH_A, snapshot.syncEpoch());
            assertEquals(6L, recoveredRevision);
            assertEquals(database.currentRevision(DOCUMENT_A), recoveredRevision);
            assertEquals(history.path("expectedFinalDocument").asText(), recovered);
            assertEquals(2L, database.countRows("document_operation_batches", DOCUMENT_A));
        }

        @Test
        @DisplayName("operation identity is unique while exact retry lookup preserves the original revision")
        void idempotencyIdentityRejectsConflictAndPreservesOriginal() throws SQLException {
            CanonicalBatch firstBatch = batches.getFirst();
            database.commitBatch(DOCUMENT_A, EPOCH_A, 0, firstBatch);
            JsonNode original = operations(firstBatch.payload()).getFirst();
            UUID clientId = UUID.fromString(original.path("clientId").asText());
            UUID operationId = UUID.fromString(original.path("clientOperationId").asText());

            OptionalLong retryRevision = database.findIdentityRevision(
                    DOCUMENT_A, EPOCH_A, clientId, operationId);
            assertTrue(retryRevision.isPresent());
            assertEquals(1L, retryRevision.getAsLong());

            CanonicalBatch conflicting = conflictingIdentityBatch(original);
            SQLException conflict = assertThrows(SQLException.class,
                    () -> database.commitBatch(DOCUMENT_A, EPOCH_A, 4, conflicting));

            assertEquals("23505", conflict.getSQLState());
            assertEquals(4L, database.currentRevision(DOCUMENT_A));
            assertEquals(1L, database.countRows("document_operation_batches", DOCUMENT_A));
            assertEquals(4L, database.countRows("document_operation_ids", DOCUMENT_A));
            assertEquals(1L, database.findIdentityRevision(
                    DOCUMENT_A, EPOCH_A, clientId, operationId).orElseThrow());
        }

        @Test
        @DisplayName("stale revision fencing rolls back batch and identity rows")
        void staleRevisionFenceRollsBackEveryWrite() throws SQLException {
            database.commitBatch(DOCUMENT_A, EPOCH_A, 0, batches.getFirst());

            SQLException stale = assertThrows(SQLException.class,
                    () -> database.commitBatch(DOCUMENT_A, EPOCH_A, 0, batches.get(1)));

            assertEquals("40001", stale.getSQLState());
            assertEquals(4L, database.currentRevision(DOCUMENT_A));
            assertEquals(1L, database.countRows("document_operation_batches", DOCUMENT_A));
            assertEquals(4L, database.countRows("document_operation_ids", DOCUMENT_A));
            JsonNode revisionFive = operations(batches.get(1).payload()).getFirst();
            assertFalse(database.findIdentityRevision(
                    DOCUMENT_A,
                    EPOCH_A,
                    UUID.fromString(revisionFive.path("clientId").asText()),
                    UUID.fromString(revisionFive.path("clientOperationId").asText())
            ).isPresent());
        }

        @Test
        @DisplayName("documented batch range and positive operation-count checks reject malformed rows")
        void databaseConstraintsRejectMalformedBatches() {
            CanonicalBatch firstBatch = batches.getFirst();
            CanonicalBatch reversedRange = new CanonicalBatch(
                    UUID.fromString("f1111111-1111-1111-1111-111111111111"),
                    4,
                    1,
                    firstBatch.payload()
            );
            CanonicalBatch empty = new CanonicalBatch(
                    UUID.fromString("f2222222-2222-2222-2222-222222222222"),
                    1,
                    1,
                    OBJECT_MAPPER.createObjectNode().set(
                            "operations", OBJECT_MAPPER.createArrayNode())
            );

            assertSqlState("23514", () -> database.insertBatch(DOCUMENT_A, EPOCH_A, reversedRange));
            assertSqlState("23514", () -> database.insertBatch(DOCUMENT_A, EPOCH_A, empty));
        }

        @Test
        @DisplayName("batch boundaries are unique within one document epoch")
        void batchBoundaryIndexesRejectDuplicateFirstOrLastRevision() throws SQLException {
            CanonicalBatch firstBatch = batches.getFirst();
            database.insertBatch(DOCUMENT_A, EPOCH_A, firstBatch);

            CanonicalBatch duplicateFirst = new CanonicalBatch(
                    UUID.fromString("f4444444-4444-4444-4444-444444444444"),
                    firstBatch.firstRevision(),
                    firstBatch.lastRevision() + 1,
                    firstBatch.payload()
            );
            CanonicalBatch duplicateLast = new CanonicalBatch(
                    UUID.fromString("f5555555-5555-5555-5555-555555555555"),
                    firstBatch.firstRevision() + 1,
                    firstBatch.lastRevision(),
                    firstBatch.payload()
            );

            assertSqlState("23505", () -> database.insertBatch(DOCUMENT_A, EPOCH_A, duplicateFirst));
            assertSqlState("23505", () -> database.insertBatch(DOCUMENT_A, EPOCH_A, duplicateLast));
        }

        @Test
        @DisplayName("documents and epochs isolate identities, batches, and revision-zero snapshots")
        void documentAndEpochScopesRemainIndependent() throws SQLException {
            database.commitBatch(DOCUMENT_A, EPOCH_A, 0, batches.getFirst());
            database.insertSnapshot(
                    UUID.fromString("a1111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    DOCUMENT_A,
                    EPOCH_A,
                    0,
                    history.path("initialDocument").asText()
            );

            database.seedDocument(DOCUMENT_B, OWNER_ID, EPOCH_C, "Persistence document B");
            CanonicalBatch documentBBatch = withId(
                    batches.getFirst(),
                    UUID.fromString("b3333333-3333-3333-3333-333333333333")
            );
            database.commitBatch(DOCUMENT_B, EPOCH_C, 0, documentBBatch);

            database.replaceDocumentTimeline(DOCUMENT_A, EPOCH_B);
            database.insertSnapshot(
                    UUID.fromString("a2222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    DOCUMENT_A,
                    EPOCH_B,
                    0,
                    history.path("initialDocument").asText()
            );
            CanonicalBatch newEpochBatch = withId(
                    batches.getFirst(),
                    UUID.fromString("b4444444-4444-4444-4444-444444444444")
            );
            database.commitBatch(DOCUMENT_A, EPOCH_B, 0, newEpochBatch);

            JsonNode firstOperation = operations(batches.getFirst().payload()).getFirst();
            UUID clientId = UUID.fromString(firstOperation.path("clientId").asText());
            UUID operationId = UUID.fromString(firstOperation.path("clientOperationId").asText());
            assertEquals(1L, database.findIdentityRevision(
                    DOCUMENT_A, EPOCH_A, clientId, operationId).orElseThrow());
            assertEquals(1L, database.findIdentityRevision(
                    DOCUMENT_A, EPOCH_B, clientId, operationId).orElseThrow());
            assertEquals(1L, database.findIdentityRevision(
                    DOCUMENT_B, EPOCH_C, clientId, operationId).orElseThrow());
            assertEquals(2L, database.countRows("document_operation_batches", DOCUMENT_A));
            assertEquals(2L, database.countRows("document_snapshots", DOCUMENT_A));
        }

        @Test
        @DisplayName("hard document deletion cascades persistence rows without affecting another document")
        void hardDeleteCascadesOnlyTheTargetDocument() throws SQLException {
            database.commitBatch(DOCUMENT_A, EPOCH_A, 0, batches.getFirst());
            database.insertSnapshot(
                    UUID.fromString("a3333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    DOCUMENT_A,
                    EPOCH_A,
                    0,
                    history.path("initialDocument").asText()
            );
            database.seedDocument(DOCUMENT_B, OWNER_ID, EPOCH_C, "Persistence document B");
            database.commitBatch(
                    DOCUMENT_B,
                    EPOCH_C,
                    0,
                    withId(batches.getFirst(), UUID.fromString("b5555555-5555-5555-5555-555555555555"))
            );

            database.deleteDocument(DOCUMENT_A);

            assertEquals(0L, database.countRows("document_operation_batches", DOCUMENT_A));
            assertEquals(0L, database.countRows("document_operation_ids", DOCUMENT_A));
            assertEquals(0L, database.countRows("document_snapshots", DOCUMENT_A));
            assertEquals(1L, database.countRows("document_operation_batches", DOCUMENT_B));
            assertEquals(4L, database.countRows("document_operation_ids", DOCUMENT_B));
        }
    }

    private static void commitCompleteHistory(UUID documentId, UUID epoch) throws SQLException {
        database.commitBatch(documentId, epoch, 0, batches.get(0));
        database.commitBatch(documentId, epoch, 4, batches.get(1));
    }

    private static Operation deserializeOperation(JsonNode canonicalOperation) throws IOException {
        ObjectNode operation = canonicalOperation.deepCopy();
        operation.remove(List.of("revision", "clientId", "clientOperationId", "actorUserId"));
        return OBJECT_MAPPER.treeToValue(operation, Operation.class);
    }

    private static List<String> operationKinds(JsonNode payload) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode operation : payload.path("operations")) {
            kinds.add(operation.path("kind").asText());
        }
        return kinds;
    }

    private static List<JsonNode> operations(JsonNode payload) {
        List<JsonNode> operations = new ArrayList<>();
        payload.path("operations").forEach(operations::add);
        return operations;
    }

    private static CanonicalBatch conflictingIdentityBatch(JsonNode originalIdentity) {
        ObjectNode operation = OBJECT_MAPPER.createObjectNode();
        operation.put("revision", 5);
        operation.put("clientId", originalIdentity.path("clientId").asText());
        operation.put("clientOperationId", originalIdentity.path("clientOperationId").asText());
        operation.put("actorUserId", OWNER_ID.toString());
        operation.put("kind", "INSERT");
        operation.put("position", 0);
        operation.put("text", "conflicting reuse");

        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.set("operations", OBJECT_MAPPER.createArrayNode().add(operation));
        return new CanonicalBatch(
                UUID.fromString("f3333333-3333-3333-3333-333333333333"),
                5,
                5,
                payload
        );
    }

    private static CanonicalBatch withId(CanonicalBatch batch, UUID id) {
        return new CanonicalBatch(id, batch.firstRevision(), batch.lastRevision(), batch.payload());
    }

    private static void assertSqlState(String expectedSqlState, SqlAction action) {
        SQLException error = assertThrows(SQLException.class, action::run);
        assertEquals(expectedSqlState, error.getSQLState());
    }

    private static JsonNode loadHistoryFixture() throws IOException {
        try (InputStream input = CanonicalOperationPersistenceAcceptanceIT.class
                .getResourceAsStream("/persistence/canonical-operation-history.json")) {
            if (input == null) {
                throw new IOException("Missing canonical persistence history fixture");
            }
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static List<CanonicalBatch> readBatches(JsonNode fixture) {
        List<CanonicalBatch> parsed = new ArrayList<>();
        for (JsonNode batch : fixture.path("batches")) {
            parsed.add(new CanonicalBatch(
                    UUID.fromString(batch.path("id").asText()),
                    batch.path("firstRevision").asLong(),
                    batch.path("lastRevision").asLong(),
                    batch.path("payload")
            ));
        }
        return parsed;
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
