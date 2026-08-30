package com.collaborativeeditor.service.realtime;

import com.collaborativeeditor.domain.document.DocumentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulates the runtime state of an active WebSocket collaboration connection.
 */
public class RealtimeSession {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSession.class);

    public enum State {
        AWAITING_HELLO,
        ACTIVE,
        CLOSED
    }

    private final WebSocketSession session;
    private final UUID connectionId;
    private final UUID documentId;
    private final UUID userId;
    private final DocumentRole role;

    private volatile UUID clientId;
    private volatile State state;

    public RealtimeSession(
            WebSocketSession session,
            UUID connectionId,
            UUID documentId,
            UUID userId,
            DocumentRole role) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.state = State.AWAITING_HELLO;
    }

    public WebSocketSession getSession() {
        return session;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getUserId() {
        return userId;
    }

    public DocumentRole getRole() {
        return role;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public boolean isOpen() {
        return session.isOpen() && state != State.CLOSED;
    }

    public void sendMessage(String json) throws IOException {
        if (!isOpen()) {
            return;
        }
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    public void close(CloseStatus closeStatus) {
        this.state = State.CLOSED;
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.close(closeStatus);
                }
            }
        } catch (IOException e) {
            log.warn("Error closing WebSocket session connectionId={}: {}", connectionId, e.getMessage());
        }
    }
}
