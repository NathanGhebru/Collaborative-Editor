package com.collaborativeeditor.service;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermission;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
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
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentSnapshotRepository documentSnapshotRepository;

    @Mock
    private DocumentPermissionRepository documentPermissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OperationPersistenceService operationPersistenceService;

    private ObjectMapper objectMapper;
    private DocumentService documentService;

    private User owner;
    private User collaborator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        documentService = new DocumentService(
                documentRepository,
                documentSnapshotRepository,
                documentPermissionRepository,
                userRepository,
                operationPersistenceService,
                objectMapper
        );

        owner = new User(
                UUID.randomUUID(),
                "owner_user",
                "owner@example.com",
                "hash",
                "Owner User",
                AccountStatus.ACTIVE,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        collaborator = new User(
                UUID.randomUUID(),
                "collab_user",
                "collab@example.com",
                "hash",
                "Collab User",
                AccountStatus.ACTIVE,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    @Test
    @DisplayName("createDocument atomically persists document metadata and revision-0 snapshot")
    void createDocument_success() {
        CreateDocumentRequest request = new CreateDocumentRequest("Distributed Systems", "Initial draft content");

        DocumentDetailDto result = documentService.createDocument(owner, request);

        assertNotNull(result);
        assertEquals("Distributed Systems", result.title());
        assertEquals("Initial draft content", result.content());
        assertEquals("OWNER", result.permission());
        assertEquals(0L, result.currentRevision());
        assertNotNull(result.syncEpoch());

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(1)).save(docCaptor.capture());
        assertEquals("Distributed Systems", docCaptor.getValue().getTitle());
        assertEquals(owner.getId(), docCaptor.getValue().getOwner().getId());

        ArgumentCaptor<DocumentSnapshot> snapCaptor = ArgumentCaptor.forClass(DocumentSnapshot.class);
        verify(documentSnapshotRepository, times(1)).save(snapCaptor.capture());
        assertEquals(0L, snapCaptor.getValue().getRevision());
        assertEquals("Initial draft content", snapCaptor.getValue().getContent());
        assertNotNull(snapCaptor.getValue().getContentHash());
    }

    @Test
    @DisplayName("createDocument defaults to empty string content when initialContent is omitted")
    void createDocument_emptyContentDefault() {
        CreateDocumentRequest request = new CreateDocumentRequest("Empty Doc");

        DocumentDetailDto result = documentService.createDocument(owner, request);

        assertEquals("", result.content());
        ArgumentCaptor<DocumentSnapshot> snapCaptor = ArgumentCaptor.forClass(DocumentSnapshot.class);
        verify(documentSnapshotRepository, times(1)).save(snapCaptor.capture());
        assertEquals("", snapCaptor.getValue().getContent());
    }

    @Test
    @DisplayName("createDocument throws INVALID_TITLE when title is blank or contains newlines")
    void createDocument_invalidTitle() {
        CreateDocumentRequest blankRequest = new CreateDocumentRequest("   ");
        ApiException ex1 = assertThrows(ApiException.class, () -> documentService.createDocument(owner, blankRequest));
        assertEquals(ErrorCode.INVALID_TITLE, ex1.getErrorCode());

        CreateDocumentRequest newlineRequest = new CreateDocumentRequest("Title with\nline break");
        ApiException ex2 = assertThrows(ApiException.class, () -> documentService.createDocument(owner, newlineRequest));
        assertEquals(ErrorCode.INVALID_TITLE, ex2.getErrorCode());
    }

    @Test
    @DisplayName("listDocuments returns accessible documents with OWNER and EDITOR roles and calculates nextCursor")
    void listDocuments_success() {
        UUID doc1Id = UUID.randomUUID();
        UUID doc2Id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        Document doc1 = new Document(doc1Id, owner, "Doc 1", UUID.randomUUID(), 0L, now, now);
        Document doc2 = new Document(doc2Id, collaborator, "Doc 2", UUID.randomUUID(), 5L, now, now.minusMinutes(1));

        when(documentRepository.findAccessibleDocumentsFirstPage(eq(owner.getId()), any(Pageable.class)))
                .thenReturn(List.of(doc1, doc2));

        DocumentListResponse response = documentService.listDocuments(owner, 20, null);

        assertNotNull(response);
        assertEquals(2, response.documents().size());
        assertEquals("OWNER", response.documents().get(0).permission());
        assertEquals("EDITOR", response.documents().get(1).permission());
        assertNull(response.nextCursor());
    }

    @Test
    @DisplayName("getDocument returns document detail for owner")
    void getDocument_owner() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Notes", UUID.randomUUID(), 10L, OffsetDateTime.now(), OffsetDateTime.now());
        DocumentSnapshot snapshot = new DocumentSnapshot(UUID.randomUUID(), doc, doc.getSyncEpoch(), 10L, "Snapshot text", "hash", OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(documentSnapshotRepository.findTopByDocumentIdOrderByRevisionDesc(docId)).thenReturn(Optional.of(snapshot));

        DocumentDetailDto result = documentService.getDocument(owner, docId);

        assertNotNull(result);
        assertEquals("Notes", result.title());
        assertEquals("Snapshot text", result.content());
        assertEquals("OWNER", result.permission());
    }

    @Test
    @DisplayName("getDocument returns document detail for editor")
    void getDocument_editor() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Notes", UUID.randomUUID(), 10L, OffsetDateTime.now(), OffsetDateTime.now());
        DocumentSnapshot snapshot = new DocumentSnapshot(UUID.randomUUID(), doc, doc.getSyncEpoch(), 10L, "Snapshot text", "hash", OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(documentPermissionRepository.existsByDocumentIdAndUserId(docId, collaborator.getId())).thenReturn(true);
        when(documentSnapshotRepository.findTopByDocumentIdOrderByRevisionDesc(docId)).thenReturn(Optional.of(snapshot));

        DocumentDetailDto result = documentService.getDocument(collaborator, docId);

        assertNotNull(result);
        assertEquals("EDITOR", result.permission());
    }

    @Test
    @DisplayName("getDocument conceals unauthorized documents by throwing DOCUMENT_NOT_FOUND")
    void getDocument_unauthorized_concealment() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Private Doc", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(documentPermissionRepository.existsByDocumentIdAndUserId(docId, collaborator.getId())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> documentService.getDocument(collaborator, docId));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateDocument updates title for owner")
    void updateDocument_owner() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Old Title", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        UpdateDocumentRequest request = new UpdateDocumentRequest("New Title");
        DocumentSummaryDto result = documentService.updateDocument(owner, docId, request);

        assertEquals("New Title", result.title());
        verify(documentRepository, times(1)).save(doc);
    }

    @Test
    @DisplayName("deleteDocument allows owner to delete")
    void deleteDocument_owner() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "To Delete", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(owner, docId);

        verify(documentRepository, times(1)).delete(doc);
    }

    @Test
    @DisplayName("deleteDocument throws DOCUMENT_FORBIDDEN when editor attempts delete")
    void deleteDocument_editor_forbidden() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "To Delete", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(documentPermissionRepository.existsByDocumentIdAndUserId(docId, collaborator.getId())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> documentService.deleteDocument(collaborator, docId));
        assertEquals(ErrorCode.DOCUMENT_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("grantPermission allows owner to grant EDITOR access")
    void grantPermission_success() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Shared Doc", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(userRepository.findByIdentifier("collab_user")).thenReturn(Optional.of(collaborator));
        when(documentPermissionRepository.existsByDocumentIdAndUserId(docId, collaborator.getId())).thenReturn(false);

        GrantPermissionRequest request = new GrantPermissionRequest("collab_user", "EDITOR");
        DocumentPermissionDto result = documentService.grantPermission(owner, docId, request);

        assertNotNull(result);
        assertEquals("collab_user", result.user().username());
        assertEquals("EDITOR", result.role());
        verify(documentPermissionRepository, times(1)).save(any(DocumentPermission.class));
    }

    @Test
    @DisplayName("grantPermission throws ALREADY_HAS_ACCESS when user is already editor")
    void grantPermission_alreadyHasAccess() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Shared Doc", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(userRepository.findByIdentifier("collab_user")).thenReturn(Optional.of(collaborator));
        when(documentPermissionRepository.existsByDocumentIdAndUserId(docId, collaborator.getId())).thenReturn(true);

        GrantPermissionRequest request = new GrantPermissionRequest("collab_user", "EDITOR");
        ApiException ex = assertThrows(ApiException.class, () -> documentService.grantPermission(owner, docId, request));
        assertEquals(ErrorCode.ALREADY_HAS_ACCESS, ex.getErrorCode());
    }

    @Test
    @DisplayName("revokePermission allows owner to revoke access")
    void revokePermission_success() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document(docId, owner, "Shared Doc", UUID.randomUUID(), 0L, OffsetDateTime.now(), OffsetDateTime.now());
        DocumentPermission permission = new DocumentPermission(UUID.randomUUID(), doc, collaborator, DocumentRole.EDITOR, owner, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(documentPermissionRepository.findByDocumentIdAndUserId(docId, collaborator.getId())).thenReturn(Optional.of(permission));

        documentService.revokePermission(owner, docId, collaborator.getId());

        verify(documentPermissionRepository, times(1)).delete(permission);
    }
}

