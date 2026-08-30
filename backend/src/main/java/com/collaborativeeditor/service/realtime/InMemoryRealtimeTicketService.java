package com.collaborativeeditor.service.realtime;

import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe implementation of RealtimeTicketService.
 * Can be cleanly adapted to a Redis-backed implementation when Redis coordination is integrated.
 */
@Service
public class InMemoryRealtimeTicketService implements RealtimeTicketService {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TICKET_RANDOM_LENGTH = 32;
    private static final int TTL_SECONDS = 60;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, RealtimeTicket> ticketStore = new ConcurrentHashMap<>();

    @Override
    public RealtimeTicket issueTicket(UUID documentId, UUID userId, DocumentRole role) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");

        String ticketString = generateSecureTicket();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(TTL_SECONDS);

        RealtimeTicket ticket = new RealtimeTicket(ticketString, documentId, userId, role, expiresAt);
        ticketStore.put(ticketString, ticket);
        return ticket;
    }

    @Override
    public Optional<RealtimeTicket> consumeTicket(String ticketString, UUID documentId) {
        if (ticketString == null || ticketString.isBlank() || documentId == null) {
            return Optional.empty();
        }

        RealtimeTicket ticket = ticketStore.remove(ticketString);
        if (ticket == null) {
            return Optional.empty();
        }

        if (ticket.isExpired() || !ticket.documentId().equals(documentId)) {
            return Optional.empty();
        }

        return Optional.of(ticket);
    }

    private String generateSecureTicket() {
        StringBuilder sb = new StringBuilder("rt_");
        for (int i = 0; i < TICKET_RANDOM_LENGTH; i++) {
            int index = secureRandom.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }
        return sb.toString();
    }
}
