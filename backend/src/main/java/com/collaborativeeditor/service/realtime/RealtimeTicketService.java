package com.collaborativeeditor.service.realtime;

import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;

import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the issuance and atomic single-use consumption of realtime WebSocket tickets.
 */
public interface RealtimeTicketService {

    /**
     * Issues a short-lived (60s TTL), high-entropy, single-use ticket for a user and document.
     */
    RealtimeTicket issueTicket(UUID documentId, UUID userId, DocumentRole role);

    /**
     * Atomically consumes a ticket. Returns the RealtimeTicket if valid, unexpired, and matching documentId.
     */
    Optional<RealtimeTicket> consumeTicket(String ticketString, UUID documentId);
}
