package com.collaborativeeditor.realtime;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.persistence.PersistedCanonicalOperation;
import com.collaborativeeditor.service.realtime.RealtimeHandshakeInterceptor;
import com.collaborativeeditor.service.realtime.RealtimeSessionRegistry;
import com.collaborativeeditor.service.realtime.RealtimeWebSocketHandler;
import com.collaborativeeditor.service.sequencing.AcceptedOperationResult;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealtimeOperationBroadcastTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentPermissionRepository permissionRepository;
    @Mock private OperationPersistenceService persistenceService;
    @Mock private DocumentSequencingService sequencingService;
    @Mock private WebSocketSession senderWs;
    @Mock private WebSocketSession peerWs;

    private RealtimeSessionRegistry sessionRegistry;
    private RealtimeWebSocketHandler handler;
    private ObjectMapper objectMapper;

    private UUID docId;
    private UUID syncEpoch;
    private UUID userIdA;
    private UUID userIdB;
    private UUID clientIdA;
    private UUID clientIdB;
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
        userIdA = UUID.randomUUID();
        userIdB = UUID.randomUUID();
        clientIdA = UUID.randomUUID();
        clientIdB = UUID.randomUUID();

        User userA = new User(userIdA, "userA", "userA@test.com", "hash", "User A", AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now());
        document = new Document(docId, userA, "Broadcast Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(permissionRepository.existsByDocumentIdAndUserId(any(), any())).thenReturn(true);

        // Setup session A (sender)
        setupSession(senderWs, UUID.randomUUID(), userIdA, DocumentRole.OWNER, clientIdA);

        // Setup session B (peer)
        setupSession(peerWs, UUID.randomUUID(), userIdB, DocumentRole.EDITOR, clientIdB);
    }

    private void setupSession(WebSocketSession ws, UUID connId, UUID userId, DocumentRole role, UUID clientId) throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID, docId);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_USER_ID, userId);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_ROLE, role);
        attributes.put(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID, connId);
        when(ws.getAttributes()).thenReturn(attributes);
        when(ws.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(ws);

        String helloJson = """
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

        handler.handleTextMessage(ws, new TextMessage(helloJson));
    }

    @Test
    @DisplayName("Accepted client.operation broadcasts canonical server.operations to both sender and peer")
    void acceptedOperationBroadcastsToAll() throws Exception {
        UUID opId = UUID.randomUUID();
        InsertOperation op = new InsertOperation(0, "Collaborative ");

        AcceptedOperationResult accepted = new AcceptedOperationResult(
                docId, syncEpoch, 1L, clientIdA, opId, userIdA, op, UUID.randomUUID(), false, OffsetDateTime.now()
        );
        when(sequencingService.submitOperation(any())).thenReturn(accepted);

        String clientOpJson = """
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
                  "position": 0,
                  "text": "Collaborative "
                }
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientIdA, OffsetDateTime.now(), opId);

        handler.handleTextMessage(senderWs, new TextMessage(clientOpJson));

        // Both senderWs and peerWs receive the broadcast
        ArgumentCaptor<TextMessage> senderCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(senderWs, org.mockito.Mockito.atLeastOnce()).sendMessage(senderCaptor.capture());

        ArgumentCaptor<TextMessage> peerCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(peerWs, org.mockito.Mockito.atLeastOnce()).sendMessage(peerCaptor.capture());

        // Check broadcast payload on peerWs
        TextMessage lastPeerMsg = peerCaptor.getValue();
        JsonNode peerJson = objectMapper.readTree(lastPeerMsg.getPayload());
        assertEquals("server.operations", peerJson.get("type").asText());
        assertEquals(1, peerJson.get("payload").get("operations").size());

        JsonNode opItem = peerJson.get("payload").get("operations").get(0);
        assertEquals(1L, opItem.get("revision").asLong());
        assertEquals(clientIdA.toString(), opItem.get("clientId").asText());
        assertEquals(opId.toString(), opItem.get("clientOperationId").asText());
        assertEquals("INSERT", opItem.get("operation").get("kind").asText());
        assertEquals("Collaborative ", opItem.get("operation").get("text").asText());
    }

    @Test
    @DisplayName("Canonical GROUP operation resulting from split delete is broadcast as GROUP")
    void canonicalGroupBroadcast() throws Exception {
        UUID opId = UUID.randomUUID();
        GroupOperation groupOp = new GroupOperation(List.of(new DeleteOperation(1, 2), new DeleteOperation(5, 3)));

        AcceptedOperationResult accepted = new AcceptedOperationResult(
                docId, syncEpoch, 2L, clientIdA, opId, userIdA, groupOp, UUID.randomUUID(), false, OffsetDateTime.now()
        );
        when(sequencingService.submitOperation(any())).thenReturn(accepted);

        String clientOpJson = """
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
                "baseRevision": 1,
                "operation": {
                  "kind": "DELETE",
                  "position": 1,
                  "length": 5
                }
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientIdA, OffsetDateTime.now(), opId);

        handler.handleTextMessage(senderWs, new TextMessage(clientOpJson));

        ArgumentCaptor<TextMessage> peerCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(peerWs, org.mockito.Mockito.atLeastOnce()).sendMessage(peerCaptor.capture());

        JsonNode peerJson = objectMapper.readTree(peerCaptor.getValue().getPayload());
        assertEquals("server.operations", peerJson.get("type").asText());
        JsonNode opItem = peerJson.get("payload").get("operations").get(0);
        assertEquals("GROUP", opItem.get("operation").get("kind").asText());
        assertEquals(2, opItem.get("operation").get("operations").size());
    }

    @Test
    @DisplayName("Idempotent replay submission broadcasts the original canonical operation without error")
    void idempotentReplayBroadcastsOriginal() throws Exception {
        UUID opId = UUID.randomUUID();
        InsertOperation op = new InsertOperation(0, "A");

        AcceptedOperationResult accepted = new AcceptedOperationResult(
                docId, syncEpoch, 1L, clientIdA, opId, userIdA, op, UUID.randomUUID(), true, OffsetDateTime.now()
        );
        when(sequencingService.submitOperation(any())).thenReturn(accepted);

        String clientOpJson = """
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
                  "position": 0,
                  "text": "A"
                }
              }
            }
            """.formatted(UUID.randomUUID(), docId, syncEpoch, clientIdA, OffsetDateTime.now(), opId);

        handler.handleTextMessage(senderWs, new TextMessage(clientOpJson));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(senderWs, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertEquals("server.operations", json.get("type").asText());
        assertEquals(1L, json.get("payload").get("operations").get(0).get("revision").asLong());
    }
}
