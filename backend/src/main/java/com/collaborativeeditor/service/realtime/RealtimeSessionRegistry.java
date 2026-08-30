package com.collaborativeeditor.service.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry managing active document room subscriptions, session bindings, and room broadcasts.
 */
@Component
public class RealtimeSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    // Document ID -> Set of active RealtimeSessions in the room
    private final Map<UUID, Set<RealtimeSession>> roomSessions = new ConcurrentHashMap<>();

    // Connection ID -> RealtimeSession
    private final Map<UUID, RealtimeSession> connectionSessions = new ConcurrentHashMap<>();

    public void registerSession(RealtimeSession session) {
        Objects.requireNonNull(session, "session must not be null");
        connectionSessions.put(session.getConnectionId(), session);
        roomSessions.computeIfAbsent(session.getDocumentId(), k -> ConcurrentHashMap.newKeySet()).add(session);
        log.debug("Registered realtime session connectionId={} for docId={}", session.getConnectionId(), session.getDocumentId());
    }

    public void bindClientId(RealtimeSession newSession, UUID clientId) {
        Objects.requireNonNull(newSession, "newSession must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");

        Set<RealtimeSession> room = roomSessions.get(newSession.getDocumentId());
        if (room != null) {
            for (RealtimeSession existing : room) {
                if (!existing.getConnectionId().equals(newSession.getConnectionId())
                        && clientId.equals(existing.getClientId())) {
                    log.info("Closing superseded session connectionId={} for clientId={} in docId={}",
                            existing.getConnectionId(), clientId, newSession.getDocumentId());
                    existing.close(new CloseStatus(4004, "SESSION_SUPERSEDED"));
                    room.remove(existing);
                    connectionSessions.remove(existing.getConnectionId());
                }
            }
        }

        newSession.setClientId(clientId);
    }

    public void unregisterSession(RealtimeSession session) {
        if (session == null) {
            return;
        }
        connectionSessions.remove(session.getConnectionId());
        Set<RealtimeSession> room = roomSessions.get(session.getDocumentId());
        if (room != null) {
            room.remove(session);
            if (room.isEmpty()) {
                roomSessions.remove(session.getDocumentId());
            }
        }
        log.debug("Unregistered realtime session connectionId={} for docId={}", session.getConnectionId(), session.getDocumentId());
    }

    public RealtimeSession getSession(UUID connectionId) {
        return connectionSessions.get(connectionId);
    }

    public Set<RealtimeSession> getRoomSessions(UUID documentId) {
        Set<RealtimeSession> room = roomSessions.get(documentId);
        return room != null ? Collections.unmodifiableSet(room) : Collections.emptySet();
    }

    public void broadcastToRoom(UUID documentId, String messageJson) {
        Set<RealtimeSession> room = roomSessions.get(documentId);
        if (room == null || room.isEmpty()) {
            return;
        }

        for (RealtimeSession session : room) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(messageJson);
                } catch (IOException e) {
                    log.warn("Failed to deliver broadcast message to connectionId={}: {}", session.getConnectionId(), e.getMessage());
                }
            }
        }
    }
}
