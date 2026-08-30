package com.collaborativeeditor.domain.document;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Query("""
        SELECT d FROM Document d
        WHERE d.owner.id = :userId
           OR EXISTS (
               SELECT 1 FROM DocumentPermission p
               WHERE p.document.id = d.id AND p.user.id = :userId
           )
        ORDER BY d.updatedAt DESC, d.id DESC
    """)
    List<Document> findAccessibleDocumentsFirstPage(
            @Param("userId") UUID userId,
            Pageable pageable
    );

    @Query("""
        SELECT d FROM Document d
        WHERE (d.owner.id = :userId OR EXISTS (
                   SELECT 1 FROM DocumentPermission p
                   WHERE p.document.id = d.id AND p.user.id = :userId
              ))
          AND (d.updatedAt < :cursorUpdatedAt OR (d.updatedAt = :cursorUpdatedAt AND d.id < :cursorId))
        ORDER BY d.updatedAt DESC, d.id DESC
    """)
    List<Document> findAccessibleDocumentsAfterCursor(
            @Param("userId") UUID userId,
            @Param("cursorUpdatedAt") OffsetDateTime cursorUpdatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );
}

