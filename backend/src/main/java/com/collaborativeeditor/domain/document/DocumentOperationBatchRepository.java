package com.collaborativeeditor.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentOperationBatchRepository extends JpaRepository<DocumentOperationBatch, UUID> {

    @Query("SELECT b FROM DocumentOperationBatch b WHERE b.document.id = :documentId AND b.syncEpoch = :syncEpoch AND b.lastRevision > :afterRevision ORDER BY b.firstRevision ASC")
    List<DocumentOperationBatch> findBatchesAfterRevision(
            @Param("documentId") UUID documentId,
            @Param("syncEpoch") UUID syncEpoch,
            @Param("afterRevision") Long afterRevision
    );

    @Query("SELECT b FROM DocumentOperationBatch b WHERE b.document.id = :documentId AND b.syncEpoch = :syncEpoch AND b.lastRevision >= :fromRevision AND b.firstRevision <= :toRevision ORDER BY b.firstRevision ASC")
    List<DocumentOperationBatch> findBatchesInRange(
            @Param("documentId") UUID documentId,
            @Param("syncEpoch") UUID syncEpoch,
            @Param("fromRevision") Long fromRevision,
            @Param("toRevision") Long toRevision
    );

    Optional<DocumentOperationBatch> findByDocumentIdAndSyncEpochAndFirstRevision(
            UUID documentId,
            UUID syncEpoch,
            Long firstRevision
    );

    Optional<DocumentOperationBatch> findByDocumentIdAndSyncEpochAndLastRevision(
            UUID documentId,
            UUID syncEpoch,
            Long lastRevision
    );

    void deleteByDocumentId(UUID documentId);
}

