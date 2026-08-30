package com.collaborativeeditor.controller;

import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.dto.document.CreateDocumentRequest;
import com.collaborativeeditor.dto.document.DocumentDetailDto;
import com.collaborativeeditor.dto.document.DocumentListResponse;
import com.collaborativeeditor.dto.document.DocumentPermissionDto;
import com.collaborativeeditor.dto.document.DocumentPermissionsListResponse;
import com.collaborativeeditor.dto.document.DocumentSummaryDto;
import com.collaborativeeditor.dto.document.GrantPermissionRequest;
import com.collaborativeeditor.dto.document.UpdateDocumentRequest;
import com.collaborativeeditor.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<DocumentDetailDto> createDocument(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateDocumentRequest request) {
        DocumentDetailDto response = documentService.createDocument(user, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<DocumentListResponse> listDocuments(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        DocumentListResponse response = documentService.listDocuments(user, limit, cursor);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentDetailDto> getDocument(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId) {
        DocumentDetailDto response = documentService.getDocument(user, documentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{documentId}")
    public ResponseEntity<DocumentSummaryDto> updateDocument(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId,
            @Valid @RequestBody UpdateDocumentRequest request) {
        DocumentSummaryDto response = documentService.updateDocument(user, documentId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId) {
        documentService.deleteDocument(user, documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{documentId}/permissions")
    public ResponseEntity<DocumentPermissionsListResponse> listPermissions(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId) {
        DocumentPermissionsListResponse response = documentService.listPermissions(user, documentId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{documentId}/permissions")
    public ResponseEntity<DocumentPermissionDto> grantPermission(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId,
            @Valid @RequestBody GrantPermissionRequest request) {
        DocumentPermissionDto response = documentService.grantPermission(user, documentId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @DeleteMapping("/{documentId}/permissions/{userId}")
    public ResponseEntity<Void> revokePermission(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId,
            @PathVariable UUID userId) {
        documentService.revokePermission(user, documentId, userId);
        return ResponseEntity.noContent().build();
    }
}

