package com.collaborativeeditor.ot;

import com.collaborativeeditor.ot.engine.ClientRebaseResult;
import com.collaborativeeditor.ot.engine.ClientRebaseUtils;
import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.engine.OtEngine;
import com.collaborativeeditor.ot.model.ClientOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads and verifies all 23 canonical test vectors from docs/ot-test-vectors.json.
 */
public class CanonicalTestVectorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static JsonNode rootNode;
    private static final List<JsonNode> vectorNodes = new ArrayList<>();

    @BeforeAll
    static void loadVectors() throws IOException {
        Path[] candidatePaths = new Path[] {
            Paths.get("docs/ot-test-vectors.json"),
            Paths.get("../docs/ot-test-vectors.json"),
            Paths.get("../../docs/ot-test-vectors.json"),
            Paths.get(System.getProperty("user.dir"), "docs/ot-test-vectors.json"),
            Paths.get(System.getProperty("user.dir"), "../docs/ot-test-vectors.json")
        };

        File vectorFile = null;
        for (Path p : candidatePaths) {
            File f = p.toFile();
            if (f.exists() && f.isFile()) {
                vectorFile = f;
                break;
            }
        }

        assertNotNull(vectorFile, "Canonical test vector file docs/ot-test-vectors.json must exist");
        rootNode = OBJECT_MAPPER.readTree(vectorFile);
        JsonNode vectorsArray = rootNode.get("vectors");
        assertNotNull(vectorsArray, "vectors array must exist in ot-test-vectors.json");
        assertTrue(vectorsArray.isArray(), "vectors must be an array");

        for (JsonNode node : vectorsArray) {
            vectorNodes.add(node);
        }

        assertEquals(23, vectorNodes.size(), "Expected exactly 23 canonical test vectors");
    }

    @TestFactory
    Stream<DynamicTest> testCanonicalVectors() {
        return vectorNodes.stream().map(node -> {
            String id = node.get("id").asText();
            String description = node.has("description") ? node.get("description").asText() : id;
            return DynamicTest.dynamicTest(id + ": " + description, () -> runVector(node));
        });
    }

    private void runVector(JsonNode node) throws Exception {
        String id = node.get("id").asText();

        if ("vec-client-queue-rebase-three-step".equals(id)) {
            runClientQueueRebaseVector(node);
            return;
        }

        String initialDocument = node.get("initialDocument").asText();
        ClientOperation opA = OBJECT_MAPPER.treeToValue(node.get("opA"), ClientOperation.class);
        ClientOperation opB = OBJECT_MAPPER.treeToValue(node.get("opB"), ClientOperation.class);
        Operation expectedA = OBJECT_MAPPER.treeToValue(node.get("expectedTransformedA"), Operation.class);
        Operation expectedB = OBJECT_MAPPER.treeToValue(node.get("expectedTransformedB"), Operation.class);
        String expectedDocAfterAThenBPrime = node.get("expectedDocAfterAThenBPrime").asText();
        String expectedDocAfterBThenAPrime = node.get("expectedDocAfterBThenAPrime").asText();
        String expectedConvergedDocument = node.get("expectedConvergedDocument").asText();

        // 1. Transform A against B
        Operation transformedA = OtEngine.transform(opA, opB);
        assertEquals(expectedA, transformedA, id + " - transformed A does not match expected");

        // 2. Transform B against A
        Operation transformedB = OtEngine.transform(opB, opA);
        assertEquals(expectedB, transformedB, id + " - transformed B does not match expected");

        // 3. Apply A then transformed B to initial doc
        String docAfterA = DocumentApplier.apply(initialDocument, opA.operation());
        String docAfterAThenBPrime = DocumentApplier.apply(docAfterA, transformedB);
        assertEquals(expectedDocAfterAThenBPrime, docAfterAThenBPrime, id + " - docAfterAThenBPrime mismatch");

        // 4. Apply B then transformed A to initial doc
        String docAfterB = DocumentApplier.apply(initialDocument, opB.operation());
        String docAfterBThenAPrime = DocumentApplier.apply(docAfterB, transformedA);
        assertEquals(expectedDocAfterBThenAPrime, docAfterBThenAPrime, id + " - docAfterBThenAPrime mismatch");

        // 5. Assert final convergence
        assertEquals(expectedConvergedDocument, docAfterAThenBPrime, id + " - converged document mismatch");
        assertEquals(expectedConvergedDocument, docAfterBThenAPrime, id + " - converged document mismatch");
    }

    private void runClientQueueRebaseVector(JsonNode node) throws Exception {
        String initialDocument = node.get("initialDocument").asText();
        ClientOperation opR = OBJECT_MAPPER.treeToValue(node.get("opR"), ClientOperation.class);
        ClientOperation clientInFlight = OBJECT_MAPPER.treeToValue(node.get("clientInFlight"), ClientOperation.class);
        ClientOperation clientBuffered = OBJECT_MAPPER.treeToValue(node.get("clientBuffered"), ClientOperation.class);

        Operation expectedTransformedInFlight = OBJECT_MAPPER.treeToValue(
            node.get("expectedTransformedInFlight"), Operation.class);
        Operation expectedTransformedBuffered = OBJECT_MAPPER.treeToValue(
            node.get("expectedTransformedBuffered"), Operation.class);
        Operation expectedTransformedRForOptimistic = OBJECT_MAPPER.treeToValue(
            node.get("expectedTransformedRForOptimistic"), Operation.class);
        String expectedFinalOptimisticDoc = node.get("expectedFinalOptimisticDoc").asText();

        ClientRebaseResult result = ClientRebaseUtils.rebase(opR, clientInFlight, List.of(clientBuffered));

        assertNotNull(result.transformedInFlight());
        assertEquals(expectedTransformedInFlight, result.transformedInFlight().operation(),
            "Transformed in-flight op does not match expected");

        assertEquals(1, result.transformedBuffered().size());
        assertEquals(expectedTransformedBuffered, result.transformedBuffered().get(0).operation(),
            "Transformed buffered op does not match expected");

        assertEquals(expectedTransformedRForOptimistic, result.transformedRemoteForOptimistic(),
            "Transformed remote op for optimistic state does not match expected");

        // Compute local optimistic document state before R: initial -> inFlight -> buffered
        String optimisticDocBeforeR = DocumentApplier.apply(initialDocument, clientInFlight.operation());
        optimisticDocBeforeR = DocumentApplier.apply(optimisticDocBeforeR, clientBuffered.operation());
        assertEquals("A12BCDEF", optimisticDocBeforeR);

        // Apply transformed remote op to optimistic doc
        String finalOptimisticDoc = DocumentApplier.apply(optimisticDocBeforeR, result.transformedRemoteForOptimistic());
        assertEquals(expectedFinalOptimisticDoc, finalOptimisticDoc,
            "Final optimistic document does not match expected");
    }
}

