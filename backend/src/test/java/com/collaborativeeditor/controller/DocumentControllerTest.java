package com.collaborativeeditor.controller;

import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.dto.document.CreateDocumentRequest;
import com.collaborativeeditor.dto.document.DocumentDetailDto;
import com.collaborativeeditor.dto.document.DocumentListResponse;
import com.collaborativeeditor.dto.document.DocumentOwnerDto;
import com.collaborativeeditor.dto.document.DocumentPermissionDto;
import com.collaborativeeditor.dto.document.DocumentPermissionsListResponse;
import com.collaborativeeditor.dto.document.DocumentSummaryDto;
import com.collaborativeeditor.dto.document.GrantPermissionRequest;
import com.collaborativeeditor.dto.document.UpdateDocumentRequest;
import com.collaborativeeditor.exception.ApiException;
import com.collaborativeeditor.exception.ErrorCode;
import com.collaborativeeditor.security.JwtAuthenticationFilter;
import com.collaborativeeditor.security.JwtTokenProvider;
import com.collaborativeeditor.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = DocumentController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    private final User testUser = new User(
            UUID.randomUUID(),
            "testuser",
            "test@example.com",
            "hash",
            "Test User",
            AccountStatus.ACTIVE,
            OffsetDateTime.now(),
            OffsetDateTime.now()
    );

    @Test
    @DisplayName("POST /api/v1/documents returns 201 Created with document detail")
    void createDocument_success() throws Exception {
        UUID docId = UUID.randomUUID();
        CreateDocumentRequest request = new CreateDocumentRequest("New Document", "Initial Content");
        DocumentDetailDto response = new DocumentDetailDto(
                docId,
                "New Document",
                "Initial Content",
                DocumentOwnerDto.fromUser(testUser),
                "OWNER",
                0L,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(documentService.createDocument(any(), any(CreateDocumentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/documents")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(docId.toString()))
                .andExpect(jsonPath("$.title").value("New Document"))
                .andExpect(jsonPath("$.permission").value("OWNER"))
                .andExpect(jsonPath("$.currentRevision").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/documents with invalid title returns 422 INVALID_TITLE")
    void createDocument_invalidTitle() throws Exception {
        CreateDocumentRequest request = new CreateDocumentRequest("   ");

        mockMvc.perform(post("/api/v1/documents")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_TITLE"));
    }

    @Test
    @DisplayName("GET /api/v1/documents returns 200 OK with document list")
    void listDocuments_success() throws Exception {
        DocumentSummaryDto summary = new DocumentSummaryDto(
                UUID.randomUUID(),
                "My Doc",
                DocumentOwnerDto.fromUser(testUser),
                "OWNER",
                5L,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        DocumentListResponse response = new DocumentListResponse(List.of(summary), null);

        when(documentService.listDocuments(any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents")
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].title").value("My Doc"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 200 OK with document detail")
    void getDocument_success() throws Exception {
        UUID docId = UUID.randomUUID();
        DocumentDetailDto response = new DocumentDetailDto(
                docId,
                "Sample Doc",
                "Body content",
                DocumentOwnerDto.fromUser(testUser),
                "OWNER",
                1L,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(documentService.getDocument(any(), eq(docId))).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents/{documentId}", docId)
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(docId.toString()))
                .andExpect(jsonPath("$.title").value("Sample Doc"));
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 404 DOCUMENT_NOT_FOUND when non-owner/non-editor")
    void getDocument_notFound() throws Exception {
        UUID docId = UUID.randomUUID();
        when(documentService.getDocument(any(), eq(docId)))
                .thenThrow(new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/documents/{documentId}", docId)
                        .with(user(testUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /api/v1/documents/{id} returns 200 OK with updated summary")
    void updateDocument_success() throws Exception {
        UUID docId = UUID.randomUUID();
        UpdateDocumentRequest request = new UpdateDocumentRequest("Updated Title");
        DocumentSummaryDto response = new DocumentSummaryDto(
                docId,
                "Updated Title",
                DocumentOwnerDto.fromUser(testUser),
                "OWNER",
                1L,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(documentService.updateDocument(any(), eq(docId), any(UpdateDocumentRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/documents/{documentId}", docId)
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"));
    }

    @Test
    @DisplayName("DELETE /api/v1/documents/{id} returns 204 No Content")
    void deleteDocument_success() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/documents/{documentId}", docId)
                        .with(user(testUser)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id}/permissions returns 200 OK with permissions list")
    void listPermissions_success() throws Exception {
        UUID docId = UUID.randomUUID();
        DocumentPermissionsListResponse response = new DocumentPermissionsListResponse(
                DocumentOwnerDto.fromUser(testUser),
                List.of(new DocumentPermissionDto(
                        new DocumentOwnerDto(UUID.randomUUID(), "collab", "Collaborator"),
                        "EDITOR",
                        OffsetDateTime.now()
                ))
        );

        when(documentService.listPermissions(any(), eq(docId))).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents/{documentId}/permissions", docId)
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.username").value("testuser"))
                .andExpect(jsonPath("$.permissions[0].role").value("EDITOR"));
    }

    @Test
    @DisplayName("POST /api/v1/documents/{id}/permissions returns 201 Created with granted permission")
    void grantPermission_success() throws Exception {
        UUID docId = UUID.randomUUID();
        GrantPermissionRequest request = new GrantPermissionRequest("collab@example.com", "EDITOR");
        DocumentPermissionDto response = new DocumentPermissionDto(
                new DocumentOwnerDto(UUID.randomUUID(), "collab", "Collaborator"),
                "EDITOR",
                OffsetDateTime.now()
        );

        when(documentService.grantPermission(any(), eq(docId), any(GrantPermissionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/documents/{documentId}/permissions", docId)
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }

    @Test
    @DisplayName("DELETE /api/v1/documents/{id}/permissions/{userId} returns 204 No Content")
    void revokePermission_success() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/documents/{documentId}/permissions/{userId}", docId, targetUserId)
                        .with(user(testUser)))
                .andExpect(status().isNoContent());
    }
}

