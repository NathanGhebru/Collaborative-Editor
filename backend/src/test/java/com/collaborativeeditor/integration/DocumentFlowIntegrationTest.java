package com.collaborativeeditor.integration;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermission;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.dto.document.CreateDocumentRequest;
import com.collaborativeeditor.dto.document.DocumentDetailDto;
import com.collaborativeeditor.dto.document.DocumentListResponse;
import com.collaborativeeditor.dto.document.DocumentPermissionDto;
import com.collaborativeeditor.dto.document.DocumentPermissionsListResponse;
import com.collaborativeeditor.dto.document.DocumentSummaryDto;
import com.collaborativeeditor.dto.document.GrantPermissionRequest;
import com.collaborativeeditor.dto.document.UpdateDocumentRequest;
import com.collaborativeeditor.exception.ApiException;
import com.collaborativeeditor.exception.ErrorCode;
import com.collaborativeeditor.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
class DocumentFlowIntegrationTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentSnapshotRepository documentSnapshotRepository;

    @Autowired
    private DocumentPermissionRepository documentPermissionRepository;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User editor;
    private User outsider;

    @BeforeEach
    void setUp() {
        documentPermissionRepository.deleteAll();
        documentSnapshotRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        OffsetDateTime now = OffsetDateTime.now();
        owner = userRepository.save(new User(
                UUID.randomUUID(),
                "docowner",
                "owner@example.com",
                "hashedpwd",
                "Document Owner",
                AccountStatus.ACTIVE,
                now,
                now
        ));

        editor = userRepository.save(new User(
                UUID.randomUUID(),
                "doceditor",
                "editor@example.com",
                "hashedpwd",
                "Document Editor",
                AccountStatus.ACTIVE,
                now,
                now
        ));

        outsider = userRepository.save(new User(
                UUID.randomUUID(),
                "outsider",
                "outsider@example.com",
                "hashedpwd",
                "Outsider User",
                AccountStatus.ACTIVE,
                now,
                now
        ));
    }

    @Test
    @DisplayName("Complete document lifecycle: create, snapshot, list, rename, share, revoke, and cascade delete")
    void fullDocumentLifecycle() {
        // 1. Create document with initial content
        CreateDocumentRequest createReq = new CreateDocumentRequest("Architecture Spec", "Initial system design draft");
        DocumentDetailDto created = documentService.createDocument(owner, createReq);

        assertNotNull(created.id());
        assertEquals("Architecture Spec", created.title());
        assertEquals("Initial system design draft", created.content());
        assertEquals("OWNER", created.permission());
        assertEquals(0L, created.currentRevision());

        // Verify revision-0 snapshot was persisted atomically
        Optional<DocumentSnapshot> snapshotOpt = documentSnapshotRepository.findTopByDocumentIdOrderByRevisionDesc(created.id());
        assertTrue(snapshotOpt.isPresent());
        assertEquals(0L, snapshotOpt.get().getRevision());
        assertEquals("Initial system design draft", snapshotOpt.get().getContent());
        assertNotNull(snapshotOpt.get().getContentHash());

        // 2. List documents as owner
        DocumentListResponse listResponse = documentService.listDocuments(owner, 20, null);
        assertEquals(1, listResponse.documents().size());
        assertEquals("Architecture Spec", listResponse.documents().get(0).title());
        assertEquals("OWNER", listResponse.documents().get(0).permission());

        // 3. Rename document
        UpdateDocumentRequest updateReq = new UpdateDocumentRequest("Distributed Architecture Spec");
        DocumentSummaryDto updated = documentService.updateDocument(owner, created.id(), updateReq);
        assertEquals("Distributed Architecture Spec", updated.title());

        // 4. Grant EDITOR access to editor user
        GrantPermissionRequest grantReq = new GrantPermissionRequest("doceditor", "EDITOR");
        DocumentPermissionDto granted = documentService.grantPermission(owner, created.id(), grantReq);
        assertEquals("doceditor", granted.user().username());
        assertEquals("EDITOR", granted.role());

        // Verify editor can list and see document with EDITOR role
        DocumentListResponse editorList = documentService.listDocuments(editor, 20, null);
        assertEquals(1, editorList.documents().size());
        assertEquals("EDITOR", editorList.documents().get(0).permission());

        // Verify editor can open document
        DocumentDetailDto editorDetail = documentService.getDocument(editor, created.id());
        assertEquals("Distributed Architecture Spec", editorDetail.title());
        assertEquals("Initial system design draft", editorDetail.content());
        assertEquals("EDITOR", editorDetail.permission());

        // Verify editor can rename document
        DocumentSummaryDto editorUpdated = documentService.updateDocument(editor, created.id(), new UpdateDocumentRequest("Collaborative Spec"));
        assertEquals("Collaborative Spec", editorUpdated.title());

        // Verify editor CANNOT delete document (403 DOCUMENT_FORBIDDEN)
        ApiException forbiddenEx = assertThrows(ApiException.class, () -> documentService.deleteDocument(editor, created.id()));
        assertEquals(ErrorCode.DOCUMENT_FORBIDDEN, forbiddenEx.getErrorCode());

        // Verify outsider receives 404 DOCUMENT_NOT_FOUND (concealment policy)
        ApiException outsiderGetEx = assertThrows(ApiException.class, () -> documentService.getDocument(outsider, created.id()));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, outsiderGetEx.getErrorCode());

        ApiException outsiderDeleteEx = assertThrows(ApiException.class, () -> documentService.deleteDocument(outsider, created.id()));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, outsiderDeleteEx.getErrorCode());

        // 5. List permissions as owner
        DocumentPermissionsListResponse perms = documentService.listPermissions(owner, created.id());
        assertEquals(1, perms.permissions().size());
        assertEquals("doceditor", perms.permissions().get(0).user().username());

        // 6. Revoke permission
        documentService.revokePermission(owner, created.id(), editor.getId());
        DocumentListResponse editorListAfterRevoke = documentService.listDocuments(editor, 20, null);
        assertEquals(0, editorListAfterRevoke.documents().size());

        // Former editor now gets 404
        ApiException revokedGetEx = assertThrows(ApiException.class, () -> documentService.getDocument(editor, created.id()));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, revokedGetEx.getErrorCode());

        // 7. Owner deletes document
        documentService.deleteDocument(owner, created.id());
        assertFalse(documentRepository.findById(created.id()).isPresent());
        assertTrue(documentSnapshotRepository.findTopByDocumentIdOrderByRevisionDesc(created.id()).isEmpty());
    }

    @Test
    @DisplayName("Cursor pagination correctly pages across multiple documents")
    void cursorPagination() throws InterruptedException {
        for (int i = 1; i <= 5; i++) {
            documentService.createDocument(owner, new CreateDocumentRequest("Doc #" + i, "Content " + i));
            Thread.sleep(10); // Ensure distinct timestamps
        }

        // Fetch page 1 with limit 2
        DocumentListResponse page1 = documentService.listDocuments(owner, 2, null);
        assertEquals(2, page1.documents().size());
        assertNotNull(page1.nextCursor());

        // Fetch page 2 with limit 2 using nextCursor
        DocumentListResponse page2 = documentService.listDocuments(owner, 2, page1.nextCursor());
        assertEquals(2, page2.documents().size());
        assertNotNull(page2.nextCursor());
        assertNotEquals(page1.documents().get(0).id(), page2.documents().get(0).id());

        // Fetch page 3 with limit 2
        DocumentListResponse page3 = documentService.listDocuments(owner, 2, page2.nextCursor());
        assertEquals(1, page3.documents().size());
        assertNull(page3.nextCursor());
    }
}
