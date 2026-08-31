package com.collaborativeeditor.realtime;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermission;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.realtime.RealtimeTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg=="
)
@ActiveProfiles("test")
@DisplayName("PRES-001 live WebSocket acceptance")
class PresenceCursorWebSocketAcceptanceTest {

    private static final long EVENT_TIMEOUT_SECONDS = 2;

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentPermissionRepository permissionRepository;
    @Autowired private DocumentSnapshotRepository snapshotRepository;
    @Autowired private RealtimeTicketService ticketService;
    @Autowired private DocumentOperationBatchRepository operationBatchRepository;
    @Autowired private DocumentOperationIdRepository operationIdRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @SpyBean private OperationPersistenceService persistenceService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<ObservedSocket> sockets = new ArrayList<>();
    private User owner;
    private User editor;
    private User third;
    private Document document;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        snapshotRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        owner = saveUser("owner", "Owner Public");
        editor = saveUser("editor", "Editor Public");
        third = saveUser("third", "Third Public");
        document = documentRepository.save(new Document(
                UUID.randomUUID(), owner, "Presence acceptance", UUID.randomUUID(), 0L,
                OffsetDateTime.now(), OffsetDateTime.now()));
        snapshotRepository.save(new DocumentSnapshot(
                UUID.randomUUID(), document, document.getSyncEpoch(), 0L, "hello",
                OperationPersistenceService.calculateSha256("hello"), OffsetDateTime.now()));
        grant(editor, DocumentRole.EDITOR);
        grant(third, DocumentRole.EDITOR);
    }

    @AfterEach
    void closeSockets() {
        sockets.forEach(ObservedSocket::closeQuietly);
        sockets.clear();
    }

    @Test
    @DisplayName("A/B/C: snapshots and JOINED/LEFT are connection-scoped, public, and revision-neutral")
    void snapshotJoinLeaveAndMultipleTabsExposeOnlyPublicConnectionIdentity() throws Exception {
        UUID ownerTab = UUID.randomUUID();
        ObservedSocket a = connect(owner, DocumentRole.OWNER, ownerTab);
        Bootstrap aBootstrap = a.hello(ownerTab);
        assertNotNull(aBootstrap.presenceSnapshot(), "new ACTIVE client must receive presence.snapshot");

        UUID editorTab1 = UUID.randomUUID();
        ObservedSocket b1 = connect(editor, DocumentRole.EDITOR, editorTab1);
        Bootstrap b1Bootstrap = b1.hello(editorTab1);
        List<JsonNode> b1Entries = publicConnectionEntries(b1Bootstrap.presenceSnapshot());
        assertTrue(hasClient(b1Entries, ownerTab));
        assertPublicPresenceOnly(b1Bootstrap.presenceSnapshot());

        JsonNode joinedB1 = a.awaitType("presence.changed");
        assertEquals("JOINED", joinedB1.path("payload").path("event").asText());
        assertEquals(editorTab1.toString(), changedEntry(joinedB1).path("clientId").asText());

        UUID editorTab2 = UUID.randomUUID();
        ObservedSocket b2 = connect(editor, DocumentRole.EDITOR, editorTab2);
        Bootstrap b2Bootstrap = b2.hello(editorTab2);
        List<JsonNode> b2Entries = publicConnectionEntries(b2Bootstrap.presenceSnapshot());
        assertTrue(hasClient(b2Entries, editorTab1));
        assertTrue(hasClient(b2Entries, ownerTab));
        assertNotEquals(editorTab1, editorTab2);

        b1.close();
        JsonNode left = a.awaitChanged("LEFT", editorTab1);
        assertEquals(editorTab1.toString(), changedEntry(left).path("clientId").asText());
        assertTrue(b2.session().isOpen(), "closing one tab must not remove the user's other tab");
        assertEquals(0L, documentRepository.findById(document.getId()).orElseThrow().getCurrentRevision());
    }

    @Test
    @DisplayName("D/E: server binds cursor identity and relays exact current-revision direction")
    void cursorIdentityIsAuthoritativeAndCoordinatesPreserveDirection() throws Exception {
        UUID ownerTab = UUID.randomUUID();
        UUID editorTab = UUID.randomUUID();
        ObservedSocket a = connect(owner, DocumentRole.OWNER, ownerTab);
        a.hello(ownerTab);
        ObservedSocket b = connect(editor, DocumentRole.EDITOR, editorTab);
        b.hello(editorTab);
        a.drain();
        b.drain();

        a.sendCursor(ownerTab, 0, 2, 2, true);
        assertAuthoritativeCursor(b.awaitType("cursor.remote"), ownerTab, 2, 2);
        a.sendCursor(ownerTab, 0, 1, 4, true);
        assertAuthoritativeCursor(b.awaitType("cursor.remote"), ownerTab, 1, 4);
        a.sendCursor(ownerTab, 0, 4, 1, true);
        assertAuthoritativeCursor(b.awaitType("cursor.remote"), ownerTab, 4, 1);

        assertEquals(0L, documentRepository.findById(document.getId()).orElseThrow().getCurrentRevision());
    }

    @Test
    @DisplayName("K: actual document length rejects globally-small impossible cursor without closing socket")
    void impossiblePositionIsRejectedAgainstActualDocumentLength() throws Exception {
        UUID ownerTab = UUID.randomUUID();
        UUID editorTab = UUID.randomUUID();
        ObservedSocket a = connect(owner, DocumentRole.OWNER, ownerTab);
        a.hello(ownerTab);
        ObservedSocket b = connect(editor, DocumentRole.EDITOR, editorTab);
        b.hello(editorTab);
        a.drain();
        b.drain();

        a.sendCursor(ownerTab, 0, 500_000, 500_000, false);
        a.awaitCode("INVALID_POSITION");
        assertTrue(a.session().isOpen());
        assertNull(b.pollType("cursor.remote", 250), "invalid cursor must not be broadcast");
    }

    @Test
    @DisplayName("L: UTF-16 cursor boundaries accept around emoji and reject surrogate bisection")
    void utf16SurrogateBoundaryIsValidatedWithoutSnapping() throws Exception {
        replaceRevisionZeroContent("A😀B");
        UUID ownerTab = UUID.randomUUID();
        UUID editorTab = UUID.randomUUID();
        ObservedSocket a = connect(owner, DocumentRole.OWNER, ownerTab);
        a.hello(ownerTab);
        ObservedSocket b = connect(editor, DocumentRole.EDITOR, editorTab);
        b.hello(editorTab);
        a.drain();
        b.drain();

        a.sendCursor(ownerTab, 0, 1, 1, false);
        assertAuthoritativeCursor(b.awaitType("cursor.remote"), ownerTab, 1, 1);
        a.sendCursor(ownerTab, 0, 2, 2, false);
        a.awaitCode("INVALID_POSITION");
        assertTrue(a.session().isOpen());
        assertNull(b.pollType("cursor.remote", 250));
        a.sendCursor(ownerTab, 0, 3, 3, false);
        assertAuthoritativeCursor(b.awaitType("cursor.remote"), ownerTab, 3, 3);
    }

    @Test
    @DisplayName("P: reconnect and SESSION_SUPERSEDED converge without stale duplicate presence")
    void reconnectAndSupersessionLeaveOneSensiblePresenceEntry() throws Exception {
        UUID stableClientId = UUID.randomUUID();
        ObservedSocket oldSocket = connect(owner, DocumentRole.OWNER, stableClientId);
        oldSocket.hello(stableClientId);

        ObservedSocket peer = connect(editor, DocumentRole.EDITOR, UUID.randomUUID());
        peer.hello(peer.clientId());
        peer.drain();

        ObservedSocket replacement = connect(owner, DocumentRole.OWNER, stableClientId);
        replacement.hello(stableClientId);
        assertEquals(4004, oldSocket.awaitClose().getCode());

        ObservedSocket probe = connect(third, DocumentRole.EDITOR, UUID.randomUUID());
        Bootstrap probeBootstrap = probe.hello(probe.clientId());
        long stableEntries = publicConnectionEntries(probeBootstrap.presenceSnapshot()).stream()
                .filter(entry -> stableClientId.toString().equals(entry.path("clientId").asText()))
                .count();
        assertEquals(1L, stableEntries, "superseded connection must not remain in presence snapshot");
        assertTrue(replacement.session().isOpen());
    }

    @Test
    @DisplayName("Q/R: cursor hot path neither advances revisions nor invokes durable operation persistence")
    void highRateCursorTrafficIsDurabilityAndPersistenceHotPathIsolated() throws Exception {
        UUID ownerTab = UUID.randomUUID();
        UUID editorTab = UUID.randomUUID();
        ObservedSocket a = connect(owner, DocumentRole.OWNER, ownerTab);
        a.hello(ownerTab);
        ObservedSocket b = connect(editor, DocumentRole.EDITOR, editorTab);
        b.hello(editorTab);
        a.drain();
        b.drain();

        clearInvocations(persistenceService);
        long initialSnapshots = snapshotRepository.count();
        for (int index = 0; index < 200; index++) {
            int position = index % 6;
            a.sendCursor(ownerTab, 0, position, position, false);
        }

        assertEquals(0L, documentRepository.findById(document.getId()).orElseThrow().getCurrentRevision());
        assertEquals(0L, operationBatchRepository.count());
        assertEquals(0L, operationIdRepository.count());
        assertEquals(initialSnapshots, snapshotRepository.count());
        Integer durableAwarenessTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE LOWER(table_name) LIKE '%presence%'
                   OR LOWER(table_name) LIKE '%cursor%'
                """, Integer.class);
        assertEquals(0, durableAwarenessTables);
        verifyNoInteractions(persistenceService);
        var recovered = persistenceService.recoverDocument(document.getId());
        assertEquals(0L, recovered.revision());
        assertEquals("hello", recovered.content());
        assertNotNull(b.pollType("cursor.remote", TimeUnit.SECONDS.toMillis(EVENT_TIMEOUT_SECONDS)),
                "at least one best-effort cursor update should survive server rate limiting");
        assertTrue(a.session().isOpen());
    }

    private User saveUser(String prefix, String displayName) {
        UUID id = UUID.randomUUID();
        return userRepository.save(new User(
                id, prefix + "_" + id.toString().substring(0, 8), id + "@private.example",
                "private-password-hash", displayName, AccountStatus.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now()));
    }

    private void grant(User user, DocumentRole role) {
        permissionRepository.save(new DocumentPermission(
                UUID.randomUUID(), document, user, role, owner,
                OffsetDateTime.now(), OffsetDateTime.now()));
    }

    private void replaceRevisionZeroContent(String content) {
        snapshotRepository.deleteAll();
        snapshotRepository.save(new DocumentSnapshot(
                UUID.randomUUID(), document, document.getSyncEpoch(), 0L, content,
                OperationPersistenceService.calculateSha256(content), OffsetDateTime.now()));
    }

    private ObservedSocket connect(User user, DocumentRole role, UUID clientId) throws Exception {
        RealtimeTicket ticket = ticketService.issueTicket(document.getId(), user.getId(), role);
        ObservedSocket observed = new ObservedSocket(clientId);
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/v1/documents/" + document.getId()
                + "?ticket=" + ticket.ticket();
        observed.attach(client.execute(observed.handler(), null, URI.create(url))
                .get(5, TimeUnit.SECONDS));
        sockets.add(observed);
        return observed;
    }

    private void assertAuthoritativeCursor(JsonNode message, UUID expectedClientId, int anchor, int head) {
        JsonNode payload = message.path("payload");
        assertEquals(expectedClientId.toString(), payload.path("clientId").asText());
        assertEquals(owner.getId().toString(), payload.path("userId").asText());
        assertEquals(owner.getDisplayName(), payload.path("displayName").asText());
        assertEquals("OWNER", payload.path("role").asText());
        assertEquals(anchor, payload.path("anchor").asInt());
        assertEquals(head, payload.path("head").asInt());
        assertNotEquals("forged-client", payload.path("clientId").asText());
        assertNotEquals("Forged Name", payload.path("displayName").asText());
        assertNotEquals("EDITOR", payload.path("role").asText());
    }

    private static List<JsonNode> publicConnectionEntries(JsonNode message) {
        assertNotNull(message, "presence snapshot is required");
        List<JsonNode> entries = new ArrayList<>();
        collectObjectsWithField(message.path("payload"), "clientId", entries);
        return entries;
    }

    private static void collectObjectsWithField(JsonNode node, String field, List<JsonNode> matches) {
        if (node.isObject() && node.hasNonNull(field)) {
            matches.add(node);
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectObjectsWithField(child, field, matches));
        }
    }

    private static boolean hasClient(List<JsonNode> entries, UUID clientId) {
        return entries.stream().anyMatch(entry -> clientId.toString().equals(entry.path("clientId").asText()));
    }

    private static JsonNode changedEntry(JsonNode changed) {
        JsonNode payload = changed.path("payload");
        for (String candidate : List.of("connection", "collaborator", "user")) {
            if (payload.path(candidate).isObject()) {
                return payload.path(candidate);
            }
        }
        return payload;
    }

    private static void assertPublicPresenceOnly(JsonNode snapshot) {
        Set<String> forbidden = Set.of(
                "email", "token", "accessToken", "refreshToken", "password", "passwordHash", "auth");
        Set<String> keys = new HashSet<>();
        collectKeys(snapshot, keys);
        assertTrue(keys.stream().noneMatch(forbidden::contains),
                () -> "presence leaked private identity fields: " + keys);
        for (JsonNode entry : publicConnectionEntries(snapshot)) {
            assertTrue(Set.of("OWNER", "EDITOR").contains(entry.path("role").asText()));
        }
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            names.forEachRemaining(keys::add);
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectKeys(child, keys));
        }
    }

    private record Bootstrap(JsonNode presenceSnapshot, JsonNode ready) {
    }

    private final class ObservedSocket {
        private final UUID clientId;
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closeStatus = new CompletableFuture<>();
        private WebSocketSession session;

        private ObservedSocket(UUID clientId) {
            this.clientId = clientId;
        }

        UUID clientId() {
            return clientId;
        }

        WebSocketSession session() {
            return session;
        }

        void attach(WebSocketSession session) {
            this.session = session;
        }

        TextWebSocketHandler handler() {
            return new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession ignored, TextMessage message) throws Exception {
                    messages.offer(objectMapper.readTree(message.getPayload()));
                }

                @Override
                public void afterConnectionClosed(WebSocketSession ignored, CloseStatus status) {
                    closeStatus.complete(status);
                }
            };
        }

        Bootstrap hello(UUID helloClientId) throws Exception {
            String hello = """
                    {
                      "protocolVersion": 1,
                      "type": "client.hello",
                      "messageId": "%s",
                      "documentId": "%s",
                      "syncEpoch": "%s",
                      "clientId": "%s",
                      "timestamp": "%s",
                      "payload": { "knownEpoch": "%s", "knownRevision": 0 }
                    }
                    """.formatted(
                    UUID.randomUUID(), document.getId(), document.getSyncEpoch(), helloClientId,
                    OffsetDateTime.now(), document.getSyncEpoch());
            session.sendMessage(new TextMessage(hello));

            JsonNode snapshot = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EVENT_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                long remaining = Math.max(1, deadline - System.nanoTime());
                JsonNode message = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (message == null) {
                    break;
                }
                if ("presence.snapshot".equals(message.path("type").asText())) {
                    snapshot = message;
                }
                if ("server.ready".equals(message.path("type").asText())) {
                    return new Bootstrap(snapshot, message);
                }
            }
            throw new AssertionError("server.ready was not observed during bootstrap");
        }

        void sendCursor(UUID envelopeClientId, long baseRevision, int anchor, int head, boolean spoof) throws Exception {
            String spoofFields = spoof
                    ? ", \"clientId\": \"forged-client\", \"displayName\": \"Forged Name\", \"role\": \"EDITOR\", \"userId\": \"forged-user\""
                    : "";
            String cursor = """
                    {
                      "protocolVersion": 1,
                      "type": "cursor.update",
                      "messageId": "%s",
                      "documentId": "%s",
                      "syncEpoch": "%s",
                      "clientId": "%s",
                      "timestamp": "%s",
                      "payload": {
                        "baseRevision": %d,
                        "anchor": %d,
                        "head": %d%s
                      }
                    }
                    """.formatted(
                    UUID.randomUUID(), document.getId(), document.getSyncEpoch(), envelopeClientId,
                    OffsetDateTime.now(), baseRevision, anchor, head, spoofFields);
            session.sendMessage(new TextMessage(cursor));
        }

        JsonNode awaitType(String type) throws InterruptedException {
            JsonNode message = pollType(type, TimeUnit.SECONDS.toMillis(EVENT_TIMEOUT_SECONDS));
            assertNotNull(message, "expected " + type + " frame");
            return message;
        }

        JsonNode awaitCode(String code) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EVENT_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                long remaining = Math.max(1, deadline - System.nanoTime());
                JsonNode message = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (message == null) {
                    break;
                }
                if (code.equals(message.path("payload").path("code").asText())) {
                    return message;
                }
            }
            throw new AssertionError("expected frame with code " + code);
        }

        JsonNode awaitChanged(String event, UUID expectedClientId) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EVENT_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                JsonNode message = pollType("presence.changed", 250);
                if (message != null
                        && event.equals(message.path("payload").path("event").asText())
                        && expectedClientId.toString().equals(changedEntry(message).path("clientId").asText())) {
                    return message;
                }
            }
            throw new AssertionError("expected presence.changed " + event + " for client " + expectedClientId);
        }

        JsonNode pollType(String type, long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                long remaining = Math.max(1, deadline - System.nanoTime());
                JsonNode message = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (message == null) {
                    return null;
                }
                if (type.equals(message.path("type").asText())) {
                    return message;
                }
            }
            return null;
        }

        CloseStatus awaitClose() throws Exception {
            return closeStatus.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void drain() {
            messages.clear();
        }

        void close() throws Exception {
            session.close();
        }

        void closeQuietly() {
            try {
                if (session != null && session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {
                // Test cleanup should not mask the acceptance result.
            }
        }
    }
}
