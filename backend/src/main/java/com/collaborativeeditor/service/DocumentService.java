package com.collaborativeeditor.service;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermission;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.dto.document.CreateDocumentRequest;
import com.collaborativeeditor.dto.document.CursorPayload;
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
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT.DOCUMENT");

    private final DocumentRepository documentRepository;
    private final DocumentSnapshotRepository documentSnapshotRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final UserRepository userRepository;
    private final OperationPersistenceService operationPersistenceService;
    private final ObjectMapper objectMapper;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentSnapshotRepository documentSnapshotRepository,
            DocumentPermissionRepository documentPermissionRepository,
            UserRepository userRepository,
            OperationPersistenceService operationPersistenceService,
            ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.documentSnapshotRepository = documentSnapshotRepository;
        this.documentPermissionRepository = documentPermissionRepository;
        this.userRepository = userRepository;
        this.operationPersistenceService = operationPersistenceService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DocumentDetailDto createDocument(User user, CreateDocumentRequest request) {
        String trimmedTitle = validateAndSanitizeTitle(request.getTitle());

        String initialContent = request.getInitialContent();
        if (initialContent == null) {
            initialContent = "";
        } else if (initialContent.length() > 1000000) {
            throw new ApiException(ErrorCode.DOCUMENT_TOO_LARGE);
        }

        UUID documentId = UUID.randomUUID();
        UUID syncEpoch = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        Document document = new Document(
                documentId,
                user,
                trimmedTitle,
                syncEpoch,
                0L,
                now,
                now
        );
        documentRepository.save(document);

        String contentHash = hashContent(initialContent);
        DocumentSnapshot snapshot = new DocumentSnapshot(
                UUID.randomUUID(),
                document,
                syncEpoch,
                0L,
                initialContent,
                contentHash,
                now
        );
        documentSnapshotRepository.save(snapshot);

        auditLog.info("DOCUMENT_CREATED docId={} ownerId={}", documentId, user.getId());

        return new DocumentDetailDto(
                document.getId(),
                document.getTitle(),
                initialContent,
                DocumentOwnerDto.fromUser(user),
                "OWNER",
                document.getCurrentRevision(),
                document.getSyncEpoch(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public DocumentListResponse listDocuments(User user, Integer limitParam, String cursorParam) {
        int limit = 20;
        if (limitParam != null) {
            limit = Math.max(1, Math.min(100, limitParam));
        }

        List<Document> documents;
        if (cursorParam != null && !cursorParam.isBlank()) {
            CursorPayload cursor = decodeCursor(cursorParam);
            documents = documentRepository.findAccessibleDocumentsAfterCursor(
                    user.getId(),
                    cursor.updatedAt(),
                    cursor.id(),
                    PageRequest.of(0, limit + 1)
            );
        } else {
            documents = documentRepository.findAccessibleDocumentsFirstPage(
                    user.getId(),
                    PageRequest.of(0, limit + 1)
            );
        }

        boolean hasMore = documents.size() > limit;
        List<Document> pageItems = hasMore ? documents.subList(0, limit) : documents;

        List<DocumentSummaryDto> dtos = pageItems.stream().map(doc -> {
            String role = doc.getOwner().getId().equals(user.getId()) ? "OWNER" : "EDITOR";
            return new DocumentSummaryDto(
                    doc.getId(),
                    doc.getTitle(),
                    DocumentOwnerDto.fromUser(doc.getOwner()),
                    role,
                    doc.getCurrentRevision(),
                    doc.getSyncEpoch(),
                    doc.getCreatedAt(),
                    doc.getUpdatedAt()
            );
        }).toList();

        String nextCursor = null;
        if (hasMore && !pageItems.isEmpty()) {
            Document lastDoc = pageItems.get(pageItems.size() - 1);
            nextCursor = encodeCursor(new CursorPayload(lastDoc.getUpdatedAt(), lastDoc.getId()));
        }

        return new DocumentListResponse(dtos, nextCursor);
    }

    @Transactional(readOnly = true)
    public DocumentDetailDto getDocument(User user, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        String role = resolveUserRole(document, user.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        String content;
        try {
            content = operationPersistenceService.recoverDocument(documentId).content();
        } catch (Exception e) {
            content = documentSnapshotRepository.findTopByDocumentIdOrderByRevisionDesc(documentId)
                    .map(DocumentSnapshot::getContent)
                    .orElse("");
        }

        return new DocumentDetailDto(
                document.getId(),
                document.getTitle(),
                content,
                DocumentOwnerDto.fromUser(document.getOwner()),
                role,
                document.getCurrentRevision(),
                document.getSyncEpoch(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    @Transactional
    public DocumentSummaryDto updateDocument(User user, UUID documentId, UpdateDocumentRequest request) {
        String trimmedTitle = validateAndSanitizeTitle(request.getTitle());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        String role = resolveUserRole(document, user.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        document.setTitle(trimmedTitle);
        document.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(document);

        auditLog.info("DOCUMENT_UPDATED docId={} userId={} role={}", documentId, user.getId(), role);

        return new DocumentSummaryDto(
                document.getId(),
                document.getTitle(),
                DocumentOwnerDto.fromUser(document.getOwner()),
                role,
                document.getCurrentRevision(),
                document.getSyncEpoch(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    @Transactional
    public void deleteDocument(User user, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!document.getOwner().getId().equals(user.getId())) {
            if (documentPermissionRepository.existsByDocumentIdAndUserId(documentId, user.getId())) {
                throw new ApiException(ErrorCode.DOCUMENT_FORBIDDEN);
            }
            throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        documentSnapshotRepository.deleteByDocumentId(documentId);
        documentPermissionRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        auditLog.info("DOCUMENT_DELETED docId={} ownerId={}", documentId, user.getId());
    }

    @Transactional(readOnly = true)
    public DocumentPermissionsListResponse listPermissions(User user, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!document.getOwner().getId().equals(user.getId())) {
            if (documentPermissionRepository.existsByDocumentIdAndUserId(documentId, user.getId())) {
                throw new ApiException(ErrorCode.DOCUMENT_FORBIDDEN);
            }
            throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        List<DocumentPermission> permissions = documentPermissionRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
        List<DocumentPermissionDto> permissionDtos = permissions.stream().map(p -> new DocumentPermissionDto(
                DocumentOwnerDto.fromUser(p.getUser()),
                p.getRole().name(),
                p.getCreatedAt()
        )).toList();

        return new DocumentPermissionsListResponse(
                DocumentOwnerDto.fromUser(document.getOwner()),
                permissionDtos
        );
    }

    @Transactional
    public DocumentPermissionDto grantPermission(User user, UUID documentId, GrantPermissionRequest request) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!document.getOwner().getId().equals(user.getId())) {
            if (documentPermissionRepository.existsByDocumentIdAndUserId(documentId, user.getId())) {
                throw new ApiException(ErrorCode.DOCUMENT_FORBIDDEN);
            }
            throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        if (request.getRole() == null || !"EDITOR".equalsIgnoreCase(request.getRole().trim())) {
            throw new ApiException(ErrorCode.INVALID_ROLE);
        }

        String identifier = request.getUserIdentifier().trim().toLowerCase();
        User targetUser = userRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        if (targetUser.getId().equals(document.getOwner().getId())) {
            throw new ApiException(ErrorCode.ALREADY_HAS_ACCESS);
        }

        if (documentPermissionRepository.existsByDocumentIdAndUserId(documentId, targetUser.getId())) {
            throw new ApiException(ErrorCode.ALREADY_HAS_ACCESS);
        }

        OffsetDateTime now = OffsetDateTime.now();
        DocumentPermission permission = new DocumentPermission(
                UUID.randomUUID(),
                document,
                targetUser,
                DocumentRole.EDITOR,
                user,
                now,
                now
        );
        documentPermissionRepository.save(permission);

        auditLog.info("PERMISSION_GRANTED docId={} ownerId={} targetUserId={}", documentId, user.getId(), targetUser.getId());

        return new DocumentPermissionDto(
                DocumentOwnerDto.fromUser(targetUser),
                "EDITOR",
                now
        );
    }

    @Transactional
    public void revokePermission(User user, UUID documentId, UUID targetUserId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!document.getOwner().getId().equals(user.getId())) {
            if (documentPermissionRepository.existsByDocumentIdAndUserId(documentId, user.getId())) {
                throw new ApiException(ErrorCode.DOCUMENT_FORBIDDEN);
            }
            throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        DocumentPermission permission = documentPermissionRepository.findByDocumentIdAndUserId(documentId, targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERMISSION_NOT_FOUND));

        documentPermissionRepository.delete(permission);
        auditLog.info("PERMISSION_REVOKED docId={} ownerId={} targetUserId={}", documentId, user.getId(), targetUserId);
    }

    private Optional<String> resolveUserRole(Document document, UUID userId) {
        if (document.getOwner().getId().equals(userId)) {
            return Optional.of("OWNER");
        }
        if (documentPermissionRepository.existsByDocumentIdAndUserId(document.getId(), userId)) {
            return Optional.of("EDITOR");
        }
        return Optional.empty();
    }

    private String validateAndSanitizeTitle(String title) {
        if (title == null) {
            throw new ApiException(ErrorCode.INVALID_TITLE);
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255 || trimmed.contains("\r") || trimmed.contains("\n")) {
            throw new ApiException(ErrorCode.INVALID_TITLE);
        }
        return trimmed;
    }

    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String encodeCursor(CursorPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to encode pagination cursor", e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private CursorPayload decodeCursor(String cursorStr) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursorStr);
            return objectMapper.readValue(decoded, CursorPayload.class);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_CURSOR);
        }
    }
}

