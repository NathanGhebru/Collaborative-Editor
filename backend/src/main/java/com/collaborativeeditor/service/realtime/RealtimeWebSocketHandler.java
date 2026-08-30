package com.collaborativeeditor.service.realtime;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.dto.realtime.ClientHelloPayload;
import com.collaborativeeditor.dto.realtime.ClientOperationPayload;
import com.collaborativeeditor.dto.realtime.RealtimeMessageEnvelope;
import com.collaborativeeditor.dto.realtime.ServerErrorPayload;
import com.collaborativeeditor.dto.realtime.ServerOperationRejectedPayload;
import com.collaborativeeditor.dto.realtime.ServerOperationsPayload;
import com.collaborativeeditor.dto.realtime.ServerReadyPayload;
import com.collaborativeeditor.dto.realtime.ServerResyncRequiredPayload;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.service.persistence.IdempotencyConflictException;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.sequencing.AcceptedOperationResult;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
import com.collaborativeeditor.service.sequencing.EpochMismatchException;
import com.collaborativeeditor.service.sequencing.FutureRevisionException;
import com.collaborativeeditor.service.sequencing.SequencerOperationRejectedException;
import com.collaborativeeditor.service.sequencing.SubmitOperationCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TextWebSocketHandler implementing the frozen RT-001 / RT-002 WebSocket collaboration lifecycle.
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private static final int MAX_FRAME_PAYLOAD_SIZE = 65536;

    private final RealtimeSessionRegistry sessionRegistry;
    private final DocumentRepository documentRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final OperationPersistenceService persistenceService;
    private final DocumentSequencingService sequencingService;
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(
            RealtimeSessionRegistry sessionRegistry,
            DocumentRepository documentRepository,
            DocumentPermissionRepository documentPermissionRepository,
            OperationPersistenceService persistenceService,
            DocumentSequencingService sequencingService,
            ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.documentRepository = documentRepository;
        this.documentPermissionRepository = documentPermissionRepository;
        this.persistenceService = persistenceService;
        this.sequencingService = sequencingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID documentId = (UUID) session.getAttributes().get(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID);
        UUID userId = (UUID) session.getAttributes().get(RealtimeHandshakeInterceptor.ATTR_USER_ID);
        DocumentRole role = (DocumentRole) session.getAttributes().get(RealtimeHandshakeInterceptor.ATTR_ROLE);
        UUID connectionId = (UUID) session.getAttributes().get(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID);

        RealtimeSession realtimeSession = new RealtimeSession(session, connectionId, documentId, userId, role);
        session.getAttributes().put("REALTIME_SESSION", realtimeSession);
        sessionRegistry.registerSession(realtimeSession);
        log.debug("WebSocket connection established connectionId={} docId={} userId={}", connectionId, documentId, userId);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RealtimeSession realtimeSession = (RealtimeSession) session.getAttributes().get("REALTIME_SESSION");
        if (realtimeSession == null) {
            session.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        if (message.getPayloadLength() > MAX_FRAME_PAYLOAD_SIZE) {
            sendServerError(realtimeSession, "PAYLOAD_TOO_LARGE", "Frame size exceeds 64 KB limit.", true, 1009);
            realtimeSession.close(new CloseStatus(1009, "PAYLOAD_TOO_LARGE"));
            return;
        }

        RealtimeMessageEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), RealtimeMessageEnvelope.class);
        } catch (Exception e) {
            log.warn("Malformed JSON frame from connectionId={}: {}", realtimeSession.getConnectionId(), e.getMessage());
            sendServerError(realtimeSession, "INVALID_MESSAGE", "Malformed JSON text frame.", true, 4000);
            realtimeSession.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        if (envelope == null || envelope.protocolVersion() == null) {
            sendServerError(realtimeSession, "BAD_REQUEST", "Missing required envelope fields.", true, 4000);
            realtimeSession.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        if (envelope.protocolVersion() != 1) {
            sendServerError(realtimeSession, "UNSUPPORTED_PROTOCOL_VERSION", "Unsupported protocol version.", true, 4002);
            realtimeSession.close(new CloseStatus(4002, "UNSUPPORTED_PROTOCOL_VERSION"));
            return;
        }

        if (envelope.type() == null || envelope.messageId() == null || envelope.documentId() == null || envelope.timestamp() == null) {
            sendServerError(realtimeSession, "BAD_REQUEST", "Missing required envelope fields.", true, 4000);
            realtimeSession.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        if (!envelope.documentId().equals(realtimeSession.getDocumentId())) {
            sendServerError(realtimeSession, "INVALID_MESSAGE", "Document ID mismatch on room socket.", true, 4000);
            realtimeSession.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        switch (envelope.type()) {
            case "client.hello" -> handleClientHello(realtimeSession, envelope);
            case "client.operation" -> handleClientOperation(realtimeSession, envelope);
            default -> {
                log.warn("Unknown message type {} from connectionId={}", envelope.type(), realtimeSession.getConnectionId());
                sendServerError(realtimeSession, "INVALID_MESSAGE", "Unknown message type: " + envelope.type(), false, null);
            }
        }
    }

    private void handleClientHello(RealtimeSession session, RealtimeMessageEnvelope envelope) throws IOException {
        if (session.getState() != RealtimeSession.State.AWAITING_HELLO) {
            sendServerError(session, "INVALID_MESSAGE", "client.hello already received on this connection.", true, 4000);
            session.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        if (envelope.clientId() == null) {
            sendServerError(session, "INVALID_MESSAGE", "Missing clientId in client.hello envelope.", true, 4000);
            session.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        // Bind clientId to session, superseding any older connection for the same clientId
        sessionRegistry.bindClientId(session, envelope.clientId());

        ClientHelloPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), ClientHelloPayload.class);
        } catch (Exception e) {
            sendServerError(session, "INVALID_MESSAGE", "Invalid client.hello payload.", true, 4000);
            session.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        if (payload == null || payload.knownEpoch() == null || payload.knownRevision() == null) {
            sendServerError(session, "INVALID_MESSAGE", "Missing required fields in client.hello payload.", true, 4000);
            session.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        Optional<Document> docOpt = documentRepository.findById(session.getDocumentId());
        if (docOpt.isEmpty()) {
            sendServerError(session, "DOCUMENT_NOT_FOUND", "Document not found or deleted.", true, 4003);
            session.close(new CloseStatus(4003, "DOCUMENT_DELETED"));
            return;
        }

        Document document = docOpt.get();

        // Validate user permission still active
        if (!hasActiveDocumentPermission(document, session.getUserId())) {
            sendServerError(session, "DOCUMENT_FORBIDDEN", "Access to document has been revoked.", true, 4001);
            session.close(new CloseStatus(4001, "UNAUTHORIZED"));
            return;
        }

        // Validate syncEpoch
        if (!document.getSyncEpoch().equals(payload.knownEpoch())) {
            sendServerResyncRequired(session, "EPOCH_MISMATCH");
            return;
        }

        long knownRev = payload.knownRevision();
        long currentRev = document.getCurrentRevision();

        if (knownRev < 0) {
            sendServerResyncRequired(session, "PROTOCOL_ERROR");
            return;
        }

        if (knownRev > currentRev) {
            sendServerResyncRequired(session, "REVISION_AHEAD");
            return;
        }

        // If client is behind, deliver contiguous catch-up operations before server.ready
        if (knownRev < currentRev) {
            List<PersistedCanonicalOperation> catchUpOps = persistenceService.getCanonicalOperations(
                    document.getId(),
                    document.getSyncEpoch(),
                    knownRev,
                    currentRev
            );

            if (!catchUpOps.isEmpty()) {
                sendServerOperations(session, document.getId(), document.getSyncEpoch(), catchUpOps);
            }
        }

        // Send server.ready confirmation
        sendServerReady(session, document.getId(), document.getSyncEpoch(), currentRev, session.getRole().name());
        session.setState(RealtimeSession.State.ACTIVE);
    }

    private void handleClientOperation(RealtimeSession session, RealtimeMessageEnvelope envelope) throws IOException {
        if (session.getState() != RealtimeSession.State.ACTIVE) {
            sendServerError(session, "INVALID_MESSAGE", "Cannot submit edits before receiving server.ready.", false, null);
            return;
        }

        if (envelope.clientId() == null || !envelope.clientId().equals(session.getClientId())) {
            sendServerError(session, "INVALID_MESSAGE", "clientId does not match bound session identity.", true, 4000);
            session.close(new CloseStatus(4000, "BAD_REQUEST"));
            return;
        }

        ClientOperationPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), ClientOperationPayload.class);
        } catch (Exception e) {
            sendServerOperationRejected(session, envelope.syncEpoch(), null, "INVALID_OPERATION", "Malformed client.operation payload.");
            return;
        }

        if (payload == null || payload.clientOperationId() == null || payload.baseRevision() == null || payload.operation() == null) {
            sendServerOperationRejected(session, envelope.syncEpoch(),
                    payload != null ? payload.clientOperationId() : null,
                    "INVALID_OPERATION", "Missing required fields in client.operation payload.");
            return;
        }

        if (payload.operation() instanceof GroupOperation || payload.operation() instanceof NoOpOperation) {
            sendServerOperationRejected(session, envelope.syncEpoch(), payload.clientOperationId(),
                    "INVALID_OPERATION", "Clients cannot submit NO_OP or GROUP operations as new edits.");
            return;
        }

        Optional<Document> docOpt = documentRepository.findById(session.getDocumentId());
        if (docOpt.isEmpty()) {
            sendServerError(session, "DOCUMENT_NOT_FOUND", "Document deleted.", true, 4003);
            session.close(new CloseStatus(4003, "DOCUMENT_DELETED"));
            return;
        }

        Document document = docOpt.get();
        if (!hasActiveDocumentPermission(document, session.getUserId())) {
            sendServerError(session, "DOCUMENT_FORBIDDEN", "Permission revoked.", true, 4001);
            session.close(new CloseStatus(4001, "UNAUTHORIZED"));
            return;
        }

        SubmitOperationCommand command = new SubmitOperationCommand(
                session.getDocumentId(),
                envelope.syncEpoch(),
                session.getClientId(),
                payload.clientOperationId(),
                session.getUserId(),
                payload.baseRevision(),
                payload.operation()
        );

        try {
            AcceptedOperationResult result = sequencingService.submitOperation(command);

            // Construct canonical server.operations payload
            PersistedCanonicalOperation canonicalItem = new PersistedCanonicalOperation(
                    result.revision(),
                    result.clientId(),
                    result.clientOperationId(),
                    result.actorUserId(),
                    result.canonicalOperation()
            );

            String broadcastJson = buildEnvelopeJson(
                    "server.operations",
                    session.getDocumentId(),
                    session.getDocumentId(),
                    result.syncEpoch(),
                    new ServerOperationsPayload(List.of(canonicalItem))
            );

            // Broadcast single-stream canonical accepted operation to ALL active room subscribers (including sender)
            sessionRegistry.broadcastToRoom(session.getDocumentId(), broadcastJson);

        } catch (SequencerOperationRejectedException e) {
            sendServerOperationRejected(session, envelope.syncEpoch(), payload.clientOperationId(), e.getRejectionCode(), e.getMessage());
        } catch (EpochMismatchException e) {
            sendServerResyncRequired(session, "EPOCH_MISMATCH");
        } catch (FutureRevisionException e) {
            sendServerResyncRequired(session, "REVISION_AHEAD");
        } catch (IdempotencyConflictException e) {
            sendServerOperationRejected(session, envelope.syncEpoch(), payload.clientOperationId(), "IDENTITY_CONFLICT", e.getMessage());
        } catch (Exception e) {
            log.error("Internal sequencing exception for connectionId={}: {}", session.getConnectionId(), e.getMessage(), e);
            sendServerError(session, "INTERNAL_ERROR", "An internal error occurred while sequencing edit.", false, null);
        }
    }

    @Override
    public void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        RealtimeSession realtimeSession = (RealtimeSession) session.getAttributes().get("REALTIME_SESSION");
        if (realtimeSession != null) {
            sendServerError(realtimeSession, "UNSUPPORTED_DATA", "Binary frames are not supported in protocol v1.", true, 1003);
            realtimeSession.close(new CloseStatus(1003, "UNSUPPORTED_DATA"));
        } else {
            try {
                session.close(new CloseStatus(1003, "UNSUPPORTED_DATA"));
            } catch (IOException e) {
                log.warn("Error closing binary session: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RealtimeSession realtimeSession = (RealtimeSession) session.getAttributes().get("REALTIME_SESSION");
        if (realtimeSession != null) {
            realtimeSession.setState(RealtimeSession.State.CLOSED);
            sessionRegistry.unregisterSession(realtimeSession);
            log.debug("WebSocket connection closed connectionId={} docId={} status={}",
                    realtimeSession.getConnectionId(), realtimeSession.getDocumentId(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error for session id {}: {}", session.getId(), exception.getMessage());
        RealtimeSession realtimeSession = (RealtimeSession) session.getAttributes().get("REALTIME_SESSION");
        if (realtimeSession != null) {
            realtimeSession.close(new CloseStatus(1011, "INTERNAL_ERROR"));
            sessionRegistry.unregisterSession(realtimeSession);
        }
    }

    private boolean hasActiveDocumentPermission(Document document, UUID userId) {
        if (document.getOwner().getId().equals(userId)) {
            return true;
        }
        return documentPermissionRepository.existsByDocumentIdAndUserId(document.getId(), userId);
    }

    private void sendServerReady(RealtimeSession session, UUID documentId, UUID syncEpoch, long revision, String role) throws IOException {
        ServerReadyPayload payload = new ServerReadyPayload(session.getConnectionId(), revision, role);
        String json = buildEnvelopeJson("server.ready", documentId, syncEpoch, payload);
        session.sendMessage(json);
    }

    private void sendServerOperations(RealtimeSession session, UUID documentId, UUID syncEpoch, List<PersistedCanonicalOperation> ops) throws IOException {
        ServerOperationsPayload payload = new ServerOperationsPayload(ops);
        String json = buildEnvelopeJson("server.operations", documentId, syncEpoch, payload);
        session.sendMessage(json);
    }

    private void sendServerOperationRejected(RealtimeSession session, UUID syncEpoch, UUID clientOpId, String code, String message) {
        ServerOperationRejectedPayload payload = new ServerOperationRejectedPayload(clientOpId, code, message);
        try {
            String json = buildEnvelopeJson("server.operation_rejected", session.getDocumentId(), syncEpoch, payload);
            session.sendMessage(json);
        } catch (IOException e) {
            log.warn("Failed to send server.operation_rejected to connectionId={}: {}", session.getConnectionId(), e.getMessage());
        }
    }

    private void sendServerResyncRequired(RealtimeSession session, String reason) {
        ServerResyncRequiredPayload payload = new ServerResyncRequiredPayload(reason);
        try {
            String json = buildEnvelopeJson("server.resync_required", session.getDocumentId(), null, payload);
            session.sendMessage(json);
        } catch (IOException e) {
            log.warn("Failed to send server.resync_required to connectionId={}: {}", session.getConnectionId(), e.getMessage());
        }
    }

    private void sendServerError(RealtimeSession session, String code, String message, boolean fatal, Integer closeCode) {
        ServerErrorPayload payload = new ServerErrorPayload(code, message, fatal, closeCode);
        try {
            String json = buildEnvelopeJson("server.error", session.getDocumentId(), null, payload);
            session.sendMessage(json);
        } catch (IOException e) {
            log.warn("Failed to send server.error to connectionId={}: {}", session.getConnectionId(), e.getMessage());
        }
    }

    private String buildEnvelopeJson(String type, UUID documentId, UUID syncEpoch, Object payloadObj) {
        return buildEnvelopeJson(type, documentId, documentId, syncEpoch, payloadObj);
    }

    private String buildEnvelopeJson(String type, UUID targetDocumentId, UUID envelopeDocId, UUID syncEpoch, Object payloadObj) {
        JsonNode payloadNode = objectMapper.valueToTree(payloadObj);
        RealtimeMessageEnvelope envelope = new RealtimeMessageEnvelope(
                1,
                type,
                UUID.randomUUID(),
                envelopeDocId,
                syncEpoch,
                null,
                OffsetDateTime.now(),
                payloadNode
        );
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message envelope", e);
        }
    }
}
