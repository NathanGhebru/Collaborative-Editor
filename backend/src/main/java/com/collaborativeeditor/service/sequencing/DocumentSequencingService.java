package com.collaborativeeditor.service.sequencing;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatch;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.ot.engine.OtEngine;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.ot.model.OperationKey;
import com.collaborativeeditor.ot.validation.OperationValidationException;
import com.collaborativeeditor.ot.validation.OperationValidator;
import com.collaborativeeditor.service.persistence.CanonicalOperationBatch;
import com.collaborativeeditor.service.persistence.DocumentRecoveryResult;
import com.collaborativeeditor.service.persistence.IdempotencyLookupResult;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.persistence.StaleRevisionFencingException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service implementing the transport-independent OT sequencing and durable acceptance pipeline (PERS-002).
 */
@Service
public class DocumentSequencingService {

    private static final int MAX_SEQUENCING_RETRIES = 30;

    private final DocumentRepository documentRepository;
    private final OperationPersistenceService persistenceService;
    private volatile Runnable testProvisionalHook;

    public DocumentSequencingService(
            DocumentRepository documentRepository,
            OperationPersistenceService persistenceService) {
        this.documentRepository = documentRepository;
        this.persistenceService = persistenceService;
    }

    public void setTestProvisionalHook(Runnable testProvisionalHook) {
        this.testProvisionalHook = testProvisionalHook;
    }

    /**
     * Submits a client operation for deterministic validation, historical rebase,
     * canonical revision assignment, and durable atomic persistence.
     *
     * @param command The submitted operation command.
     * @return AcceptedOperationResult containing the assigned canonical revision and transformed operation.
     */
    public AcceptedOperationResult submitOperation(SubmitOperationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // 1. Validate envelope and primitive operation kind
        if (command.operation() instanceof GroupOperation || command.operation() instanceof NoOpOperation) {
            throw new SequencerOperationRejectedException(
                "INVALID_OPERATION",
                "Clients cannot submit NO_OP or GROUP operations as new edits."
            );
        }

        // 2. Idempotency Pre-Check
        IdempotencyLookupResult existing = persistenceService.checkIdempotency(
            command.documentId(),
            command.syncEpoch(),
            command.clientId(),
            command.clientOperationId(),
            command.operation()
        );

        if (existing.isDuplicate()) {
            return new AcceptedOperationResult(
                command.documentId(),
                command.syncEpoch(),
                existing.revision(),
                command.clientId(),
                command.clientOperationId(),
                command.actorUserId(),
                existing.canonicalOperation().operation(),
                existing.batchId(),
                true,
                OffsetDateTime.now()
            );
        }

        // 3. Document and Epoch Validation
        Document document = documentRepository.findById(command.documentId())
            .orElseThrow(() -> new SequencerOperationRejectedException(
                "DOCUMENT_NOT_FOUND",
                "Document not found: " + command.documentId()
            ));

        if (!document.getSyncEpoch().equals(command.syncEpoch())) {
            throw new EpochMismatchException(document.getSyncEpoch(), command.syncEpoch());
        }

        // 4. Base Revision Validation
        long currentRev = document.getCurrentRevision();
        if (command.baseRevision() > currentRev) {
            throw new FutureRevisionException(command.baseRevision(), currentRev);
        }

        // 5. Validate operation against base document text
        DocumentRecoveryResult baseDoc = persistenceService.recoverDocumentAtRevision(
            command.documentId(),
            command.syncEpoch(),
            command.baseRevision()
        );

        try {
            OperationValidator.validate(baseDoc.content(), command.operation());
        } catch (OperationValidationException e) {
            throw new SequencerOperationRejectedException(
                e.getErrorCode() != null ? e.getErrorCode() : "INVALID_OPERATION",
                e.getMessage(),
                e
            );
        }

        // 6. Optimistic Sequencing & Rebase Loop (with fencing retry)
        int maxRetries = command.maxRetries() > 0 ? command.maxRetries() : MAX_SEQUENCING_RETRIES;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (attempt > 0) {
                // Short jittered backoff to alleviate high concurrency collisions
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(2, 10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                document = documentRepository.findById(command.documentId())
                    .orElseThrow(() -> new SequencerOperationRejectedException(
                        "DOCUMENT_NOT_FOUND",
                        "Document not found: " + command.documentId()
                    ));

                if (!document.getSyncEpoch().equals(command.syncEpoch())) {
                    throw new EpochMismatchException(document.getSyncEpoch(), command.syncEpoch());
                }

                currentRev = document.getCurrentRevision();

                // Check if our operation was committed in a concurrent race
                IdempotencyLookupResult retryCheck = persistenceService.checkIdempotency(
                    command.documentId(),
                    command.syncEpoch(),
                    command.clientId(),
                    command.clientOperationId(),
                    command.operation()
                );
                if (retryCheck.isDuplicate()) {
                    return new AcceptedOperationResult(
                        command.documentId(),
                        command.syncEpoch(),
                        retryCheck.revision(),
                        command.clientId(),
                        command.clientOperationId(),
                        command.actorUserId(),
                        retryCheck.canonicalOperation().operation(),
                        retryCheck.batchId(),
                        true,
                        OffsetDateTime.now()
                    );
                }
            }

            // Historical Rebase / Transformation
            Operation currentOp = command.operation();
            if (command.baseRevision() < currentRev) {
                List<PersistedCanonicalOperation> history = persistenceService.getCanonicalOperations(
                    command.documentId(),
                    command.syncEpoch(),
                    command.baseRevision(),
                    currentRev
                );

                OperationKey keyA = OperationKey.of(command.clientId(), command.clientOperationId());
                for (PersistedCanonicalOperation committed : history) {
                    OperationKey keyB = OperationKey.of(committed.clientId(), committed.clientOperationId());
                    currentOp = OtEngine.transform(currentOp, committed.operation(), keyA, keyB);
                }
            }

            long newRevision = currentRev + 1;
            PersistedCanonicalOperation persistedOp = new PersistedCanonicalOperation(
                newRevision,
                command.clientId(),
                command.clientOperationId(),
                command.actorUserId(),
                currentOp
            );

            CanonicalOperationBatch batch = new CanonicalOperationBatch(
                command.documentId(),
                command.syncEpoch(),
                currentRev,
                List.of(persistedOp),
                null
            );

            Runnable provHook = this.testProvisionalHook;
            if (provHook != null) {
                this.testProvisionalHook = null;
                provHook.run();
            }

            try {
                DocumentOperationBatch savedBatch = persistenceService.persistBatch(batch);
                return new AcceptedOperationResult(
                    command.documentId(),
                    command.syncEpoch(),
                    newRevision,
                    command.clientId(),
                    command.clientOperationId(),
                    command.actorUserId(),
                    currentOp,
                    savedBatch.getId(),
                    false,
                    savedBatch.getCreatedAt()
                );
            } catch (StaleRevisionFencingException e) {
                if (attempt == MAX_SEQUENCING_RETRIES - 1) {
                    throw e;
                }
            }
        }

        throw new StaleRevisionFencingException(
            command.documentId(),
            command.syncEpoch(),
            currentRev,
            currentRev + 1
        );
    }
}
