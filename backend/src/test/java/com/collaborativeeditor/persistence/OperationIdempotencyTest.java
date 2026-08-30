package com.collaborativeeditor.persistence;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.IdempotencyLookupResult;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("Operation Idempotency Persistence Tests")
public class OperationIdempotencyTest {

    @Autowired
    private OperationPersistenceService persistenceService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentOperationBatchRepository batchRepository;

    @Autowired
    private DocumentOperationIdRepository operationIdRepository;

    @Autowired
    private DocumentSnapshotRepository snapshotRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private Document testDoc;
    private UUID docId;
    private UUID syncEpoch;

    @BeforeEach
    void setUp() {
        operationIdRepository.deleteAll();
        batchRepository.deleteAll();
        snapshotRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(new User(
            UUID.randomUUID(), "author", "author@test.com", "hash", "Author",
            AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        testDoc = documentRepository.save(new Document(
            docId, testUser, "Idempotency Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now()
        ));
    }

    @Test
    @DisplayName("Unseen client operation ID returns NOT_FOUND")
    void testNotSeen() {
        IdempotencyLookupResult result = persistenceService.checkIdempotency(
            docId, syncEpoch, UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(0, "A")
        );
        assertFalse(result.isDuplicate());
        assertEquals(IdempotencyLookupResult.Status.NOT_FOUND, result.status());
    }

    @Test
    @DisplayName("Duplicate retry returns DUPLICATE status with canonical revision and batch ID")
    void testDuplicateRetryReturnsCanonicalResult() {
        UUID clientId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        InsertOperation insertOp = new InsertOperation(0, "First Edit");

        PersistedCanonicalOperation canonicalOp = new PersistedCanonicalOperation(
            1L, clientId, clientOpId, testUser.getId(), insertOp
        );

        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(canonicalOp), null
        );
        var savedBatch = persistenceService.persistBatch(batch);

        // Check idempotency with same key
        IdempotencyLookupResult result = persistenceService.checkIdempotency(
            docId, syncEpoch, clientId, clientOpId, insertOp
        );

        assertTrue(result.isDuplicate());
        assertEquals(IdempotencyLookupResult.Status.DUPLICATE, result.status());
        assertEquals(1L, result.revision());
        assertEquals(savedBatch.getId(), result.batchId());
        assertNotNull(result.canonicalOperation());
        assertEquals(insertOp, result.canonicalOperation().operation());
    }

    @Test
    @DisplayName("Multiple clients on same document have distinct isolated idempotency tracking")
    void testMultipleClientsIndependentIdempotency() {
        UUID clientA = UUID.randomUUID();
        UUID clientB = UUID.randomUUID();
        UUID sharedOpId = UUID.randomUUID(); // Same operation UUID, different client UUID

        PersistedCanonicalOperation opA = new PersistedCanonicalOperation(
            1L, clientA, sharedOpId, testUser.getId(), new InsertOperation(0, "A")
        );
        PersistedCanonicalOperation opB = new PersistedCanonicalOperation(
            2L, clientB, sharedOpId, testUser.getId(), new InsertOperation(1, "B")
        );

        persistenceService.persistBatch(new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(opA, opB), null
        ));

        IdempotencyLookupResult resA = persistenceService.checkIdempotency(
            docId, syncEpoch, clientA, sharedOpId, opA.operation()
        );
        IdempotencyLookupResult resB = persistenceService.checkIdempotency(
            docId, syncEpoch, clientB, sharedOpId, opB.operation()
        );

        assertTrue(resA.isDuplicate());
        assertEquals(1L, resA.revision());

        assertTrue(resB.isDuplicate());
        assertEquals(2L, resB.revision());
    }
}

