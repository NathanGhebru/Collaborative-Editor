package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.ot.model.Operation;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Test-only behavioral seam for the transport-independent PERS-002 service.
 *
 * Production code does not implement this interface. After PERS-002 is merged, a
 * small test adapter can normalize its public/internal API into this contract
 * without constraining production class names, exception types, or construction.
 */
public interface DurableSequencingTestAdapter {

    Sequencer openSequencer(UUID documentId);

    RecoveredDocument recover(UUID documentId);

    interface Factory {
        DurableSequencingTestAdapter create(ApplicationContext applicationContext);
    }

    interface Sequencer {
        AcceptedOperation submit(Submission submission);

        /**
         * Pauses one submission after its canonical result and expected previous
         * revision are fixed, but before its persistence transaction begins.
         */
        CommitBarrier pauseNextAfterProvisionalOrder();

        /**
         * Pauses one submission after transaction writes are issued and directly
         * before the database commit boundary.
         */
        CommitBarrier pauseNextBeforeDatabaseCommit();

        /**
         * Causes one submission to fail after transaction writes have been issued
         * but before PostgreSQL commit.
         */
        void failNextAfterPersistenceWritesBeforeCommit();
    }

    interface CommitBarrier extends AutoCloseable {
        void awaitPaused(Duration timeout);

        void release();

        @Override
        default void close() {
            release();
        }
    }

    record Submission(
            UUID documentId,
            UUID syncEpoch,
            UUID actorUserId,
            UUID clientId,
            UUID clientOperationId,
            long baseRevision,
            Operation operation) {

        public Submission {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(syncEpoch, "syncEpoch must not be null");
            Objects.requireNonNull(actorUserId, "actorUserId must not be null");
            Objects.requireNonNull(clientId, "clientId must not be null");
            Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
        }
    }

    record AcceptedOperation(
            UUID documentId,
            UUID syncEpoch,
            UUID clientId,
            UUID clientOperationId,
            long revision,
            Operation canonicalOperation) {

        public AcceptedOperation {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(syncEpoch, "syncEpoch must not be null");
            Objects.requireNonNull(clientId, "clientId must not be null");
            Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
            Objects.requireNonNull(canonicalOperation, "canonicalOperation must not be null");
        }
    }

    record RecoveredDocument(
            UUID documentId,
            UUID syncEpoch,
            long revision,
            String content) {

        public RecoveredDocument {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(syncEpoch, "syncEpoch must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    enum RejectionCode {
        EPOCH_MISMATCH,
        REVISION_AHEAD,
        IDENTITY_CONFLICT,
        STALE_SEQUENCER,
        PERSISTENCE_FAILED
    }

    final class RejectedSubmissionException extends RuntimeException {

        private final RejectionCode code;

        public RejectedSubmissionException(RejectionCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public RejectedSubmissionException(RejectionCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public RejectionCode code() {
            return code;
        }
    }
}
