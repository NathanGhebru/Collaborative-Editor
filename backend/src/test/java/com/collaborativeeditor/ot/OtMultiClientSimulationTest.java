package com.collaborativeeditor.ot;

import com.collaborativeeditor.ot.engine.ClientRebaseResult;
import com.collaborativeeditor.ot.engine.ClientRebaseUtils;
import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.engine.OtEngine;
import com.collaborativeeditor.ot.model.ClientOperation;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.ot.model.OperationKey;
import com.collaborativeeditor.ot.validation.OperationValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("OT Multi-Client Concurrency Simulation Tests")
public class OtMultiClientSimulationTest {

    private static final long SEED = 0x07003L;

    @Test
    @DisplayName("Simulate 3 concurrent clients editing with sequential local pending buffer")
    void testThreeClientsSimulation() {
        runSimulation(3, 50, SEED + 3);
    }

    @Test
    @DisplayName("Simulate 10 concurrent clients editing with sequential local pending buffer")
    void testTenClientsSimulation() {
        runSimulation(10, 100, SEED + 10);
    }

    @Test
    @DisplayName("Simulate 50 concurrent clients editing with sequential local pending buffer")
    void testFiftyClientsSimulation() {
        runSimulation(50, 150, SEED + 50);
    }

    private void runSimulation(int numClients, int totalEditsToGenerate, long seed) {
        Random random = new Random(seed);
        String initialDoc = "The quick brown fox jumps over the lazy dog 🚀.";

        // Server state
        String serverDoc = initialDoc;
        List<ClientOperation> canonicalHistory = new ArrayList<>(); // 0-indexed revisions 1..N

        // Client states
        List<SimulatedClient> clients = new ArrayList<>();
        for (int i = 0; i < numClients; i++) {
            clients.add(new SimulatedClient(UUID.randomUUID().toString(), initialDoc));
        }

        // Event queues
        Queue<ClientOperation> serverInbox = new LinkedList<>();

        int editsGenerated = 0;

        while (editsGenerated < totalEditsToGenerate || !serverInbox.isEmpty() || hasPendingClientWork(clients)) {
            // 1. Maybe generate local edit from a random client
            if (editsGenerated < totalEditsToGenerate && random.nextInt(3) == 0) {
                SimulatedClient client = clients.get(random.nextInt(numClients));
                Operation op = generateClientEdit(random, client.optimisticDoc);
                if (op != null) {
                    ClientOperation clientOp = client.generateLocalEdit(op);
                    editsGenerated++;
                    if (clientOp != null) {
                        // Client had no in-flight op, so this went in-flight immediately
                        serverInbox.add(clientOp);
                    }
                }
            }

            // 2. Process an operation at the server if available
            if (!serverInbox.isEmpty()) {
                ClientOperation incoming = serverInbox.poll();

                // Transform incoming against canonical history since incoming.baseRevision()
                Operation currentOp = incoming.operation();
                OperationKey incomingKey = incoming.getOperationKey();

                for (int rev = (int) incoming.baseRevision(); rev < canonicalHistory.size(); rev++) {
                    ClientOperation canonicalOp = canonicalHistory.get(rev);
                    currentOp = OtEngine.transform(currentOp, canonicalOp.operation(), incomingKey, canonicalOp.getOperationKey());
                }

                long assignedRevision = canonicalHistory.size() + 1;
                ClientOperation canonicalAccepted = new ClientOperation(
                    incoming.clientId(),
                    incoming.clientOperationId(),
                    assignedRevision,
                    currentOp
                );

                canonicalHistory.add(canonicalAccepted);
                serverDoc = DocumentApplier.apply(serverDoc, currentOp);

                // Broadcast canonical operation to all clients
                for (SimulatedClient client : clients) {
                    ClientOperation nextToSend = client.receiveCanonical(canonicalAccepted);
                    if (nextToSend != null) {
                        serverInbox.add(nextToSend);
                    }
                }
            }
        }

        // Verify final convergence across all clients and server
        for (int i = 0; i < numClients; i++) {
            SimulatedClient client = clients.get(i);
            assertEquals(canonicalHistory.size(), client.confirmedRevision, "Client " + i + " revision mismatch");
            assertEquals(serverDoc, client.confirmedDoc, "Client " + i + " confirmed doc mismatch");
            assertEquals(serverDoc, client.optimisticDoc, "Client " + i + " optimistic doc mismatch");
            assertEquals(0, client.pendingBuffer.size(), "Client " + i + " pending buffer not drained");
        }
    }

    private boolean hasPendingClientWork(List<SimulatedClient> clients) {
        for (SimulatedClient client : clients) {
            if (client.inFlight != null || !client.pendingBuffer.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Operation generateClientEdit(Random random, String doc) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        for (int i = 1; i < doc.length(); i++) {
            if (!OperationValidator.bisectsSurrogatePair(doc, i)) {
                boundaries.add(i);
            }
        }
        boundaries.add(doc.length());

        boolean doInsert = doc.isEmpty() || random.nextBoolean();
        if (doInsert) {
            int pos = boundaries.get(random.nextInt(boundaries.size()));
            String[] tokens = {"X", "12", "hello ", "✨", " "};
            return new InsertOperation(pos, tokens[random.nextInt(tokens.length)]);
        } else {
            if (boundaries.size() < 2) {
                return null;
            }
            int startIdx = random.nextInt(boundaries.size() - 1);
            int maxLen = Math.min(boundaries.size() - startIdx - 1, 3);
            int endIdx = startIdx + 1 + random.nextInt(maxLen);
            int startPos = boundaries.get(startIdx);
            int endPos = boundaries.get(endIdx);
            return new DeleteOperation(startPos, endPos - startPos);
        }
    }

    private static class SimulatedClient {
        final String clientId;
        long confirmedRevision = 0;
        String confirmedDoc;
        String optimisticDoc;
        ClientOperation inFlight = null;
        final List<ClientOperation> pendingBuffer = new ArrayList<>();

        SimulatedClient(String clientId, String initialDoc) {
            this.clientId = clientId;
            this.confirmedDoc = initialDoc;
            this.optimisticDoc = initialDoc;
        }

        ClientOperation generateLocalEdit(Operation op) {
            ClientOperation localOp = new ClientOperation(clientId, UUID.randomUUID().toString(), confirmedRevision, op);
            optimisticDoc = DocumentApplier.apply(optimisticDoc, op);

            if (inFlight == null) {
                inFlight = localOp;
                return inFlight;
            } else {
                pendingBuffer.add(localOp);
                return null;
            }
        }

        ClientOperation receiveCanonical(ClientOperation canonicalOp) {
            boolean isOwnAck = canonicalOp.clientId().equals(this.clientId)
                && inFlight != null
                && canonicalOp.clientOperationId().equals(inFlight.clientOperationId());

            if (isOwnAck) {
                // In-flight operation confirmed
                confirmedDoc = DocumentApplier.apply(confirmedDoc, canonicalOp.operation());
                confirmedRevision = canonicalOp.baseRevision();
                inFlight = null;

                if (!pendingBuffer.isEmpty()) {
                    // Promote first buffered op to inFlight
                    ClientOperation next = pendingBuffer.remove(0);
                    inFlight = next.withBaseRevision(confirmedRevision);
                    return inFlight;
                }
                return null;
            }

            // Remote canonical operation arrives
            ClientRebaseResult rebaseResult = ClientRebaseUtils.rebase(canonicalOp, inFlight, pendingBuffer);

            confirmedDoc = DocumentApplier.apply(confirmedDoc, canonicalOp.operation());
            confirmedRevision = canonicalOp.baseRevision();

            inFlight = rebaseResult.transformedInFlight();
            pendingBuffer.clear();
            pendingBuffer.addAll(rebaseResult.transformedBuffered());

            optimisticDoc = DocumentApplier.apply(optimisticDoc, rebaseResult.transformedRemoteForOptimistic());
            return null;
        }
    }
}

