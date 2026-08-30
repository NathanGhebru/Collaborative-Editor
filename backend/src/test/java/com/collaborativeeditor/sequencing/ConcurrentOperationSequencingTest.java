package com.collaborativeeditor.sequencing;

import com.collaborativeeditor.domain.document.Document;
import com.collaborativeeditor.domain.document.DocumentOperationBatchRepository;
import com.collaborativeeditor.domain.document.DocumentOperationIdRepository;
import com.collaborativeeditor.domain.document.DocumentRepository;
import com.collaborativeeditor.domain.document.DocumentSnapshot;
import com.collaborativeeditor.domain.document.DocumentSnapshotRepository;
import com.collaborativeeditor.domain.user.AccountStatus;
import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.domain.user.UserRepository;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.service.persistence.DocumentRecoveryResult;
import com.collaborativeeditor.service.persistence.OperationPersistenceService;
import com.collaborativeeditor.service.sequencing.AcceptedOperationResult;
import com.collaborativeeditor.service.sequencing.DocumentSequencingService;
import com.collaborativeeditor.service.sequencing.SubmitOperationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.jwt.secret=c3VwZXItc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtZW52aXJvbm1lbnQtY29sbGFiLWVkaXRvcg==")
@ActiveProfiles("test")
@DisplayName("Concurrent Multi-Threaded Operation Sequencing Tests")
public class ConcurrentOperationSequencingTest {

    @Autowired
    private DocumentSequencingService sequencingService;

    @Autowired
    private OperationPersistenceService persistenceService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentOperationBatchRepository batchRepository;

    @Autowired
    private DocumentOperationIdRepository operationIdRepository;

    @Autowired
    private DocumentSnapshotRepository snapshotRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private Document testDoc;
    private UUID docId;
    private UUID syncEpoch;

    @BeforeEach
    void setUp() {
        operationIdRepository.deleteAll();
        batchRepository.deleteAll();
        snapshotRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(new User(
            UUID.randomUUID(), "concurauthor", "concur@test.com", "hash", "Concur Author",
            AccountStatus.ACTIVE, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        docId = UUID.randomUUID();
        syncEpoch = UUID.randomUUID();
        testDoc = documentRepository.save(new Document(
            docId, testUser, "Concurrent Doc", syncEpoch, 0L, OffsetDateTime.now(), OffsetDateTime.now()
        ));

        snapshotRepository.save(new DocumentSnapshot(
            UUID.randomUUID(), testDoc, syncEpoch, 0L, "START-",
            OperationPersistenceService.calculateSha256("START-"), OffsetDateTime.now()
        ));
    }

    @Test
    @DisplayName("10 concurrent clients submitting from baseRevision 0 all serialize and converge without losing edits")
    void test10ConcurrentClientsFromBaseRevision0() throws Exception {
        int clientCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<AcceptedOperationResult>> futures = new ArrayList<>();
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < clientCount; i++) {
            final String token = "[" + i + "]";
            tokens.add(token);
            final UUID clientId = UUID.randomUUID();
            final UUID clientOpId = UUID.randomUUID();

            futures.add(executor.submit(() -> {
                startLatch.await(); // Simultaneous start
                SubmitOperationCommand cmd = new SubmitOperationCommand(
                    docId, syncEpoch, clientId, clientOpId, testUser.getId(), 0L, new InsertOperation(6, token)
                );
                return sequencingService.submitOperation(cmd);
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        List<AcceptedOperationResult> results = new ArrayList<>();
        for (Future<AcceptedOperationResult> future : futures) {
            results.add(future.get());
        }

        // Verify all 10 results succeeded
        assertEquals(clientCount, results.size());

        // Verify final document revision in database is 10
        Document doc = documentRepository.findById(docId).orElseThrow();
        assertEquals((long) clientCount, doc.getCurrentRevision());

        // Verify recovery contains all inserted tokens
        DocumentRecoveryResult recovery = persistenceService.recoverDocument(docId);
        assertEquals((long) clientCount, recovery.revision());
        assertEquals(clientCount, recovery.operationsReplayed());

        String finalContent = recovery.content();
        assertTrue(finalContent.startsWith("START-"));
        for (String token : tokens) {
            assertTrue(finalContent.contains(token), "Expected final text to contain " + token + ", but was: " + finalContent);
        }
    }
}
