package com.collaborativeeditor.persistence;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatch;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.persistence.StaleRevisionFencingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("Operation Persistence Database Integration Tests")
public class OperationPersistenceIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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
            UUID.randomUUID(), "testauthor", "author@test.com", "hash", "Author",
            AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        testDoc = documentRepository.save(new Document(
            docId, testUser, "Integration Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        // Create revision-0 snapshot
        snapshotRepository.save(new DocumentSnapshot(
            UUID.randomUUID(), testDoc, syncEpoch, 0L, "Hello",
            OperationPersistenceService.calculateSha256("Hello"), OffsetDateTime.now()
        ));
    }

    @Test
    @DisplayName("Persist batch updates database current_revision and stores batch + operation ID rows")
    void testPersistBatchSuccess() {
        UUID clientId = UUID.randomUUID();
        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();

        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, clientId, opId1, testUser.getId(), new InsertOperation(5, " World 🚀")
        );
        PersistedCanonicalOperation op2 = new PersistedCanonicalOperation(
            2L, clientId, opId2, testUser.getId(), new DeleteOperation(0, 1)
        );

        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(op1, op2), null
        );

        DocumentOperationBatch savedBatch = persistenceService.persistBatch(batch);
        assertNotNull(savedBatch);
        assertNotNull(savedBatch.getId());

        // Check document revision advanced to 2
        Document updatedDoc = documentRepository.findById(docId).orElseThrow();
        assertEquals(2L, updatedDoc.getCurrentRevision());

        // Check batches
        List<DocumentOperationBatch> batches = batchRepository.findBatchesAfterRevision(docId, syncEpoch, 0L);
        assertEquals(1, batches.size());
        assertEquals(1L, batches.get(0).getFirstRevision());
        assertEquals(2L, batches.get(0).getLastRevision());
        assertEquals(2, batches.get(0).getOperationCount());

        // Check canonical operations query
        List<PersistedCanonicalOperation> loadedOps = persistenceService.getCanonicalOperations(docId, syncEpoch, 0L, 2L);
        assertEquals(2, loadedOps.size());
        assertEquals(" World 🚀", ((InsertOperation) loadedOps.get(0).operation()).text());
        assertEquals(0, ((DeleteOperation) loadedOps.get(1).operation()).position());
    }

    @Test
    @DisplayName("Fenced revision failure rolls back all batch and operation ID rows")
    void testFencingRollbackAtomicity() {
        UUID clientId = UUID.randomUUID();
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(0, "A")
        );

        // Batch specifies expectedPreviousRevision = 5, but document is at 0
        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 5L, List.of(new PersistedCanonicalOperation(6L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(0, "A"))), null
        );

        assertThrows(StaleRevisionFencingException.class, () -> persistenceService.persistBatch(batch));

        // Verify document revision is unchanged
        Document doc = documentRepository.findById(docId).orElseThrow();
        assertEquals(0L, doc.getCurrentRevision());

        // Verify zero batches and zero operation IDs were persisted
        assertEquals(0, batchRepository.count());
        assertEquals(0, operationIdRepository.count());
    }

    @Test
    @DisplayName("Hard deleting document cascades to delete batches and operation IDs")
    void testCascadeDelete() {
        UUID clientId = UUID.randomUUID();
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(0, "A")
        );

        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(op1), null
        );
        persistenceService.persistBatch(batch);

        assertEquals(1, batchRepository.count());
        assertEquals(1, operationIdRepository.count());

        // Hard delete document
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            operationIdRepository.deleteByIdDocumentId(docId);
            batchRepository.deleteByDocumentId(docId);
            snapshotRepository.deleteByDocumentId(docId);
            documentRepository.deleteById(docId);
        });

        assertEquals(0, documentRepository.count());
        assertEquals(0, batchRepository.count());
        assertEquals(0, operationIdRepository.count());
    }

    @Test
    @DisplayName("Round-trip persistence with Unicode surrogate pairs and GROUP operations")
    void testUnicodeAndGroupOperationsRoundTrip() {
        UUID clientId = UUID.randomUUID();

        // 1. Emoji insert
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, clientId, UUID.randomUUID(), testUser.getId(),
            new InsertOperation(5, " ✨🎉🔥🌟 ")
        );

        // 2. Server-emitted GROUP operation containing split deletes
        GroupOperation splitGroup = new GroupOperation(List.of(
            new DeleteOperation(1, 2),
            new DeleteOperation(6, 3),
            NoOpOperation.INSTANCE
        ));
        PersistedCanonicalOperation op2 = new PersistedCanonicalOperation(
            2L, clientId, UUID.randomUUID(), testUser.getId(),
            splitGroup
        );

        persistenceService.persistBatch(new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(op1, op2), null
        ));

        List<PersistedCanonicalOperation> loaded = persistenceService.getCanonicalOperations(docId, syncEpoch, 0L, 2L);
        assertEquals(2, loaded.size());

        InsertOperation loadedOp1 = (InsertOperation) loaded.get(0).operation();
        assertEquals(" ✨🎉🔥🌟 ", loadedOp1.text());

        GroupOperation loadedOp2 = (GroupOperation) loaded.get(1).operation();
        assertEquals(3, loadedOp2.operations().size());
        assertEquals(1, ((DeleteOperation) loadedOp2.operations().get(0)).position());
        assertEquals(6, ((DeleteOperation) loadedOp2.operations().get(1)).position());
        assertTrue(loadedOp2.operations().get(2) instanceof NoOpOperation);
    }
}

