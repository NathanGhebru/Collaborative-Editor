package com.collaborativeeditor.realtime;

import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import com.collaborativeeditor.service.realtime.InMemoryRealtimeTicketService;
import com.collaborativeeditor.service.realtime.RealtimeHandshakeInterceptor;
import com.collaborativeeditor.service.realtime.RealtimeTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeWebSocketHandshakeTest {

    private RealtimeTicketService ticketService;
    private RealtimeHandshakeInterceptor interceptor;

    private ServerHttpRequest request;
    private ServerHttpResponse response;
    private WebSocketHandler wsHandler;

    private UUID docId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        ticketService = new InMemoryRealtimeTicketService();
        interceptor = new RealtimeHandshakeInterceptor(ticketService);

        request = mock(ServerHttpRequest.class);
        response = mock(ServerHttpResponse.class);
        wsHandler = mock(WebSocketHandler.class);

        docId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Valid ticket grants WebSocket handshake and populates connection attributes")
    void validTicketHandshakeSuccess() {
        RealtimeTicket ticket = ticketService.issueTicket(docId, userId, DocumentRole.OWNER);
        URI uri = URI.create("http://localhost:8080/ws/v1/documents/" + docId + "?ticket=" + ticket.ticket());
        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals(docId, attributes.get(RealtimeHandshakeInterceptor.ATTR_DOCUMENT_ID));
        assertEquals(userId, attributes.get(RealtimeHandshakeInterceptor.ATTR_USER_ID));
        assertEquals(DocumentRole.OWNER, attributes.get(RealtimeHandshakeInterceptor.ATTR_ROLE));
        assertNotNull(attributes.get(RealtimeHandshakeInterceptor.ATTR_CONNECTION_ID));
    }

    @Test
    @DisplayName("Missing ticket parameter rejects handshake with 401 UNAUTHORIZED")
    void missingTicketHandshakeRejected() {
        URI uri = URI.create("http://localhost:8080/ws/v1/documents/" + docId);
        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertTrue(attributes.isEmpty());
    }

    @Test
    @DisplayName("Invalid or consumed ticket rejects handshake with 401 UNAUTHORIZED")
    void invalidTicketHandshakeRejected() {
        URI uri = URI.create("http://localhost:8080/ws/v1/documents/" + docId + "?ticket=rt_invalid_12345");
        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Ticket issued for Doc A cannot be redeemed on Doc B handshake")
    void documentMismatchHandshakeRejected() {
        UUID docB = UUID.randomUUID();
        RealtimeTicket ticket = ticketService.issueTicket(docId, userId, DocumentRole.EDITOR);

        URI uri = URI.create("http://localhost:8080/ws/v1/documents/" + docB + "?ticket=" + ticket.ticket());
        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
