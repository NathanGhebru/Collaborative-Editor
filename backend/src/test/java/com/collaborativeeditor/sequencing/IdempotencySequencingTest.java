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
import com.collaborativeeditor.ot.model.InsertOperation;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("Idempotency Sequencing Tests")
public class IdempotencySequencingTest {

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
            UUID.randomUUID(), "idempauthor", "idemp@test.com", "hash", "Idemp Author",
            AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        testDoc = documentRepository.save(new Document(
            docId, testUser, "Idempotency Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        snapshotRepository.save(new DocumentSnapshot(
            UUID.randomUUID(), testDoc, syncEpoch, 0L, "Base",
            OperationPersistenceService.calculateSha256("Base"), OffsetDateTime.now()
        ));
    }

    @Test
    @DisplayName("Duplicate retry returns exact previous revision and isDuplicate=true without creating new batch")
    void testDuplicateRetry() {
        UUID clientId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();

        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, clientOpId, testUser.getId(), 0L, new InsertOperation(4, " Text")
        );

        AcceptedOperationResult res1 = sequencingService.submitOperation(cmd);
        assertFalse(res1.isDuplicate());
        assertEquals(1L, res1.revision());
        assertEquals(1, batchRepository.count());
        assertEquals(1, operationIdRepository.count());

        // Retrying identical command
        AcceptedOperationResult res2 = sequencingService.submitOperation(cmd);
        assertTrue(res2.isDuplicate());
        assertEquals(1L, res2.revision());
        assertEquals(res1.batchId(), res2.batchId());
        assertEquals(1, batchRepository.count()); // No new batch!
        assertEquals(1, operationIdRepository.count()); // No new op ID!
    }
}
