package com.collaborativeeditor.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, UUID> {

    Optional<DocumentPermission> findByDocumentIdAndUserId(UUID documentId, UUID userId);

    List<DocumentPermission> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);

    boolean existsByDocumentIdAndUserId(UUID documentId, UUID userId);

    void deleteByDocumentIdAndUserId(UUID documentId, UUID userId);
}

