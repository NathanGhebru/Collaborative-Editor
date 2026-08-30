package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatch;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.DocumentRecoveryResult;
import com.collaborativeeditor.service.persistence.IdempotencyLookupResult;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.sequencing.AcceptedOperationResult;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
import com.collaborativeeditor.service.sequencing.EpochMismatchException;
import com.collaborativeeditor.service.sequencing.FutureRevisionException;
import com.collaborativeeditor.service.sequencing.SequencerOperationRejectedException;
import com.collaborativeeditor.service.sequencing.SubmitOperationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentSequencingService Unit & Pipeline Tests")
public class DocumentSequencingServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private OperationPersistenceService persistenceService;

    private DocumentSequencingService sequencingService;

    private UUID docId;
    private UUID syncEpoch;
    private UUID clientId;
    private Document testDoc;

    @BeforeEach
    void setUp() {
        sequencingService = new DocumentSequencingService(documentRepository, persistenceService);

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        clientId = UUID.randomUUID();

        User owner = new User(UUID.randomUUID(), "owner", "owner@test.com", "hash", "Owner", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        testDoc = new Document(docId, owner, "Test Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("Rejects client-authored GROUP or NO_OP operations")
    void rejectClientAuthoredGroupOrNoOp() {
        SubmitOperationCommand groupCmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, UUID.randomUUID(), UUID.randomUUID(), 0L,
            new GroupOperation(List.of(new InsertOperation(0, "A")))
        );

        SequencerOperationRejectedException ex1 = assertThrows(
            SequencerOperationRejectedException.class,
            () -> sequencingService.submitOperation(groupCmd)
        );
        assertEquals("INVALID_OPERATION", ex1.getRejectionCode());

        SubmitOperationCommand noopCmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, UUID.randomUUID(), UUID.randomUUID(), 0L,
            NoOpOperation.INSTANCE
        );
        SequencerOperationRejectedException ex2 = assertThrows(
            SequencerOperationRejectedException.class,
            () -> sequencingService.submitOperation(noopCmd)
        );
        assertEquals("INVALID_OPERATION", ex2.getRejectionCode());
    }

    @Test
    @DisplayName("Returns cached result on duplicate idempotent retry")
    void duplicateIdempotentRetry() {
        UUID opId = UUID.randomUUID();
        InsertOperation insertOp = new InsertOperation(0, "Duplicate");

        PersistedCanonicalOperation prevOp = new PersistedCanonicalOperation(
            1L, clientId, opId, UUID.randomUUID(), insertOp
        );
        UUID batchId = UUID.randomUUID();

        when(persistenceService.checkIdempotency(eq(docId), eq(syncEpoch), eq(clientId), eq(opId), eq(insertOp)))
            .thenReturn(IdempotencyLookupResult.duplicate(1L, batchId, prevOp));

        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, opId, UUID.randomUUID(), 0L, insertOp
        );

        AcceptedOperationResult result = sequencingService.submitOperation(cmd);

        assertTrue(result.isDuplicate());
        assertEquals(1L, result.revision());
        assertEquals(batchId, result.batchId());
        assertEquals(insertOp, result.canonicalOperation());
    }

    @Test
    @DisplayName("Rejects operation when syncEpoch does not match document")
    void rejectEpochMismatch() {
        UUID wrongEpoch = UUID.randomUUID();
        when(persistenceService.checkIdempotency(any(), any(), any(), any(), any()))
            .thenReturn(IdempotencyLookupResult.notFound());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));

        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, wrongEpoch, clientId, UUID.randomUUID(), UUID.randomUUID(), 0L, new InsertOperation(0, "A")
        );

        EpochMismatchException ex = assertThrows(
            EpochMismatchException.class,
            () -> sequencingService.submitOperation(cmd)
        );
        assertEquals(syncEpoch, ex.getExpectedEpoch());
        assertEquals(wrongEpoch, ex.getActualEpoch());
    }

    @Test
    @DisplayName("Rejects operation when baseRevision is in the future")
    void rejectFutureRevision() {
        when(persistenceService.checkIdempotency(any(), any(), any(), any(), any()))
            .thenReturn(IdempotencyLookupResult.notFound());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));

        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, UUID.randomUUID(), UUID.randomUUID(), 5L, new InsertOperation(0, "A")
        );

        FutureRevisionException ex = assertThrows(
            FutureRevisionException.class,
            () -> sequencingService.submitOperation(cmd)
        );
        assertEquals(5L, ex.getBaseRevision());
        assertEquals(0L, ex.getCurrentRevision());
    }

    @Test
    @DisplayName("Rejects operation that bisects UTF-16 surrogate pair in base document")
    void rejectSurrogatePairBisection() {
        when(persistenceService.checkIdempotency(any(), any(), any(), any(), any()))
            .thenReturn(IdempotencyLookupResult.notFound());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));

        // Base document has emoji "🚀" (length 2 UTF-16 code units)
        when(persistenceService.recoverDocumentAtRevision(docId, syncEpoch, 0L))
            .thenReturn(new DocumentRecoveryResult(docId, syncEpoch, 0L, "🚀", "hash", UUID.randomUUID(), 0L, 0));

        // Operation attempts to insert at index 1 (between high and low surrogate)
        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, UUID.randomUUID(), UUID.randomUUID(), 0L, new InsertOperation(1, "bad")
        );

        SequencerOperationRejectedException ex = assertThrows(
            SequencerOperationRejectedException.class,
            () -> sequencingService.submitOperation(cmd)
        );
        assertEquals("INVALID_POSITION", ex.getRejectionCode());
    }

    @Test
    @DisplayName("Sequences first operation at revision 0 and persists canonical batch")
    void sequenceFirstOperationAtRevision0() {
        when(persistenceService.checkIdempotency(any(), any(), any(), any(), any()))
            .thenReturn(IdempotencyLookupResult.notFound());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));
        when(persistenceService.recoverDocumentAtRevision(docId, syncEpoch, 0L))
            .thenReturn(new DocumentRecoveryResult(docId, syncEpoch, 0L, "", "hash", UUID.randomUUID(), 0L, 0));

        UUID batchId = UUID.randomUUID();
        DocumentOperationBatch savedBatch = new DocumentOperationBatch(
            batchId, testDoc, syncEpoch, 1L, 1L, "json", 1, null, OffsetDateTime.now()
        );
        when(persistenceService.persistBatch(any())).thenReturn(savedBatch);

        UUID opId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        InsertOperation insertOp = new InsertOperation(0, "Hello");

        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, opId, actorId, 0L, insertOp
        );

        AcceptedOperationResult result = sequencingService.submitOperation(cmd);

        assertNotNull(result);
        assertFalse(result.isDuplicate());
        assertEquals(1L, result.revision());
        assertEquals(batchId, result.batchId());
        assertEquals(insertOp, result.canonicalOperation());

        ArgumentCaptor<CanonicalOperationBatch> captor = ArgumentCaptor.forClass(CanonicalOperationBatch.class);
        verify(persistenceService).persistBatch(captor.capture());
        CanonicalOperationBatch persistedBatch = captor.getValue();
        assertEquals(0L, persistedBatch.expectedPreviousRevision());
        assertEquals(1L, persistedBatch.getFirstRevision());
        assertEquals(1L, persistedBatch.getLastRevision());
    }

    @Test
    @DisplayName("Rebases stale operation over committed history with split delete (GROUP result)")
    void rebaseStaleOperationWithSplitDelete() {
        testDoc.setCurrentRevision(1L);

        when(persistenceService.checkIdempotency(any(), any(), any(), any(), any()))
            .thenReturn(IdempotencyLookupResult.notFound());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(testDoc));

        // Base document at rev 0 was "ABCDEF"
        when(persistenceService.recoverDocumentAtRevision(docId, syncEpoch, 0L))
            .thenReturn(new DocumentRecoveryResult(docId, syncEpoch, 0L, "ABCDEF", "hash", UUID.randomUUID(), 0L, 0));

        // Committed rev 1 inserted "XYZ" at pos 3 -> "ABCXYZDEF"
        PersistedCanonicalOperation rev1Op = new PersistedCanonicalOperation(
            1L, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new InsertOperation(3, "XYZ")
        );
        when(persistenceService.getCanonicalOperations(docId, syncEpoch, 0L, 1L))
            .thenReturn(List.of(rev1Op));

        UUID batchId = UUID.randomUUID();
        DocumentOperationBatch savedBatch = new DocumentOperationBatch(
            batchId, testDoc, syncEpoch, 2L, 2L, "json", 1, null, OffsetDateTime.now()
        );
        when(persistenceService.persistBatch(any())).thenReturn(savedBatch);

        // Stale client deletes range [1, 5) (length 4 across "BCDE") from baseRevision 0
        DeleteOperation deleteOp = new DeleteOperation(1, 4);
        SubmitOperationCommand cmd = new SubmitOperationCommand(
            docId, syncEpoch, clientId, UUID.randomUUID(), UUID.randomUUID(), 0L, deleteOp
        );

        AcceptedOperationResult result = sequencingService.submitOperation(cmd);

        assertNotNull(result);
        assertEquals(2L, result.revision());
        assertTrue(result.canonicalOperation() instanceof GroupOperation, "Delete spanning insertion should split into GROUP");

        GroupOperation group = (GroupOperation) result.canonicalOperation();
        assertEquals(2, group.operations().size());
        // Split around "XYZ" at pos 3: DELETE(1, 2) and DELETE(1+3, 4-2) = DELETE(4, 2)
        assertEquals(new DeleteOperation(1, 2), group.operations().get(0));
        assertEquals(new DeleteOperation(4, 2), group.operations().get(1));
    }
}
