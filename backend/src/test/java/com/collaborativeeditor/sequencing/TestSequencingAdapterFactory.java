package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.service.persistence.DocumentRecoveryResult;
import com.collaborativeeditor.service.persistence.IdempotencyConflictException;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.StaleRevisionFencingException;
import com.collaborativeeditor.service.sequencing.AcceptedOperationResult;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
import com.collaborativeeditor.service.sequencing.EpochMismatchException;
import com.collaborativeeditor.service.sequencing.FutureRevisionException;
import com.collaborativeeditor.service.sequencing.SequencerOperationRejectedException;
import com.collaborativeeditor.service.sequencing.SubmitOperationCommand;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.AcceptedOperation;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.CommitBarrier;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RecoveredDocument;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RejectedSubmissionException;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.RejectionCode;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.Sequencer;
import com.collaborativeeditor.sequencing.DurableSequencingTestAdapter.Submission;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TestSequencingAdapterFactory implements DurableSequencingTestAdapter.Factory {

    @Override
    public DurableSequencingTestAdapter create(ApplicationContext applicationContext) {
        DocumentSequencingService sequencingService = applicationContext.getBean(DocumentSequencingService.class);
        OperationPersistenceService persistenceService = applicationContext.getBean(OperationPersistenceService.class);
        DocumentRepository documentRepository = applicationContext.getBean(DocumentRepository.class);

        return new TestSequencingAdapter(sequencingService, persistenceService, documentRepository);
    }

    private static class TestSequencingAdapter implements DurableSequencingTestAdapter {

        private final DocumentSequencingService sequencingService;
        private final OperationPersistenceService persistenceService;
        private final DocumentRepository documentRepository;

        TestSequencingAdapter(
                DocumentSequencingService sequencingService,
                OperationPersistenceService persistenceService,
                DocumentRepository documentRepository) {
            this.sequencingService = sequencingService;
            this.persistenceService = persistenceService;
            this.documentRepository = documentRepository;
        }

        @Override
        public Sequencer openSequencer(UUID documentId) {
            return new SequencerImpl(documentId, sequencingService, persistenceService);
        }

        @Override
        public RecoveredDocument recover(UUID documentId) {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
            UUID syncEpoch = document.getSyncEpoch();
            long rev = document.getCurrentRevision();
            DocumentRecoveryResult result = persistenceService.recoverDocumentAtRevision(documentId, syncEpoch, rev);
            return new RecoveredDocument(documentId, syncEpoch, rev, result.content());
        }
    }

    private static class SequencerImpl implements DurableSequencingTestAdapter.Sequencer {

        private final UUID documentId;
        private final DocumentSequencingService sequencingService;
        private final OperationPersistenceService persistenceService;

        private final AtomicReference<CommitBarrierImpl> activeProvisionalBarrier = new AtomicReference<>();
        private final AtomicReference<CommitBarrierImpl> activePreCommitBarrier = new AtomicReference<>();
        private volatile boolean failNextCommit = false;

        SequencerImpl(
                UUID documentId,
                DocumentSequencingService sequencingService,
                OperationPersistenceService persistenceService) {
            this.documentId = documentId;
            this.sequencingService = sequencingService;
            this.persistenceService = persistenceService;
        }

        @Override
        public AcceptedOperation submit(Submission submission) {
            CommitBarrierImpl provBarrier = activeProvisionalBarrier.getAndSet(null);
            if (provBarrier != null) {
                sequencingService.setTestProvisionalHook(provBarrier::onHookReached);
            }

            SubmitOperationCommand command = new SubmitOperationCommand(
                    submission.documentId(),
                    submission.syncEpoch(),
                    submission.clientId(),
                    submission.clientOperationId(),
                    submission.actorUserId(),
                    submission.baseRevision(),
                    submission.operation(),
                    provBarrier != null ? 1 : 30
            );

            CommitBarrierImpl preCommitBarrier = activePreCommitBarrier.getAndSet(null);
            boolean shouldFail = this.failNextCommit;
            this.failNextCommit = false;

            if (preCommitBarrier != null || shouldFail) {
                persistenceService.setTestPreCommitHook(() -> {
                    if (preCommitBarrier != null) {
                        preCommitBarrier.onHookReached();
                    }
                    if (shouldFail) {
                        throw new RuntimeException("Injected persistence failure before commit");
                    }
                });
            }

            try {
                AcceptedOperationResult result = sequencingService.submitOperation(command);
                return new AcceptedOperation(
                        result.documentId(),
                        result.syncEpoch(),
                        result.clientId(),
                        result.clientOperationId(),
                        result.revision(),
                        result.canonicalOperation()
                );
            } catch (EpochMismatchException e) {
                throw new RejectedSubmissionException(RejectionCode.EPOCH_MISMATCH, e.getMessage(), e);
            } catch (FutureRevisionException e) {
                throw new RejectedSubmissionException(RejectionCode.REVISION_AHEAD, e.getMessage(), e);
            } catch (IdempotencyConflictException e) {
                throw new RejectedSubmissionException(RejectionCode.IDENTITY_CONFLICT, e.getMessage(), e);
            } catch (StaleRevisionFencingException e) {
                throw new RejectedSubmissionException(RejectionCode.STALE_SEQUENCER, e.getMessage(), e);
            } catch (SequencerOperationRejectedException e) {
                RejectionCode code = mapCode(e.getRejectionCode(), e.getMessage());
                throw new RejectedSubmissionException(code, e.getMessage(), e);
            } catch (Exception e) {
                RejectionCode code = mapException(e);
                throw new RejectedSubmissionException(code, e.getMessage(), e);
            } finally {
                sequencingService.setTestProvisionalHook(null);
                persistenceService.setTestPreCommitHook(null);
            }
        }

        private RejectionCode mapCode(String codeStr, String message) {
            if ("EPOCH_MISMATCH".equals(codeStr)) return RejectionCode.EPOCH_MISMATCH;
            if ("FUTURE_REVISION".equals(codeStr) || "REVISION_AHEAD".equals(codeStr)) return RejectionCode.REVISION_AHEAD;
            if ("IDENTITY_CONFLICT".equals(codeStr)) return RejectionCode.IDENTITY_CONFLICT;
            if ("STALE_SEQUENCER".equals(codeStr)) return RejectionCode.STALE_SEQUENCER;
            return mapMessage(message);
        }

        private RejectionCode mapException(Throwable e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (e instanceof EpochMismatchException || msg.contains("Epoch mismatch")) {
                return RejectionCode.EPOCH_MISMATCH;
            }
            if (e instanceof FutureRevisionException || msg.contains("Future revision") || msg.contains("ahead")) {
                return RejectionCode.REVISION_AHEAD;
            }
            if (e instanceof IdempotencyConflictException || msg.contains("Idempotency conflict")) {
                return RejectionCode.IDENTITY_CONFLICT;
            }
            if (e instanceof StaleRevisionFencingException || msg.contains("Stale revision") || msg.contains("fencing")) {
                return RejectionCode.STALE_SEQUENCER;
            }
            return RejectionCode.PERSISTENCE_FAILED;
        }

        private RejectionCode mapMessage(String msg) {
            if (msg == null) return RejectionCode.PERSISTENCE_FAILED;
            if (msg.contains("Epoch")) return RejectionCode.EPOCH_MISMATCH;
            if (msg.contains("Future revision") || msg.contains("ahead")) return RejectionCode.REVISION_AHEAD;
            if (msg.contains("Idempotency") || msg.contains("conflict")) return RejectionCode.IDENTITY_CONFLICT;
            if (msg.contains("Stale") || msg.contains("fencing")) return RejectionCode.STALE_SEQUENCER;
            return RejectionCode.PERSISTENCE_FAILED;
        }

        @Override
        public CommitBarrier pauseNextAfterProvisionalOrder() {
            CommitBarrierImpl barrier = new CommitBarrierImpl();
            activeProvisionalBarrier.set(barrier);
            return barrier;
        }

        @Override
        public CommitBarrier pauseNextBeforeDatabaseCommit() {
            CommitBarrierImpl barrier = new CommitBarrierImpl();
            activePreCommitBarrier.set(barrier);
            return barrier;
        }

        @Override
        public void failNextAfterPersistenceWritesBeforeCommit() {
            this.failNextCommit = true;
        }
    }

    private static class CommitBarrierImpl implements DurableSequencingTestAdapter.CommitBarrier {

        private final CountDownLatch pausedLatch = new CountDownLatch(1);
        private final CountDownLatch releaseLatch = new CountDownLatch(1);

        void onHookReached() {
            pausedLatch.countDown();
            try {
                if (!releaseLatch.await(30, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Commit barrier timed out waiting for release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Commit barrier interrupted", e);
            }
        }

        @Override
        public void awaitPaused(Duration timeout) {
            try {
                if (!pausedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Timed out waiting for submission to pause at barrier");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for submission to pause", e);
            }
        }

        @Override
        public void release() {
            releaseLatch.countDown();
        }
    }
}
