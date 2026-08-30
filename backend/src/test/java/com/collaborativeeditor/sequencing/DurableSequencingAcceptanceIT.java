package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.engine.OtEngine;
import com.collaborativeeditor.ot.model.ClientOperation;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.sequencing.DurableSequencingPostgresProbe.TestDocument;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.AcceptedOperation;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.CommitBarrier;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RecoveredDocument;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RejectedSubmissionException;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RejectionCode;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.Sequencer;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.Submission;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("PERS-002 durable sequencing acceptance")
class DurableSequencingAcceptanceIT {

    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper VECTOR_MAPPER = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pers002_acceptance")
            .withUsername("pers002")
            .withPassword("pers002");

    private static JsonNode canonicalVectors;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OperationPersistenceService persistenceService;

    private DurableSequencingPostgresProbe database;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @BeforeAll
    static void loadCanonicalVectors() throws IOException {
        Path[] candidates = new Path[] {
                Paths.get("docs/ot-test-vectors.json"),
                Paths.get("../docs/ot-test-vectors.json"),
                Paths.get("../../docs/ot-test-vectors.json"),
                Paths.get(System.getProperty("user.dir"), "docs/ot-test-vectors.json"),
                Paths.get(System.getProperty("user.dir"), "../docs/ot-test-vectors.json")
        };

        File fixture = null;
        for (Path candidate : candidates) {
            if (candidate.toFile().isFile()) {
                fixture = candidate.toFile();
                break;
            }
        }
        if (fixture == null) {
            throw new IOException("Canonical fixture docs/ot-test-vectors.json must exist");
        }
        canonicalVectors = VECTOR_MAPPER.readTree(fixture);
    }

    @BeforeEach
    void resetPostgres() {
        database = new DurableSequencingPostgresProbe(jdbcTemplate, objectMapper);
        database.reset();
    }

    @Test
    @DisplayName("shared OT oracle and PERS-001 PostgreSQL schema are available")
    void sharedOracleAndPersistencePrerequisitesAreAvailable() {
        assertEquals(1, canonicalVectors.path("version").asInt());
        assertEquals(23, canonicalVectors.path("vectors").size());
        assertNotNull(vector("vec-ins-ins-same-position-tie-a-wins"));
        assertNotNull(vector("vec-ins-del-insert-inside-delete-split"));
        assertTrue(database.pers001SchemaExists());
    }

    @Test
    @DisplayName("first and next operations receive gap-free durable revisions and recover exactly")
    void acceptsFirstOperationAndAssignsNextCanonicalRevision() {
        TestDocument document = database.createDocument("abc");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission first = submission(document, 0, new InsertOperation(3, "X"));
        Submission second = submission(document, 1, new InsertOperation(4, "Y"));

        AcceptedOperation acceptedFirst = sequencer.submit(first);
        AcceptedOperation acceptedSecond = sequencer.submit(second);

        assertAccepted(first, 1, first.operation(), acceptedFirst);
        assertAccepted(second, 2, second.operation(), acceptedSecond);
        assertDurableHistory(document, List.of(acceptedFirst, acceptedSecond));
        assertRecovered(document, 2, "abcXY");
    }

    @Test
    @DisplayName("stale base revision rebases through every intervening canonical operation")
    void rebasesAcrossMultipleCommittedOperations() {
        TestDocument document = database.createDocument("abcdef");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission first = submission(document, 0, new InsertOperation(0, "L"));
        Submission second = submission(document, 1, new DeleteOperation(3, 2));
        Submission stale = submission(document, 0, new InsertOperation(6, "R"));

        AcceptedOperation acceptedFirst = sequencer.submit(first);
        AcceptedOperation acceptedSecond = sequencer.submit(second);
        Operation expectedStale = transformSequentially(stale, List.of(acceptedFirst, acceptedSecond));
        AcceptedOperation acceptedStale = sequencer.submit(stale);

        assertEquals(new InsertOperation(5, "R"), expectedStale);
        assertAccepted(stale, 3, expectedStale, acceptedStale);
        assertDurableHistory(document, List.of(acceptedFirst, acceptedSecond, acceptedStale));
        assertRecovered(document, 3, "LabefR");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "vec-del-ins-delete-before-insert",
            "vec-ins-del-insert-at-delete-start"
    })
    @DisplayName("stale INSERT/DELETE interactions use frozen vector results")
    void persistsFrozenInsertDeleteTransform(String vectorId) throws Exception {
        JsonNode vector = vector(vectorId);
        TestDocument document = database.createDocument(vector.path("initialDocument").asText());
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        ClientOperation operationA = VECTOR_MAPPER.treeToValue(vector.path("opA"), ClientOperation.class);
        ClientOperation operationB = VECTOR_MAPPER.treeToValue(vector.path("opB"), ClientOperation.class);
        Operation expectedB = VECTOR_MAPPER.treeToValue(
                vector.path("expectedTransformedB"), Operation.class);

        AcceptedOperation acceptedA = sequencer.submit(submission(document, operationA));
        AcceptedOperation acceptedB = sequencer.submit(submission(document, operationB));

        assertEquals(1, acceptedA.revision());
        assertEquals(operationA.operation(), acceptedA.canonicalOperation());
        assertAccepted(submission(document, operationB), 2, expectedB, acceptedB);
        assertDurableHistory(document, List.of(acceptedA, acceptedB));
        assertRecovered(document, 2, vector.path("expectedConvergedDocument").asText());
    }

    @Test
    @DisplayName("DELETE rebased over an interior INSERT persists one split GROUP revision")
    void persistsServerGeneratedSplitDeleteGroup() throws Exception {
        JsonNode vector = vector("vec-ins-del-insert-inside-delete-split");
        TestDocument document = database.createDocument(vector.path("initialDocument").asText());
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        ClientOperation insertion = VECTOR_MAPPER.treeToValue(vector.path("opA"), ClientOperation.class);
        ClientOperation deletion = VECTOR_MAPPER.treeToValue(vector.path("opB"), ClientOperation.class);
        GroupOperation expectedGroup = (GroupOperation) VECTOR_MAPPER.treeToValue(
                vector.path("expectedTransformedB"), Operation.class);

        AcceptedOperation acceptedInsert = sequencer.submit(submission(document, insertion));
        AcceptedOperation acceptedDelete = sequencer.submit(submission(document, deletion));

        assertAccepted(submission(document, deletion), 2, expectedGroup, acceptedDelete);
        assertInstanceOf(GroupOperation.class, acceptedDelete.canonicalOperation());
        assertEquals(2, expectedGroup.operations().size());
        assertDurableHistory(document, List.of(acceptedInsert, acceptedDelete));
        assertRecovered(document, 2, vector.path("expectedConvergedDocument").asText());
    }

    @Test
    @DisplayName("epoch mismatch and future revision are rejected without durable mutation")
    void rejectsWrongEpochAndFutureBaseRevision() {
        TestDocument document = database.createDocument("base");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission wrongEpoch = new Submission(
                document.documentId(),
                UUID.randomUUID(),
                document.ownerId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                new InsertOperation(0, "wrong"));
        Submission futureRevision = submission(document, 1, new InsertOperation(0, "future"));

        assertRejected(RejectionCode.EPOCH_MISMATCH, () -> sequencer.submit(wrongEpoch));
        assertRejected(RejectionCode.REVISION_AHEAD, () -> sequencer.submit(futureRevision));

        assertNoDurableOperations(document);
        assertRecovered(document, 0, "base");
    }

    @Test
    @DisplayName("identical retry returns the same result while conflicting identity is rejected")
    void retryIsIdempotentAndConflictingIdentityDoesNotMutateHistory() {
        TestDocument document = database.createDocument("abc");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission original = submission(document, 0, new InsertOperation(3, "X"));

        AcceptedOperation firstResult = sequencer.submit(original);
        AcceptedOperation retryResult = sequencer.submit(original);

        assertEquals(firstResult, retryResult);
        assertEquals(1L, database.identityRevision(original).orElseThrow());
        assertEquals(1L, database.countRows("document_operation_ids", document.documentId()));
        assertEquals(1, database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch()).size());

        Submission conflict = new Submission(
                original.documentId(),
                original.syncEpoch(),
                original.actorUserId(),
                original.clientId(),
                original.clientOperationId(),
                original.baseRevision(),
                new InsertOperation(0, "conflict"));
        assertRejected(RejectionCode.IDENTITY_CONFLICT, () -> sequencer.submit(conflict));

        assertEquals(1L, database.currentRevision(document.documentId()));
        assertEquals(1L, database.countRows("document_operation_ids", document.documentId()));
        assertEquals(1, database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch()).size());
        assertRecovered(document, 1, "abcX");
    }

    @Test
    @DisplayName("failure after transaction writes rolls back every durable acceptance artifact")
    void injectedPreCommitFailureRollsBackAndCanBeRetried() {
        TestDocument document = database.createDocument("abc");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission submission = submission(document, 0, new InsertOperation(3, "X"));
        sequencer.failNextAfterPersistenceWritesBeforeCommit();

        assertRejected(RejectionCode.PERSISTENCE_FAILED, () -> sequencer.submit(submission));

        assertNoDurableOperations(document);
        assertFalse(database.identityRevision(submission).isPresent());
        assertRecovered(document, 0, "abc");

        AcceptedOperation retry = adapter().openSequencer(document.documentId()).submit(submission);
        assertAccepted(submission, 1, submission.operation(), retry);
        assertDurableHistory(document, List.of(retry));
        assertRecovered(document, 1, "abcX");
    }

    @Test
    @DisplayName("acceptance does not return while the PostgreSQL commit is paused")
    void doesNotReturnSuccessBeforeCommit() throws Exception {
        TestDocument document = database.createDocument("abc");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission submission = submission(document, 0, new InsertOperation(3, "X"));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (CommitBarrier barrier = sequencer.pauseNextBeforeDatabaseCommit()) {
            Future<AcceptedOperation> future = executor.submit(() -> sequencer.submit(submission));
            barrier.awaitPaused(CONCURRENCY_TIMEOUT);

            assertFalse(future.isDone(), "Acceptance returned before the commit barrier was released");
            assertNoDurableOperations(document);

            barrier.release();
            AcceptedOperation accepted = future.get(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertAccepted(submission, 1, submission.operation(), accepted);
            assertDurableHistory(document, List.of(accepted));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a competing durable commit fences a provisionally ordered stale sequencer")
    void staleSequencerFenceLeavesOnlyCompetingCanonicalHistory() throws Exception {
        TestDocument document = database.createDocument("abc");
        DurableSequencingTestAdapter adapter = adapter();
        Sequencer staleSequencer = adapter.openSequencer(document.documentId());
        Submission staleSubmission = submission(document, 0, new InsertOperation(3, "S"));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        UUID competingClientId = UUID.randomUUID();
        UUID competingOperationId = UUID.randomUUID();
        PersistedCanonicalOperation competingOperation = new PersistedCanonicalOperation(
                1,
                competingClientId,
                competingOperationId,
                document.ownerId(),
                new InsertOperation(0, "C"));

        try (CommitBarrier barrier = staleSequencer.pauseNextAfterProvisionalOrder()) {
            Future<AcceptedOperation> future = executor.submit(
                    () -> staleSequencer.submit(staleSubmission));
            barrier.awaitPaused(CONCURRENCY_TIMEOUT);

            persistenceService.persistBatch(new CanonicalOperationBatch(
                    document.documentId(),
                    document.syncEpoch(),
                    0,
                    List.of(competingOperation),
                    null));

            barrier.release();
            assertFutureRejected(RejectionCode.STALE_SEQUENCER, future);
        } finally {
            executor.shutdownNow();
        }

        List<PersistedCanonicalOperation> stored = database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch());
        assertEquals(1L, database.currentRevision(document.documentId()));
        assertEquals(1, stored.size());
        assertEquals(competingOperation, stored.getFirst());
        assertEquals(1L, database.countRows("document_operation_ids", document.documentId()));
        assertFalse(database.identityRevision(staleSubmission).isPresent());

        RecoveredDocument recovered = adapter.recover(document.documentId());
        assertEquals(1L, recovered.revision());
        assertEquals("Cabc", recovered.content());
    }

    @Test
    @DisplayName("two same-base submissions race to gap-free revisions and vector-defined convergence")
    void concurrentSamePositionInsertsAreDeterministicAndDurable() throws Exception {
        JsonNode vector = vector("vec-ins-ins-same-position-tie-a-wins");
        TestDocument document = database.createDocument(vector.path("initialDocument").asText());
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        ClientOperation operationA = VECTOR_MAPPER.treeToValue(vector.path("opA"), ClientOperation.class);
        ClientOperation operationB = VECTOR_MAPPER.treeToValue(vector.path("opB"), ClientOperation.class);
        Submission submissionA = submission(document, operationA);
        Submission submissionB = submission(document, operationB);
        Operation expectedA = VECTOR_MAPPER.treeToValue(
                vector.path("expectedTransformedA"), Operation.class);
        Operation expectedB = VECTOR_MAPPER.treeToValue(
                vector.path("expectedTransformedB"), Operation.class);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        AcceptedOperation acceptedA;
        AcceptedOperation acceptedB;
        try {
            Future<AcceptedOperation> futureA = executor.submit(
                    () -> submitAtBarrier(sequencer, submissionA, ready, start));
            Future<AcceptedOperation> futureB = executor.submit(
                    () -> submitAtBarrier(sequencer, submissionB, ready, start));

            assertTrue(ready.await(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "Both submissions must reach the start barrier");
            start.countDown();
            acceptedA = futureA.get(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            acceptedB = futureB.get(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(Set.of(1L, 2L), Set.of(acceptedA.revision(), acceptedB.revision()));
        if (acceptedA.revision() == 1) {
            assertEquals(operationA.operation(), acceptedA.canonicalOperation());
            assertEquals(expectedB, acceptedB.canonicalOperation());
        } else {
            assertEquals(operationB.operation(), acceptedB.canonicalOperation());
            assertEquals(expectedA, acceptedA.canonicalOperation());
        }

        List<AcceptedOperation> acceptedInRevisionOrder = new ArrayList<>(List.of(acceptedA, acceptedB));
        acceptedInRevisionOrder.sort(Comparator.comparingLong(AcceptedOperation::revision));
        assertDurableHistory(document, acceptedInRevisionOrder);
        assertEquals(2L, database.countRows("document_operation_ids", document.documentId()));
        assertRecovered(document, 2, vector.path("expectedConvergedDocument").asText());
    }

    private DurableSequencingTestAdapter adapter() {
        Map<Class<?>, DurableSequencingTestAdapter.Factory> factories = new LinkedHashMap<>();
        applicationContext.getBeansOfType(DurableSequencingTestAdapter.Factory.class)
                .values()
                .forEach(factory -> factories.put(factory.getClass(), factory));
        ServiceLoader.load(DurableSequencingTestAdapter.Factory.class)
                .forEach(factory -> factories.put(factory.getClass(), factory));

        if (factories.size() != 1) {
            throw new AssertionError("PERS-002 integration must provide exactly one "
                    + DurableSequencingTestAdapter.Factory.class.getName()
                    + " as a Spring test bean or ServiceLoader provider; found " + factories.size());
        }
        return factories.values().iterator().next().create(applicationContext);
    }

    private JsonNode vector(String id) {
        for (JsonNode vector : canonicalVectors.path("vectors")) {
            if (id.equals(vector.path("id").asText())) {
                return vector;
            }
        }
        throw new AssertionError("Missing canonical OT vector: " + id);
    }

    private Submission submission(TestDocument document, long baseRevision, Operation operation) {
        return new Submission(
                document.documentId(),
                document.syncEpoch(),
                document.ownerId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                baseRevision,
                operation);
    }

    private Submission submission(TestDocument document, ClientOperation operation) {
        return new Submission(
                document.documentId(),
                document.syncEpoch(),
                document.ownerId(),
                UUID.fromString(operation.clientId()),
                UUID.fromString(operation.clientOperationId()),
                operation.baseRevision(),
                operation.operation());
    }

    private Operation transformSequentially(
            Submission incoming,
            List<AcceptedOperation> interveningOperations) {
        Operation transformed = incoming.operation();
        for (AcceptedOperation accepted : interveningOperations) {
            ClientOperation candidate = ClientOperation.of(
                    incoming.clientId(),
                    incoming.clientOperationId(),
                    incoming.baseRevision(),
                    transformed);
            ClientOperation canonical = ClientOperation.of(
                    accepted.clientId(),
                    accepted.clientOperationId(),
                    accepted.revision() - 1,
                    accepted.canonicalOperation());
            transformed = OtEngine.transform(candidate, canonical);
        }
        return transformed;
    }

    private void assertAccepted(
            Submission submission,
            long expectedRevision,
            Operation expectedOperation,
            AcceptedOperation actual) {
        assertEquals(submission.documentId(), actual.documentId());
        assertEquals(submission.syncEpoch(), actual.syncEpoch());
        assertEquals(submission.clientId(), actual.clientId());
        assertEquals(submission.clientOperationId(), actual.clientOperationId());
        assertEquals(expectedRevision, actual.revision());
        assertEquals(expectedOperation, actual.canonicalOperation());
    }

    private void assertDurableHistory(
            TestDocument document,
            List<AcceptedOperation> expectedOperations) {
        List<PersistedCanonicalOperation> stored = database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch());
        assertEquals(expectedOperations.size(), stored.size());
        assertEquals(expectedOperations.size(), database.countRows(
                "document_operation_ids", document.documentId()));
        assertEquals(expectedOperations.size(), database.currentRevision(document.documentId()));

        for (int index = 0; index < expectedOperations.size(); index++) {
            AcceptedOperation expected = expectedOperations.get(index);
            PersistedCanonicalOperation actual = stored.get(index);
            assertEquals(index + 1L, actual.revision());
            assertEquals(expected.revision(), actual.revision());
            assertEquals(expected.clientId(), actual.clientId());
            assertEquals(expected.clientOperationId(), actual.clientOperationId());
            assertEquals(expected.canonicalOperation(), actual.operation());
        }
    }

    private void assertNoDurableOperations(TestDocument document) {
        assertEquals(0L, database.currentRevision(document.documentId()));
        assertEquals(0L, database.countRows("document_operation_batches", document.documentId()));
        assertEquals(0L, database.countRows("document_operation_ids", document.documentId()));
        assertEquals(1L, database.countRows("document_snapshots", document.documentId()),
                "Rejected or rolled-back acceptance must not leave a partial snapshot");
        assertTrue(database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch()).isEmpty());
    }

    private void assertRecovered(TestDocument document, long revision, String content) {
        RecoveredDocument recovered = adapter().recover(document.documentId());
        assertEquals(document.documentId(), recovered.documentId());
        assertEquals(document.syncEpoch(), recovered.syncEpoch());
        assertEquals(revision, recovered.revision());
        assertEquals(content, recovered.content());

        String replayed = document.initialContent();
        for (PersistedCanonicalOperation operation : database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch())) {
            replayed = DocumentApplier.apply(replayed, operation.operation());
        }
        assertEquals(content, replayed);
    }

    private RejectedSubmissionException assertRejected(
            RejectionCode expectedCode,
            SubmissionAction action) {
        RejectedSubmissionException error = assertThrows(
                RejectedSubmissionException.class,
                action::run);
        assertEquals(expectedCode, error.code());
        return error;
    }

    private void assertFutureRejected(
            RejectionCode expectedCode,
            Future<AcceptedOperation> future) {
        ExecutionException error = assertThrows(
                ExecutionException.class,
                () -> future.get(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        RejectedSubmissionException rejection = assertInstanceOf(
                RejectedSubmissionException.class,
                error.getCause());
        assertEquals(expectedCode, rejection.code());
    }

    private AcceptedOperation submitAtBarrier(
            Sequencer sequencer,
            Submission submission,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent submission start barrier timed out");
        }
        return sequencer.submit(submission);
    }

    @FunctionalInterface
    private interface SubmissionAction {
        void run();
    }
}
