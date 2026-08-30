package com.collaborativeeditor.realtime;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.realtime.RealtimeHandshakeInterceptor;
import com.collaborativeeditor.service.realtime.RealtimeSession;
import com.collaborativeeditor.service.realtime.RealtimeSessionRegistry;
import com.collaborativeeditor.service.realtime.RealtimeWebSocketHandler;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealtimeSessionLifecycleTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentPermissionRepository permissionRepository;
    @Mock private OperationPersistenceService persistenceService;
    @Mock private DocumentSequencingService sequencingService;
    @Mock private WebSocketSession wsSession1;
    @Mock private WebSocketSession wsSession2;

    private RealtimeSessionRegistry sessionRegistry;
    private RealtimeWebSocketHandler handler;
    private ObjectMapper objectMapper;

    private UUID docId;
    private UUID syncEpoch;
    private UUID userId;
    private UUID clientId;
    private Document document;

    @BeforeEach
    void setUp() {
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
        document = new Document(docId, owner, "Test Doc", syncEpoch, 5L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("client.hello delivers catch-up operations then server.ready for client behind server revision")
    void catchUpAndServerReadyFlow() throws Exception {
        UUID connId = UUID.randomUUID();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID, docId);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_USER_ID, userId);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_ROLE, DocumentRole.OWNER);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID, connId);

        when(wsSession1.getAttributes()).thenReturn(attributes);
        when(wsSession1.isOpen()).thenReturn(true);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));

        // Mock catch-up ops from rev 2 to 5
        PersistedCanonicalOperation op3 = new PersistedCanonicalOperation(3L, UUID.randomUUID(), UUID.randomUUID(), userId, new com.collaborativeeditor.ot.model.InsertOperation(0, "A"));
        PersistedCanonicalOperation op4 = new PersistedCanonicalOperation(4L, UUID.randomUUID(), UUID.randomUUID(), userId, new com.collaborativeeditor.ot.model.InsertOperation(1, "B"));
        PersistedCanonicalOperation op5 = new PersistedCanonicalOperation(5L, UUID.randomUUID(), UUID.randomUUID(), userId, new com.collaborativeeditor.ot.model.InsertOperation(2, "C"));
        when(persistenceService.getCanonicalOperations(docId, syncEpoch, 2L, 5L)).thenReturn(List.of(op3, op4, op5));

        handler.afterConnectionEstablished(wsSession1);

        String clientHelloJson = """
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
                "knownRevision": 2
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientId, OffsetDateTime.now(), syncEpoch);

        handler.handleTextMessage(wsSession1, new TextMessage(clientHelloJson));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession1, atLeastOnce()).sendMessage(captor.capture());

        List<TextMessage> sent = captor.getAllValues();
        assertEquals(2, sent.size());

        // First message: server.operations catch-up
        JsonNode catchUpNode = objectMapper.readTree(sent.get(0).getPayload());
        assertEquals("server.operations", catchUpNode.get("type").asText());
        assertEquals(3, catchUpNode.get("payload").get("operations").size());

        // Second message: server.ready
        JsonNode readyNode = objectMapper.readTree(sent.get(1).getPayload());
        assertEquals("server.ready", readyNode.get("type").asText());
        assertEquals(5L, readyNode.get("payload").get("revision").asLong());
        assertEquals("OWNER", readyNode.get("payload").get("role").asText());
        assertEquals(connId.toString(), readyNode.get("payload").get("connectionId").asText());
    }

    @Test
    @DisplayName("Duplicate clientId connection closes older connection with close code 4004 (SESSION_SUPERSEDED)")
    void duplicateClientIdSupersedesOlderSession() throws Exception {
        UUID conn1 = UUID.randomUUID();
        UUID conn2 = UUID.randomUUID();

        Map<String, Object> attr1 = new HashMap<>();
        attr1.put(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID, docId);
        attr1.put(RealtimeHandshakeInterceptor.ATTR_USER_ID, userId);
        attr1.put(RealtimeHandshakeInterceptor.ATTR_ROLE, DocumentRole.OWNER);
        attr1.put(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID, conn1);

        Map<String, Object> attr2 = new HashMap<>();
        attr2.put(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID, docId);
        attr2.put(RealtimeHandshakeInterceptor.ATTR_USER_ID, userId);
        attr2.put(RealtimeHandshakeInterceptor.ATTR_ROLE, DocumentRole.OWNER);
        attr2.put(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID, conn2);

        when(wsSession1.getAttributes()).thenReturn(attr1);
        when(wsSession2.getAttributes()).thenReturn(attr2);
        when(wsSession1.isOpen()).thenReturn(true);
        when(wsSession2.isOpen()).thenReturn(true);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));

        handler.afterConnectionEstablished(wsSession1);
        handler.afterConnectionEstablished(wsSession2);

        String hello1 = """
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
                "knownRevision": 5
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientId, OffsetDateTime.now(), syncEpoch);

        handler.handleTextMessage(wsSession1, new TextMessage(hello1));

        // Now session 2 connects with same clientId
        String hello2 = """
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
                "knownRevision": 5
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientId, OffsetDateTime.now(), syncEpoch);

        handler.handleTextMessage(wsSession2, new TextMessage(hello2));

        // Verify session 1 was closed with 4004
        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(wsSession1).close(statusCaptor.capture());
        assertEquals(4004, statusCaptor.getValue().getCode());
        assertEquals("SESSION_SUPERSEDED", statusCaptor.getValue().getReason());
    }

    @Test
    @DisplayName("Unregistering session on close removes it from room and connection registry")
    void sessionUnregisterOnClose() {
        UUID connId = UUID.randomUUID();
        RealtimeSession session = new RealtimeSession(wsSession1, connId, docId, userId, DocumentRole.OWNER);

        sessionRegistry.registerSession(session);
        assertEquals(1, sessionRegistry.getRoomSessions(docId).size());

        sessionRegistry.unregisterSession(session);
        assertEquals(0, sessionRegistry.getRoomSessions(docId).size());
    }
}
