package com.collaborativeeditor.realtime;

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
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import com.collaborativeeditor.service.realtime.RealtimeTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg=="
)
@ActiveProfiles("test")
class RealtimeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentPermissionRepository permissionRepository;

    @Autowired
    private DocumentSnapshotRepository snapshotRepository;

    @Autowired
    private RealtimeTicketService ticketService;

    private User owner;
    private User collaborator;
    private Document document;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        permissionRepository.deleteAll();
        snapshotRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(new User(
                UUID.randomUUID(), "owner1", "owner1@test.com", "hash", "Owner 1",
                AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()));
        collaborator = userRepository.save(new User(
                UUID.randomUUID(), "collab1", "collab1@test.com", "hash", "Collab 1",
                AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()));

        document = documentRepository.save(new Document(
                UUID.randomUUID(), owner, "Integration Live Doc", UUID.randomUUID(), 0L,
                OffsetDateTime.now(), OffsetDateTime.now()));

        snapshotRepository.save(new com.collaborativeeditor.domain.document.DocumentSnapshot(
                UUID.randomUUID(), document, document.getSyncEpoch(), 0L, "",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                OffsetDateTime.now()));

        permissionRepository.save(new DocumentPermission(
                UUID.randomUUID(), document, collaborator, DocumentRole.EDITOR, owner,
                OffsetDateTime.now(), OffsetDateTime.now()));
    }

    @Test
    @DisplayName("Two live WebSocket clients connect, handshake, bootstrap, and exchange real-time edits")
    void fullLiveWebSocketCollaborationFlow() throws Exception {
        // Issue ticket for client 1 (Owner)
        RealtimeTicket ticket1 = ticketService.issueTicket(document.getId(), owner.getId(), DocumentRole.OWNER);
        // Issue ticket for client 2 (Collaborator)
        RealtimeTicket ticket2 = ticketService.issueTicket(document.getId(), collaborator.getId(), DocumentRole.EDITOR);

        StandardWebSocketClient client = new StandardWebSocketClient();
        BlockingQueue<String> client1Messages = new LinkedBlockingQueue<>();
        BlockingQueue<String> client2Messages = new LinkedBlockingQueue<>();

        String wsUrl1 = "ws://localhost:" + port + "/ws/v1/documents/" + document.getId() + "?ticket=" + ticket1.ticket();
        WebSocketSession session1 = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                client1Messages.offer(message.getPayload());
            }
        }, null, URI.create(wsUrl1)).get(5, TimeUnit.SECONDS);

        String wsUrl2 = "ws://localhost:" + port + "/ws/v1/documents/" + document.getId() + "?ticket=" + ticket2.ticket();
        WebSocketSession session2 = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                client2Messages.offer(message.getPayload());
            }
        }, null, URI.create(wsUrl2)).get(5, TimeUnit.SECONDS);

        UUID clientId1 = UUID.randomUUID();
        UUID clientId2 = UUID.randomUUID();

        // 1. Client 1 sends client.hello
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
                "knownRevision": 0
              }
            }
            """.formatted(UUID.randomUUID(), document.getId(), document.getSyncEpoch(), clientId1, OffsetDateTime.now(), document.getSyncEpoch());
        session1.sendMessage(new TextMessage(hello1));

        String readyMsg1 = client1Messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(readyMsg1);
        JsonNode readyJson1 = objectMapper.readTree(readyMsg1);
        assertEquals("server.ready", readyJson1.get("type").asText());
        assertEquals(0L, readyJson1.get("payload").get("revision").asLong());
        assertEquals("OWNER", readyJson1.get("payload").get("role").asText());

        // 2. Client 2 sends client.hello
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
                "knownRevision": 0
              }
            }
            """.formatted(UUID.randomUUID(), document.getId(), document.getSyncEpoch(), clientId2, OffsetDateTime.now(), document.getSyncEpoch());
        session2.sendMessage(new TextMessage(hello2));

        String readyMsg2 = client2Messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(readyMsg2);
        JsonNode readyJson2 = objectMapper.readTree(readyMsg2);
        assertEquals("server.ready", readyJson2.get("type").asText());
        assertEquals(0L, readyJson2.get("payload").get("revision").asLong());
        assertEquals("EDITOR", readyJson2.get("payload").get("role").asText());

        // 3. Client 1 submits client.operation: INSERT "Hello World"
        UUID clientOpId1 = UUID.randomUUID();
        String opMsg1 = """
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
                  "text": "Hello World"
                }
              }
            }
            """.formatted(UUID.randomUUID(), document.getId(), document.getSyncEpoch(), clientId1, OffsetDateTime.now(), clientOpId1);
        session1.sendMessage(new TextMessage(opMsg1));

        // Both clients should receive server.operations broadcast
        String broadcastForC1 = client1Messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(broadcastForC1);
        JsonNode bcastJson1 = objectMapper.readTree(broadcastForC1);
        assertEquals("server.operations", bcastJson1.get("type").asText());
        JsonNode opItem1 = bcastJson1.get("payload").get("operations").get(0);
        assertEquals(1L, opItem1.get("revision").asLong());
        assertEquals("Hello World", opItem1.get("operation").get("text").asText());

        String broadcastForC2 = client2Messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(broadcastForC2);
        JsonNode bcastJson2 = objectMapper.readTree(broadcastForC2);
        assertEquals("server.operations", bcastJson2.get("type").asText());
        JsonNode opItem2 = bcastJson2.get("payload").get("operations").get(0);
        assertEquals(1L, opItem2.get("revision").asLong());
        assertEquals("Hello World", opItem2.get("operation").get("text").asText());

        session1.close();
        session2.close();
    }
}
