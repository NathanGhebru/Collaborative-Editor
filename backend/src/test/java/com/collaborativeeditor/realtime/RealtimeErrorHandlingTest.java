package com.collaborativeeditor.realtime;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.realtime.RealtimeHandshakeInterceptor;
import com.collaborativeeditor.service.realtime.RealtimeSessionRegistry;
import com.collaborativeeditor.service.realtime.RealtimeWebSocketHandler;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
import com.collaborativeeditor.service.sequencing.SequencerOperationRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealtimeErrorHandlingTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentPermissionRepository permissionRepository;
    @Mock private OperationPersistenceService persistenceService;
    @Mock private DocumentSequencingService sequencingService;
    @Mock private WebSocketSession wsSession;

    private RealtimeSessionRegistry sessionRegistry;
    private RealtimeWebSocketHandler handler;
    private ObjectMapper objectMapper;

    private UUID docId;
    private UUID syncEpoch;
    private UUID userId;
    private UUID clientId;
    private Document document;

    @BeforeEach
    void setUp() throws Exception {
        sessionRegistry = new RealtimeSessionRegistry();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        handler = new RealtimeWebSocketHandler(
                sessionRegistry,
                documentRepository,
                permissionRepository,
                persistenceService,
                sequencingService,
                objectMapper
        );

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        userId = UUID.randomUUID();
        clientId = UUID.randomUUID();

        User owner = new User(userId, "user1", "user1@test.com", "hash", "User 1", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        document = new Document(docId, owner, "Test Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID, docId);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_USER_ID, userId);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_ROLE, DocumentRole.OWNER);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID, UUID.randomUUID());

        when(wsSession.getAttributes()).thenReturn(attributes);
        when(wsSession.isOpen()).thenReturn(true);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));

        handler.afterConnectionEstablished(wsSession);
    }

    @Test
    @DisplayName("Invalid JSON frame closes connection with 4000 (BAD_REQUEST) and fatal server.error")
    void malformedJsonClosesWith4000() throws Exception {
        handler.handleTextMessage(wsSession, new TextMessage("NOT_VALID_JSON{"));

        ArgumentCaptor<TextMessage> textCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, atLeastOnce()).sendMessage(textCaptor.capture());

        JsonNode json = objectMapper.readTree(textCaptor.getValue().getPayload());
        assertEquals("server.error", json.get("type").asText());
        assertEquals("INVALID_MESSAGE", json.get("payload").get("code").asText());
        assertTrue(json.get("payload").get("fatal").asBoolean());

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(wsSession).close(statusCaptor.capture());
        assertEquals(4000, statusCaptor.getValue().getCode());
    }

    @Test
    @DisplayName("Unsupported protocol version (not 1) closes connection with 4002 (UNSUPPORTED_PROTOCOL_VERSION)")
    void unsupportedProtocolVersionClosesWith4002() throws Exception {
        String msg = """
            {
              "protocolVersion": 2,
              "type": "client.hello",
              "messageId": "%s",
              "documentId": "%s",
              "syncEpoch": "%s",
              "clientId": "%s",
              "timestamp": "%s",
              "payload": {}
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientId, OffsetDateTime.now());

        handler.handleTextMessage(wsSession, new TextMessage(msg));

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(wsSession).close(statusCaptor.capture());
        assertEquals(4002, statusCaptor.getValue().getCode());
    }

    @Test
    @DisplayName("Binary WebSocket frames are rejected with close code 1003 (UNSUPPORTED_DATA)")
    void binaryFramesRejectedWith1003() throws Exception {
        handler.handleBinaryMessage(wsSession, new BinaryMessage(ByteBuffer.wrap(new byte[]{0x01, 0x02})));

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(wsSession).close(statusCaptor.capture());
        assertEquals(1003, statusCaptor.getValue().getCode());
    }

    @Test
    @DisplayName("Epoch mismatch in client.hello returns non-fatal server.resync_required")
    void epochMismatchReturnsResyncRequired() throws Exception {
        UUID wrongEpoch = UUID.randomUUID();
        String hello = """
            {
              "protocolVersion": 1,
              "type": "client.hello",
              "messageId": "%s",
              "documentId": "%s",
              "syncEpoch": "%s",
              "clientId": "%s",
              "timestamp": "%s",
              "payload": {
                "knownEpoch": "%s",
                "knownRevision": 0
              }
            }
            """.formatted(UUID.randomUUID(), docId, wrongEpoch, clientId, OffsetDateTime.now(), wrongEpoch);

        handler.handleTextMessage(wsSession, new TextMessage(hello));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, atLeastOnce()).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertEquals("server.resync_required", json.get("type").asText());
        assertEquals("EPOCH_MISMATCH", json.get("payload").get("reason").asText());
    }

    @Test
    @DisplayName("Sequencer operation rejection returns non-fatal server.operation_rejected without closing socket")
    void sequencerRejectionReturnsOperationRejected() throws Exception {
        // First activate session with valid client.hello
        String hello = """
            {
              "protocolVersion": 1,
              "type": "client.hello",
              "messageId": "%s",
              "documentId": "%s",
              "syncEpoch": "%s",
              "clientId": "%s",
              "timestamp": "%s",
              "payload": {
                "knownEpoch": "%s",
                "knownRevision": 0
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientId, OffsetDateTime.now(), syncEpoch);
        handler.handleTextMessage(wsSession, new TextMessage(hello));

        // Submit operation that throws SequencerOperationRejectedException
        UUID opId = UUID.randomUUID();
        when(sequencingService.submitOperation(any())).thenThrow(new SequencerOperationRejectedException("POSITION_OUT_OF_BOUNDS", "Position out of bounds"));

        String opJson = """
            {
              "protocolVersion": 1,
              "type": "client.operation",
              "messageId": "%s",
              "documentId": "%s",
              "syncEpoch": "%s",
              "clientId": "%s",
              "timestamp": "%s",
              "payload": {
                "clientOperationId": "%s",
                "baseRevision": 0,
                "operation": {
                  "kind": "INSERT",
                  "position": 999,
                  "text": "X"
                }
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientId, OffsetDateTime.now(), opId);

        handler.handleTextMessage(wsSession, new TextMessage(opJson));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, atLeastOnce()).sendMessage(captor.capture());

        JsonNode lastJson = objectMapper.readTree(captor.getValue().getPayload());
        assertEquals("server.operation_rejected", lastJson.get("type").asText());
        assertEquals("POSITION_OUT_OF_BOUNDS", lastJson.get("payload").get("code").asText());
        assertEquals(opId.toString(), lastJson.get("payload").get("clientOperationId").asText());
    }
}
