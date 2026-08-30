package com.collaborativeeditor.sequencing;

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
import com.collaborativeeditor.service.persistence.DocumentRecoveryResult;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.sequencing.AcceptedOperationResult;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
import com.collaborativeeditor.service.sequencing.SubmitOperationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("DocumentSequencingService Database Integration Tests")
public class DocumentSequencingIntegrationTest {

    @Autowired
    private DocumentSequencingService sequencingService;

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
    private com.collaborativeeditor.domain.document.DocumentPermissionRepository permissionRepository;

    private User testUser;
    private Document testDoc;
    private UUID docId;
    private UUID syncEpoch;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        operationIdRepository.deleteAll();
        batchRepository.deleteAll();
        snapshotRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(new User(
            UUID.randomUUID(), "seqauthor", "seq@test.com", "hash", "Seq Author",
            AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        testDoc = documentRepository.save(new Document(
            docId, testUser, "Sequencing Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        // Initial snapshot at revision 0 with text "Hello"
        snapshotRepository.save(new DocumentSnapshot(
            UUID.randomUUID(), testDoc, syncEpoch, 0L, "Hello",
            OperationPersistenceService.calculateSha256("Hello"), OffsetDateTime.now()
        ));
    }

    @Test
    @DisplayName("Sequences operations sequentially and advances document revision monotonically")
    void testSequentialOperations() {
        UUID clientA = UUID.randomUUID();

        // 1. Insert " World" at pos 5 from base 0 -> "Hello World"
        AcceptedOperationResult res1 = sequencingService.submitOperation(new SubmitOperationCommand(
            docId, syncEpoch, clientA, UUID.randomUUID(), testUser.getId(), 0L, new InsertOperation(5, " World")
        ));
        assertEquals(1L, res1.revision());
        assertFalse(res1.isDuplicate());

        // 2. Insert "!" at pos 11 from base 1 -> "Hello World!"
        AcceptedOperationResult res2 = sequencingService.submitOperation(new SubmitOperationCommand(
            docId, syncEpoch, clientA, UUID.randomUUID(), testUser.getId(), 1L, new InsertOperation(11, "!")
        ));
        assertEquals(2L, res2.revision());

        // 3. Delete "Hello " (len 6 at 0) from base 2 -> "World!"
        AcceptedOperationResult res3 = sequencingService.submitOperation(new SubmitOperationCommand(
            docId, syncEpoch, clientA, UUID.randomUUID(), testUser.getId(), 2L, new DeleteOperation(0, 6)
        ));
        assertEquals(3L, res3.revision());

        // Verify document state in database
        Document updatedDoc = documentRepository.findById(docId).orElseThrow();
        assertEquals(3L, updatedDoc.getCurrentRevision());

        // Verify document recovery from snapshot 0 + 3 batches
        DocumentRecoveryResult recovery = persistenceService.recoverDocument(docId);
        assertEquals(3L, recovery.revision());
        assertEquals("World!", recovery.content());
        assertEquals(3, recovery.operationsReplayed());
    }

    @Test
    @DisplayName("Rebases concurrent edits submitted from stale base revision 0")
    void testConcurrentEditsRebase() {
        UUID clientA = UUID.randomUUID();
        UUID clientB = UUID.randomUUID();

        // Client A submits from base 0: insert " Beautiful" at 5 -> "Hello Beautiful" (rev 1)
        AcceptedOperationResult resA = sequencingService.submitOperation(new SubmitOperationCommand(
            docId, syncEpoch, clientA, UUID.randomUUID(), testUser.getId(), 0L, new InsertOperation(5, " Beautiful")
        ));
        assertEquals(1L, resA.revision());

        // Client B also created edit from base 0 before receiving Client A's edit: insert " Dear" at 5
        // Rebase must transform Client B's insert against Client A's insert at the same position.
        // Precedence tie-breaking applies.
        AcceptedOperationResult resB = sequencingService.submitOperation(new SubmitOperationCommand(
            docId, syncEpoch, clientB, UUID.randomUUID(), testUser.getId(), 0L, new InsertOperation(5, " Dear")
        ));
        assertEquals(2L, resB.revision());

        // Verify converged document
        DocumentRecoveryResult recovery = persistenceService.recoverDocument(docId);
        assertEquals(2L, recovery.revision());
        assertNotNull(recovery.content());
        // Both strings were preserved
        assertEquals(true, recovery.content().contains("Hello"));
        assertEquals(true, recovery.content().contains("Beautiful"));
        assertEquals(true, recovery.content().contains("Dear"));
    }
}
