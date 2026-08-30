package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.sequencing.DurableSequencingPostgresProbe.TestDocument;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.AcceptedOperation;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RecoveredDocument;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RejectedSubmissionException;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RejectionCode;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.Sequencer;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.Submission;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("RT-003 same-epoch reconnect PostgreSQL acceptance")
class SameEpochReconnectDurabilityAcceptanceIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rt003_acceptance")
            .withUsername("rt003")
            .withPassword("rt003");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

    @BeforeEach
    void resetPostgres() {
        database = new DurableSequencingPostgresProbe(jdbcTemplate, objectMapper);
        database.reset();
    }

    @Test
    @DisplayName("B: retry after pre-commit disconnect creates one durable canonical operation")
    void transmittedButNotCommittedRetryCreatesExactlyOneRevision() {
        TestDocument document = database.createDocument("abc");
        Submission submission = submission(document, UUID.randomUUID(), UUID.randomUUID(), 0,
                new InsertOperation(3, "X"));
        Sequencer interruptedSequencer = adapter().openSequencer(document.documentId());
        interruptedSequencer.failNextAfterPersistenceWritesBeforeCommit();

        RejectedSubmissionException failure = assertThrows(
                RejectedSubmissionException.class,
                () -> interruptedSequencer.submit(submission));
        assertEquals(RejectionCode.PERSISTENCE_FAILED, failure.code());
        assertEquals(0L, database.currentRevision(document.documentId()));
        assertEquals(0L, database.countRows("document_operation_batches", document.documentId()));
        assertEquals(0L, database.countRows("document_operation_ids", document.documentId()));
        assertFalse(database.identityRevision(submission).isPresent());

        AcceptedOperation accepted = adapter().openSequencer(document.documentId()).submit(submission);

        assertEquals(1L, accepted.revision());
        assertEquals(submission.clientId(), accepted.clientId());
        assertEquals(submission.clientOperationId(), accepted.clientOperationId());
        assertEquals(1L, database.currentRevision(document.documentId()));
        assertEquals(1L, database.countRows("document_operation_batches", document.documentId()));
        assertEquals(1L, database.countRows("document_operation_ids", document.documentId()));
        assertEquals(1L, database.identityRevision(submission).orElseThrow());
        assertRecovered(document, 1, "abcX");
    }

    @Test
    @DisplayName("C: retry after commit but before observation returns the original canonical revision")
    void committedButUnobservedRetryDoesNotCreateAnotherRevision() {
        TestDocument document = database.createDocument("abc");
        Submission submission = submission(document, UUID.randomUUID(), UUID.randomUUID(), 0,
                new InsertOperation(3, "X"));

        // The first return value models a committed result whose outbound frame was lost.
        AcceptedOperation committedButUnobserved = adapter().openSequencer(document.documentId()).submit(submission);
        AcceptedOperation recoveredByIdenticalRetry = adapter().openSequencer(document.documentId()).submit(submission);

        assertEquals(committedButUnobserved, recoveredByIdenticalRetry);
        assertEquals(1L, committedButUnobserved.revision());
        assertEquals(1L, database.currentRevision(document.documentId()));
        assertEquals(1L, database.countRows("document_operation_batches", document.documentId()));
        assertEquals(1L, database.countRows("document_operation_ids", document.documentId()));
        assertEquals(1L, database.identityRevision(submission).orElseThrow());

        List<PersistedCanonicalOperation> catchUp = database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch());
        assertEquals(1, catchUp.size());
        assertEquals(1L, catchUp.getFirst().revision());
        assertEquals(submission.clientOperationId(), catchUp.getFirst().clientOperationId());
        assertRecovered(document, 1, "abcX");
    }

    @Test
    @DisplayName("E: disconnected remote history reloads contiguously and recovers deterministic content")
    void remoteCatchUpIsGapFreeAndRecoveryMatchesCanonicalState() {
        TestDocument document = database.createDocument("abc");
        Sequencer sequencer = adapter().openSequencer(document.documentId());
        Submission first = submission(document, UUID.randomUUID(), UUID.randomUUID(), 0,
                new InsertOperation(3, "X"));
        Submission second = submission(document, UUID.randomUUID(), UUID.randomUUID(), 1,
                new InsertOperation(4, "Y"));
        Submission third = submission(document, UUID.randomUUID(), UUID.randomUUID(), 2,
                new DeleteOperation(0, 1));

        sequencer.submit(first);
        sequencer.submit(second);
        sequencer.submit(third);

        List<PersistedCanonicalOperation> catchUp = database.loadCanonicalOperations(
                document.documentId(), document.syncEpoch());
        assertEquals(List.of(1L, 2L, 3L), catchUp.stream()
                .map(PersistedCanonicalOperation::revision)
                .toList());
        assertEquals(List.of(
                        first.clientOperationId(),
                        second.clientOperationId(),
                        third.clientOperationId()),
                catchUp.stream().map(PersistedCanonicalOperation::clientOperationId).toList());
        assertEquals(3L, database.currentRevision(document.documentId()));
        assertEquals(3L, database.countRows("document_operation_ids", document.documentId()));
        assertRecovered(document, 3, "bcXY");
    }

    private Submission submission(
            TestDocument document,
            UUID clientId,
            UUID clientOperationId,
            long baseRevision,
            com.collaborativeeditor.ot.model.Operation operation) {
        return new Submission(
                document.documentId(),
                document.syncEpoch(),
                document.ownerId(),
                clientId,
                clientOperationId,
                baseRevision,
                operation);
    }

    private DurableSequencingTestAdapter adapter() {
        Map<Class<?>, DurableSequencingTestAdapter.Factory> factories = new LinkedHashMap<>();
        applicationContext.getBeansOfType(DurableSequencingTestAdapter.Factory.class)
                .values()
                .forEach(factory -> factories.put(factory.getClass(), factory));
        ServiceLoader.load(DurableSequencingTestAdapter.Factory.class)
                .forEach(factory -> factories.put(factory.getClass(), factory));
        if (factories.size() != 1) {
            throw new AssertionError("Expected exactly one durable sequencing test adapter; found " + factories.size());
        }
        return factories.values().iterator().next().create(applicationContext);
    }

    private void assertRecovered(TestDocument document, long revision, String content) {
        RecoveredDocument recovered = adapter().recover(document.documentId());
        assertEquals(document.documentId(), recovered.documentId());
        assertEquals(document.syncEpoch(), recovered.syncEpoch());
        assertEquals(revision, recovered.revision());
        assertEquals(content, recovered.content());
    }
}
