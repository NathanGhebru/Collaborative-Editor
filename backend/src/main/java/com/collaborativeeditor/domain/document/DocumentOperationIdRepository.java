package com.collaborativeeditor.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentOperationIdRepository extends JpaRepository<DocumentOperationId, DocumentOperationIdPk> {

    Optional<DocumentOperationId> findByIdDocumentIdAndIdSyncEpochAndIdClientIdAndIdClientOperationId(
            UUID documentId,
            UUID syncEpoch,
            UUID clientId,
            UUID clientOperationId
    );

    void deleteByIdDocumentId(UUID documentId);
}

