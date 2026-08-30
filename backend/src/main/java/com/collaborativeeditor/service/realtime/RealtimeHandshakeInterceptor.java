package com.collaborativeeditor.service.realtime;

import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handshake interceptor that validates the single-use ticket query parameter and extracts connection attributes.
 */
@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHandshakeInterceptor.class);

    public static final String ATTR_DOCUMENT_ID = "DOCUMENT_ID";
    public static final String ATTR_USER_ID = "USER_ID";
    public static final String ATTR_ROLE = "ROLE";
    public static final String ATTR_CONNECTION_ID = "CONNECTION_ID";

    private final RealtimeTicketService realtimeTicketService;

    public RealtimeHandshakeInterceptor(RealtimeTicketService realtimeTicketService) {
        this.realtimeTicketService = realtimeTicketService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        URI uri = request.getURI();
        String path = uri.getPath();

        // Path format: /ws/v1/documents/{documentId}
        UUID documentId = extractDocumentId(path);
        if (documentId == null) {
            log.warn("WebSocket handshake rejected: invalid path {}", path);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String ticketString = extractTicketQueryParam(uri.getQuery());
        if (ticketString == null || ticketString.isBlank()) {
            log.warn("WebSocket handshake rejected for docId={}: missing ticket parameter", documentId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Optional<RealtimeTicket> ticketOpt = realtimeTicketService.consumeTicket(ticketString, documentId);
        if (ticketOpt.isEmpty()) {
            log.warn("WebSocket handshake rejected for docId={}: ticket is invalid, expired, or already used", documentId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        RealtimeTicket ticket = ticketOpt.get();
        UUID connectionId = UUID.randomUUID();

        attributes.put(ATTR_DOCUMENT_ID, ticket.documentId());
        attributes.put(ATTR_USER_ID, ticket.userId());
        attributes.put(ATTR_ROLE, ticket.role());
        attributes.put(ATTR_CONNECTION_ID, connectionId);

        log.debug("WebSocket handshake authenticated for connectionId={} docId={} userId={} role={}",
                connectionId, ticket.documentId(), ticket.userId(), ticket.role());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No-op
    }

    private UUID extractDocumentId(String path) {
        if (path == null) {
            return null;
        }
        String prefix = "/ws/v1/documents/";
        int idx = path.indexOf(prefix);
        if (idx == -1) {
            return null;
        }
        String docIdStr = path.substring(idx + prefix.length());
        if (docIdStr.endsWith("/")) {
            docIdStr = docIdStr.substring(0, docIdStr.length() - 1);
        }
        try {
            return UUID.fromString(docIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractTicketQueryParam(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                if ("ticket".equals(key)) {
                    return value;
                }
            }
        }
        return null;
    }
}
