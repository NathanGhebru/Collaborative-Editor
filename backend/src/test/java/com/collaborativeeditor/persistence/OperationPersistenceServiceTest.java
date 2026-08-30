package com.collaborativeeditor.persistence;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatch;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.persistence.StaleRevisionFencingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperationPersistenceService Unit & Fencing Tests")
public class OperationPersistenceServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentOperationBatchRepository batchRepository;

    @Mock
    private DocumentOperationIdRepository operationIdRepository;

    @Mock
    private DocumentSnapshotRepository snapshotRepository;

    private ObjectMapper objectMapper;
    private OperationPersistenceService persistenceService;

    private Document testDoc;
    private UUID docId;
    private UUID syncEpoch;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        persistenceService = new OperationPersistenceService(
            documentRepository,
            batchRepository,
            operationIdRepository,
            snapshotRepository,
            objectMapper
        );

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        User owner = new User(UUID.randomUUID(), "owner", "owner@test.com", "hash", "Owner", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        testDoc = new Document(docId, owner, "Test Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("Persist batch succeeds when conditional update updates 1 row")
    void persistBatchSuccess() {
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.updateCurrentRevisionFenced(eq(docId), eq(syncEpoch), eq(0L), eq(2L), any()))
            .thenReturn(1);

        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(0, "A")
        );
        PersistedCanonicalOperation op2 = new PersistedCanonicalOperation(
            2L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(1, "B")
        );

        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(op1, op2), "somehash"
        );

        DocumentOperationBatch savedBatch = persistenceService.persistBatch(batch);
        assertNotNull(savedBatch);
        assertEquals(1L, savedBatch.getFirstRevision());
        assertEquals(2L, savedBatch.getLastRevision());
        assertEquals(2, savedBatch.getOperationCount());

        verify(batchRepository).save(any(DocumentOperationBatch.class));
        verify(operationIdRepository).saveAll(any());
        verify(documentRepository).updateCurrentRevisionFenced(eq(docId), eq(syncEpoch), eq(0L), eq(2L), any());
    }

    @Test
    @DisplayName("Persist batch throws StaleRevisionFencingException when conditional update fails (0 rows)")
    void persistBatchStaleRevisionThrows() {
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.updateCurrentRevisionFenced(eq(docId), eq(syncEpoch), eq(0L), eq(1L), any()))
            .thenReturn(0); // 0 rows updated -> stale revision!

        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(0, "A")
        );

        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(op1), null
        );

        StaleRevisionFencingException ex = assertThrows(
            StaleRevisionFencingException.class,
            () -> persistenceService.persistBatch(batch)
        );

        assertEquals(docId, ex.getDocumentId());
        assertEquals(syncEpoch, ex.getSyncEpoch());
        assertEquals(0L, ex.getExpectedPreviousRevision());
        assertEquals(1L, ex.getAttemptedNewRevision());
    }

    @Test
    @DisplayName("Persist batch rejects non-contiguous revision sequences within batch")
    void persistBatchNonContiguousRevisions() {
        PersistedCanonicalOperation op1 = new PersistedCanonicalOperation(
            1L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(0, "A")
        );
        PersistedCanonicalOperation op3 = new PersistedCanonicalOperation(
            3L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(1, "B")
        );

        CanonicalOperationBatch batch = new CanonicalOperationBatch(
            docId, syncEpoch, 0L, List.of(op1, op3), null
        );

        assertThrows(IllegalArgumentException.class, () -> persistenceService.persistBatch(batch));
    }
}

