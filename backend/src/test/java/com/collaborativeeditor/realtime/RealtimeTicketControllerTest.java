package com.collaborativeeditor.realtime;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentPermission;
import com.collaborativeeditor.domain.document.DocumentPermissionRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentRole;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.dto.realtime.RealtimeTicket;
import com.collaborativeeditor.security.JwtTokenProvider;
import com.collaborativeeditor.service.realtime.RealtimeTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealtimeTicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentPermissionRepository permissionRepository;

    @Autowired
    private RealtimeTicketService ticketService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User owner;
    private User editor;
    private User outsider;
    private Document document;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(new User(
                UUID.randomUUID(), "ownerUser", "owner@test.com", "hash", "Owner User",
                AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()));
        editor = userRepository.save(new User(
                UUID.randomUUID(), "editorUser", "editor@test.com", "hash", "Editor User",
                AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()));
        outsider = userRepository.save(new User(
                UUID.randomUUID(), "outsiderUser", "outsider@test.com", "hash", "Outsider User",
                AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()));

        document = documentRepository.save(new Document(
                UUID.randomUUID(), owner, "Realtime Test Doc", UUID.randomUUID(), 0L,
                OffsetDateTime.now(), OffsetDateTime.now()));

        permissionRepository.save(new DocumentPermission(
                UUID.randomUUID(), document, editor, DocumentRole.EDITOR, owner,
                OffsetDateTime.now(), OffsetDateTime.now()));
    }

    @Test
    @DisplayName("Owner successfully issues realtime ticket")
    void ownerIssuesRealtimeTicket() throws Exception {
        String token = jwtTokenProvider.generateToken(owner);

        mockMvc.perform(post("/api/v1/documents/{documentId}/realtime-ticket", document.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket").isString())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.websocketPath").value("/ws/v1/documents/" + document.getId()));
    }

    @Test
    @DisplayName("Editor successfully issues realtime ticket")
    void editorIssuesRealtimeTicket() throws Exception {
        String token = jwtTokenProvider.generateToken(editor);

        mockMvc.perform(post("/api/v1/documents/{documentId}/realtime-ticket", document.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket").isString())
                .andExpect(jsonPath("$.websocketPath").value("/ws/v1/documents/" + document.getId()));
    }

    @Test
    @DisplayName("Unauthorized user without permission cannot see or issue ticket for document (404 DOCUMENT_NOT_FOUND)")
    void concealDocumentForUnauthorizedUser() throws Exception {
        String token = jwtTokenProvider.generateToken(outsider);

        mockMvc.perform(post("/api/v1/documents/{documentId}/realtime-ticket", document.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Unauthenticated request is rejected with 401")
    void unauthenticatedRequestRejected() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/realtime-ticket", document.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Issued ticket is single-use and consumed once")
    void ticketSingleUseConsumption() throws Exception {
        RealtimeTicket ticket = ticketService.issueTicket(document.getId(), owner.getId(), DocumentRole.OWNER);
        assertNotNull(ticket.ticket());

        Optional<RealtimeTicket> consumed = ticketService.consumeTicket(ticket.ticket(), document.getId());
        assertTrue(consumed.isPresent());

        // Second consumption must fail
        Optional<RealtimeTicket> secondAttempt = ticketService.consumeTicket(ticket.ticket(), document.getId());
        assertFalse(secondAttempt.isPresent());
    }
}
