package com.collaborativeeditor.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentSnapshotRepository extends JpaRepository<DocumentSnapshot, UUID> {

    Optional<DocumentSnapshot> findTopByDocumentIdOrderByRevisionDesc(UUID documentId);

    Optional<DocumentSnapshot> findTopByDocumentIdAndSyncEpochOrderByRevisionDesc(UUID documentId, UUID syncEpoch);

    Optional<DocumentSnapshot> findTopByDocumentIdAndSyncEpochAndRevisionLessThanEqualOrderByRevisionDesc(
            UUID documentId,
            UUID syncEpoch,
            Long revision
    );

    Optional<DocumentSnapshot> findByDocumentIdAndRevision(UUID documentId, Long revision);

    Optional<DocumentSnapshot> findByDocumentIdAndSyncEpochAndRevision(UUID documentId, UUID syncEpoch, Long revision);

    void deleteByDocumentId(UUID documentId);
}

