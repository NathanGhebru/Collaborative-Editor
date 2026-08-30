package com.collaborativeeditor.service.persistence;

import java.util.UUID;

/**
 * Result of document reconstruction from snapshot + operation log.
 */
public record DocumentRecoveryResult(
    UUID documentId,
    UUID syncEpoch,
    long revision,
    String content,
    String contentHash,
    UUID baseSnapshotId,
    long baseSnapshotRevision,
    int operationsReplayed
) {}

