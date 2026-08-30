package com.collaborativeeditor.service.sequencing;

import com.collaborativeeditor.ot.model.Operation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result DTO returned after durable canonical operation acceptance or idempotent retry.
 */
public record AcceptedOperationResult(
    UUID documentId,
    UUID syncEpoch,
    long revision,
    UUID clientId,
    UUID clientOperationId,
    UUID actorUserId,
    Operation canonicalOperation,
    UUID batchId,
    boolean isDuplicate,
    OffsetDateTime acceptedAt
) {}
