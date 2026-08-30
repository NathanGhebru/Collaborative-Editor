package com.collaborativeeditor.persistence;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.DocumentRecoveryResult;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.persistence.RevisionGapException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("Document Snapshot + Operation Log Recovery Tests")
public class DocumentRecoveryIntegrationTest {

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
            UUID.randomUUID(), "recoveryauthor", "rec@test.com", "hash", "Rec Author",
            AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        testDoc = documentRepository.save(new Document(
            docId, testUser, "Recovery Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        // Initial snapshot at revision 0 with text "ABC"
        snapshotRepository.save(new DocumentSnapshot(
            UUID.randomUUID(), testDoc, syncEpoch, 0L, "ABC",
            OperationPersistenceService.calculateSha256("ABC"), OffsetDateTime.now()
        ));
    }

    @Test
    @DisplayName("Replay 3 consecutive batches over revision 0 snapshot converges to expected text")
    void testReplayMultipleBatches() {
        UUID clientId = UUID.randomUUID();

        // Batch 1: revisions 1..2 (insert "12" at 1 -> "A12BC", then insert "3" at 5 -> "A12BC3")
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(1, "12"));
        PersistedCanonicalOperation op2 = new PersistedCanonicalOperation(2L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(5, "3"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 0L, List.of(op1, op2), null));

        // Batch 2: revisions 3..4 (delete "BC" at 3, len 2 -> "A123", then insert "XYZ" at 0 -> "XYZA123")
        PersistedCanonicalOperation op3 = new PersistedCanonicalOperation(3L, clientId, UUID.randomUUID(), testUser.getId(), new DeleteOperation(3, 2));
        PersistedCanonicalOperation op4 = new PersistedCanonicalOperation(4L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(0, "XYZ"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 2L, List.of(op3, op4), null));

        // Batch 3: revision 5 (insert "🔥" at 7 -> "XYZA123🔥")
        PersistedCanonicalOperation op5 = new PersistedCanonicalOperation(5L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(7, "🔥"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 4L, List.of(op5), null));

        DocumentRecoveryResult result = persistenceService.recoverDocument(docId);

        assertNotNull(result);
        assertEquals(5L, result.revision());
        assertEquals("XYZA123🔥", result.content());
        assertEquals(5, result.operationsReplayed());
        assertEquals(0L, result.baseSnapshotRevision());
        assertEquals(OperationPersistenceService.calculateSha256("XYZA123🔥"), result.contentHash());
    }

    @Test
    @DisplayName("Periodic snapshot bounds replay: recovers from snapshot at rev 3 plus subsequent batches")
    void testPeriodicSnapshotBoundsReplay() {
        UUID clientId = UUID.randomUUID();

        // Batch 1: revisions 1..3 ("ABC" -> "A1BC" -> "A12BC" -> "A123BC")
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(1, "1"));
        PersistedCanonicalOperation op2 = new PersistedCanonicalOperation(2L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(2, "2"));
        PersistedCanonicalOperation op3 = new PersistedCanonicalOperation(3L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(3, "3"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 0L, List.of(op1, op2, op3), null));

        // Save a periodic snapshot at revision 3
        persistenceService.createSnapshot(docId, syncEpoch, 3L, "A123BC", null, "PERIODIC");

        // Batch 2: revisions 4..5 (delete "BC" at 4, len 2 -> "A123", insert "!" at 4 -> "A123!")
        PersistedCanonicalOperation op4 = new PersistedCanonicalOperation(4L, clientId, UUID.randomUUID(), testUser.getId(), new DeleteOperation(4, 2));
        PersistedCanonicalOperation op5 = new PersistedCanonicalOperation(5L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(4, "!"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 3L, List.of(op4, op5), null));

        DocumentRecoveryResult result = persistenceService.recoverDocument(docId);

        assertEquals(5L, result.revision());
        assertEquals("A123!", result.content());
        assertEquals(3L, result.baseSnapshotRevision()); // Replay began from snapshot 3!
        assertEquals(2, result.operationsReplayed()); // Only 2 operations replayed!
    }

    @Test
    @DisplayName("Missing intermediate revision throws RevisionGapException during recovery")
    void testRevisionGapThrowsException() {
        UUID clientId = UUID.randomUUID();

        // Manually persist batch with revisions 1..2 and batch with revisions 4..5 (missing rev 3)
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(1, "1"));
        PersistedCanonicalOperation op2 = new PersistedCanonicalOperation(2L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(2, "2"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 0L, List.of(op1, op2), null));

        // Advance document currentRevision to 5 directly to simulate checking a gap
        testDoc.setCurrentRevision(5L);
        documentRepository.save(testDoc);

        PersistedCanonicalOperation op4 = new PersistedCanonicalOperation(4L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(0, "X"));
        PersistedCanonicalOperation op5 = new PersistedCanonicalOperation(5L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(1, "Y"));
        try {
            String jsonOps = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("operations", List.of(op4, op5)));
            batchRepository.save(new com.collaborativeeditor.domain.document.DocumentOperationBatch(
                UUID.randomUUID(), testDoc, syncEpoch, 4L, 5L, jsonOps, 2, null, OffsetDateTime.now()
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        RevisionGapException ex = assertThrows(RevisionGapException.class, () -> persistenceService.recoverDocument(docId));
        assertEquals(3L, ex.getExpectedRevision());
        assertEquals(4L, ex.getActualRevision());
    }

    @Test
    @DisplayName("Epoch isolation: operations in an older epoch are not replayed for a new epoch")
    void testEpochIsolation() {
        UUID clientId = UUID.randomUUID();

        // Old epoch edits: revs 1..2
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(1, "OLD"));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, syncEpoch, 0L, List.of(op1), null));

        // Restore happens: new syncEpoch, revision resets to 0, new snapshot at 0
        UUID newEpoch = UUID.randomUUID();
        testDoc.setSyncEpoch(newEpoch);
        testDoc.setCurrentRevision(0L);
        documentRepository.save(testDoc);

        snapshotRepository.save(new DocumentSnapshot(
            UUID.randomUUID(), testDoc, newEpoch, 0L, "Restored Text",
            OperationPersistenceService.calculateSha256("Restored Text"), OffsetDateTime.now()
        ));

        // New epoch edits: rev 1
        PersistedCanonicalOperation newOp1 = new PersistedCanonicalOperation(1L, clientId, UUID.randomUUID(), testUser.getId(), new InsertOperation(0, "New: "));
        persistenceService.persistBatch(new CanonicalOperationBatch(docId, newEpoch, 0L, List.of(newOp1), null));

        DocumentRecoveryResult result = persistenceService.recoverDocument(docId);
        assertEquals(1L, result.revision());
        assertEquals(newEpoch, result.syncEpoch());
        assertEquals("New: Restored Text", result.content());
        assertEquals(1, result.operationsReplayed());
    }
}

