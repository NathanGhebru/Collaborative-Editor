package com.collaborativeeditor.controller;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermission;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import com.collaborativeeditor.dto.realtime.RealtimeTicketResponse;
import com.collaborativeeditor.exception.ApiException;
import com.collaborativeeditor.exception.ErrorCode;
import com.collaborativeeditor.service.realtime.RealtimeTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Controller handling real-time ticket issuance for authenticated clients prior to WebSocket connection.
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/realtime-ticket")
public class RealtimeTicketController {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTicketController.class);

    private final DocumentRepository documentRepository;
    private final DocumentPermissionRepository permissionRepository;
    private final RealtimeTicketService ticketService;

    public RealtimeTicketController(
            DocumentRepository documentRepository,
            DocumentPermissionRepository permissionRepository,
            RealtimeTicketService ticketService) {
        this.documentRepository = documentRepository;
        this.permissionRepository = permissionRepository;
        this.ticketService = ticketService;
    }

    @PostMapping
    public ResponseEntity<RealtimeTicketResponse> createRealtimeTicket(
            @AuthenticationPrincipal User user,
            @PathVariable("documentId") UUID documentId) {

        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required.");
        }

        // Find document
        Optional<Document> documentOpt = documentRepository.findById(documentId);
        if (documentOpt.isEmpty()) {
            throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "Document not found.");
        }

        Document document = documentOpt.get();

        // Determine user role (OWNER or EDITOR from document_permissions)
        DocumentRole role;
        if (document.getOwner().getId().equals(user.getId())) {
            role = DocumentRole.OWNER;
        } else {
            Optional<DocumentPermission> permOpt = permissionRepository.findByDocumentIdAndUserId(documentId, user.getId());
            if (permOpt.isEmpty()) {
                // Return 404 DOCUMENT_NOT_FOUND rather than 403 to avoid leaking document existence
                throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "Document not found.");
            }
            role = permOpt.get().getRole();
        }

        RealtimeTicket ticket = ticketService.issueTicket(documentId, user.getId(), role);
        String websocketPath = "/ws/v1/documents/" + documentId;

        log.debug("Issued realtime ticket for userId={} docId={} role={}", user.getId(), documentId, role);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RealtimeTicketResponse(ticket.ticket(), ticket.expiresAt(), websocketPath));
    }
}
