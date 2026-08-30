package com.collaborativeeditor.service.persistence;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatch;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationId;
import com.collaborativeeditor.domain.document.DocumentOperationIdPk;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.model.Operation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service implementing canonical operation persistence, batching, idempotency, and snapshot + log recovery (PERS-001).
 */
@Service
public class OperationPersistenceService {

    private final DocumentRepository documentRepository;
    private final DocumentOperationBatchRepository batchRepository;
    private final DocumentOperationIdRepository operationIdRepository;
    private final DocumentSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public OperationPersistenceService(
            DocumentRepository documentRepository,
            DocumentOperationBatchRepository batchRepository,
            DocumentOperationIdRepository operationIdRepository,
            DocumentSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.operationIdRepository = operationIdRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Atomically persists a canonical operation batch, creates all idempotency records,
     * and conditionally advances documents.current_revision.
     *
     * @param batch The canonical operation batch to persist.
     * @return The persisted DocumentOperationBatch entity.
     * @throws StaleRevisionFencingException if conditional update fails (fencing / sequencer safety).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentOperationBatch persistBatch(CanonicalOperationBatch batch) {
        Objects.requireNonNull(batch, "batch must not be null");

        List<PersistedCanonicalOperation> ops = batch.operations();
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("Cannot persist an empty operation batch");
        }

        long firstRev = batch.getFirstRevision();
        long lastRev = batch.getLastRevision();
        long expectedPrevRev = batch.expectedPreviousRevision();

        if (firstRev != expectedPrevRev + 1) {
            throw new IllegalArgumentException("First revision in batch (" + firstRev
                + ") must immediately follow expected previous revision (" + expectedPrevRev + ")");
        }

        for (int i = 0; i < ops.size(); i++) {
            long expected = firstRev + i;
            if (ops.get(i).revision() != expected) {
                throw new IllegalArgumentException("Non-contiguous revision in batch at index " + i
                    + ": expected " + expected + " but got " + ops.get(i).revision());
            }
        }

        Document document = documentRepository.findById(batch.documentId())
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + batch.documentId()));

        if (!document.getSyncEpoch().equals(batch.syncEpoch())) {
            throw new IllegalArgumentException("Epoch mismatch: document is at epoch "
                + document.getSyncEpoch() + " but batch specifies " + batch.syncEpoch());
        }

        String jsonOperations;
        try {
            jsonOperations = objectMapper.writeValueAsString(Map.of("operations", ops));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize operations for batch", e);
        }

        UUID batchId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        DocumentOperationBatch batchEntity = new DocumentOperationBatch(
            batchId,
            document,
            batch.syncEpoch(),
            firstRev,
            lastRev,
            jsonOperations,
            ops.size(),
            batch.contentHashAfter(),
            now
        );

        DocumentOperationBatch savedBatch = batchRepository.save(batchEntity);

        List<DocumentOperationId> idEntities = new ArrayList<>(ops.size());
        for (PersistedCanonicalOperation op : ops) {
            DocumentOperationIdPk pk = new DocumentOperationIdPk(
                batch.documentId(),
                batch.syncEpoch(),
                op.clientId(),
                op.clientOperationId()
            );
            idEntities.add(new DocumentOperationId(pk, op.revision(), savedBatch, now));
        }
        operationIdRepository.saveAll(idEntities);

        int updatedRows = documentRepository.updateCurrentRevisionFenced(
            batch.documentId(),
            batch.syncEpoch(),
            expectedPrevRev,
            lastRev,
            now
        );

        if (updatedRows == 0) {
            throw new StaleRevisionFencingException(
                batch.documentId(),
                batch.syncEpoch(),
                expectedPrevRev,
                lastRev
            );
        }

        return savedBatch;
    }

    /**
     * Checks if an operation identity (documentId, syncEpoch, clientId, clientOperationId) has already been persisted.
     *
     * @param documentId Document ID.
     * @param syncEpoch Document synchronization epoch.
     * @param clientId Client UUID.
     * @param clientOperationId Client operation UUID.
     * @param incomingOp Optional incoming operation payload to check for conflicting identity reuse.
     * @return IdempotencyLookupResult indicating NOT_FOUND or DUPLICATE with previous canonical details.
     */
    @Transactional(readOnly = true)
    public IdempotencyLookupResult checkIdempotency(
            UUID documentId,
            UUID syncEpoch,
            UUID clientId,
            UUID clientOperationId,
            Operation incomingOp) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(syncEpoch, "syncEpoch must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");

        Optional<DocumentOperationId> existingIdOpt = operationIdRepository
            .findByIdDocumentIdAndIdSyncEpochAndIdClientIdAndIdClientOperationId(
                documentId,
                syncEpoch,
                clientId,
                clientOperationId
            );

        if (existingIdOpt.isEmpty()) {
            return IdempotencyLookupResult.notFound();
        }

        DocumentOperationId existingId = existingIdOpt.get();
        DocumentOperationBatch batch = existingId.getBatch();
        List<PersistedCanonicalOperation> opsInBatch = deserializeBatchOperations(batch.getOperations());

        PersistedCanonicalOperation matchingOp = opsInBatch.stream()
            .filter(op -> op.revision() == existingId.getRevision())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Operation ID row references revision "
                + existingId.getRevision() + " but batch " + batch.getId() + " does not contain it"));

        return IdempotencyLookupResult.duplicate(existingId.getRevision(), batch.getId(), matchingOp);
    }

    /**
     * Retrieves all canonical operations for a document within an epoch over a specified revision range.
     *
     * @param documentId Document ID.
     * @param syncEpoch Synchronization epoch.
     * @param fromRevisionExclusive The revision before the first requested operation (e.g. latest snapshot revision).
     * @param toRevisionInclusive The final requested revision.
     * @return Ordered contiguous list of PersistedCanonicalOperation objects.
     */
    @Transactional(readOnly = true)
    public List<PersistedCanonicalOperation> getCanonicalOperations(
            UUID documentId,
            UUID syncEpoch,
            long fromRevisionExclusive,
            long toRevisionInclusive) {
        if (fromRevisionExclusive >= toRevisionInclusive) {
            return Collections.emptyList();
        }

        List<DocumentOperationBatch> batches = batchRepository.findBatchesInRange(
            documentId,
            syncEpoch,
            fromRevisionExclusive + 1,
            toRevisionInclusive
        );

        List<PersistedCanonicalOperation> result = new ArrayList<>();
        for (DocumentOperationBatch batch : batches) {
            List<PersistedCanonicalOperation> ops = deserializeBatchOperations(batch.getOperations());
            for (PersistedCanonicalOperation op : ops) {
                if (op.revision() > fromRevisionExclusive && op.revision() <= toRevisionInclusive) {
                    result.add(op);
                }
            }
        }

        result.sort(Comparator.comparingLong(PersistedCanonicalOperation::revision));

        // Validate contiguous gap-free revisions
        long expected = fromRevisionExclusive + 1;
        for (PersistedCanonicalOperation op : result) {
            if (op.revision() != expected) {
                throw new RevisionGapException(documentId, syncEpoch, expected, op.revision());
            }
            expected++;
        }

        return result;
    }

    /**
     * Reconstructs current document content from the latest compatible snapshot plus later operation batches.
     *
     * @param documentId Document ID.
     * @return DocumentRecoveryResult containing recovered text, revision, and metadata.
     */
    @Transactional(readOnly = true)
    public DocumentRecoveryResult recoverDocument(UUID documentId) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        return recoverDocumentAtRevision(document.getId(), document.getSyncEpoch(), document.getCurrentRevision());
    }

    /**
     * Reconstructs document content at a target revision within a specific synchronization epoch.
     *
     * @param documentId Document ID.
     * @param syncEpoch Synchronization epoch.
     * @param targetRevision Target revision to reconstruct up to.
     * @return DocumentRecoveryResult.
     */
    @Transactional(readOnly = true)
    public DocumentRecoveryResult recoverDocumentAtRevision(UUID documentId, UUID syncEpoch, long targetRevision) {
        DocumentSnapshot baseSnapshot = snapshotRepository
            .findTopByDocumentIdAndSyncEpochAndRevisionLessThanEqualOrderByRevisionDesc(
                documentId,
                syncEpoch,
                targetRevision
            )
            .orElseThrow(() -> new IllegalStateException("No snapshot found for document " + documentId
                + " in epoch " + syncEpoch + " at or before revision " + targetRevision));

        long snapshotRevision = baseSnapshot.getRevision();
        String currentText = baseSnapshot.getContent();

        List<PersistedCanonicalOperation> opsToReplay = getCanonicalOperations(
            documentId,
            syncEpoch,
            snapshotRevision,
            targetRevision
        );

        for (PersistedCanonicalOperation op : opsToReplay) {
            currentText = DocumentApplier.apply(currentText, op.operation());
        }

        String finalHash = calculateSha256(currentText);

        return new DocumentRecoveryResult(
            documentId,
            syncEpoch,
            targetRevision,
            currentText,
            finalHash,
            baseSnapshot.getId(),
            snapshotRevision,
            opsToReplay.size()
        );
    }

    /**
     * Creates and saves a document snapshot (e.g. periodic or milestone snapshot).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentSnapshot createSnapshot(
            UUID documentId,
            UUID syncEpoch,
            long revision,
            String content,
            String contentHash,
            String reason) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        String hash = (contentHash != null && !contentHash.isBlank()) ? contentHash : calculateSha256(content);

        DocumentSnapshot snapshot = new DocumentSnapshot(
            UUID.randomUUID(),
            document,
            syncEpoch,
            revision,
            content,
            hash,
            OffsetDateTime.now()
        );

        return snapshotRepository.save(snapshot);
    }

    private List<PersistedCanonicalOperation> deserializeBatchOperations(String json) {
        try {
            Map<String, List<PersistedCanonicalOperation>> map = objectMapper.readValue(
                json,
                new TypeReference<>() {}
            );
            return map.getOrDefault("operations", Collections.emptyList());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize operation batch JSON", e);
        }
    }

    public static String calculateSha256(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

