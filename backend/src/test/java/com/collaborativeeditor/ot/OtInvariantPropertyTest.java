package com.collaborativeeditor.ot;

import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.engine.OtEngine;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.ot.model.OperationKey;
import com.collaborativeeditor.ot.validation.OperationValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OT Deterministic Property & Invariant Tests")
public class OtInvariantPropertyTest {

    private static final long SEED = 0x07002L;
    private static final String[] VOCAB = {"a", "b", "c", " ", "\n", "1", "2", "3", "🚀", "✨", "🎉", "🔥"};

    @Test
    @DisplayName("Property: Pairwise TP1 Convergence across 2,000 deterministic random trials")
    void testTp1Convergence() {
        Random random = new Random(SEED);

        for (int i = 0; i < 2000; i++) {
            String initialDoc = generateRandomDoc(random, 0, 30);

            OperationKey keyA = new OperationKey(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            OperationKey keyB = new OperationKey(UUID.randomUUID().toString(), UUID.randomUUID().toString());

            Operation opA = generateValidOperation(random, initialDoc);
            Operation opB = generateValidOperation(random, initialDoc);

            // Execute OT
            Operation aPrime = OtEngine.transform(opA, opB, keyA, keyB);
            Operation bPrime = OtEngine.transform(opB, opA, keyB, keyA);

            // Apply A then B'
            String docA = DocumentApplier.apply(initialDoc, opA);
            String docAB = DocumentApplier.apply(docA, bPrime);

            // Apply B then A'
            String docB = DocumentApplier.apply(initialDoc, opB);
            String docBA = DocumentApplier.apply(docB, aPrime);

            // Assert TP1 convergence: S • A • B' == S • B • A'
            assertEquals(docAB, docBA, "TP1 convergence failure on iteration " + i
                + "\nInitial: [" + initialDoc + "]"
                + "\nOpA: " + opA + " (key: " + keyA + ")"
                + "\nOpB: " + opB + " (key: " + keyB + ")"
                + "\nAPrime: " + aPrime
                + "\nBPrime: " + bPrime);

            // Verify that transformed operations are valid on their intermediate documents
            OperationValidator.validate(docA, bPrime);
            OperationValidator.validate(docB, aPrime);
        }
    }

    @Test
    @DisplayName("Property: NO_OP Identity Invariant")
    void testNoOpIdentity() {
        Random random = new Random(SEED + 1);

        for (int i = 0; i < 500; i++) {
            String doc = generateRandomDoc(random, 1, 20);
            Operation op = generateValidOperation(random, doc);
            OperationKey keyA = new OperationKey(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            OperationKey keyB = new OperationKey(UUID.randomUUID().toString(), UUID.randomUUID().toString());

            Operation transAgainstNoOp = OtEngine.transform(op, NoOpOperation.INSTANCE, keyA, keyB);
            assertEquals(op, transAgainstNoOp);

            Operation transNoOpAgainstOp = OtEngine.transform(NoOpOperation.INSTANCE, op, keyA, keyB);
            assertInstanceOf(NoOpOperation.class, transNoOpAgainstOp);

            String appliedNoOp = DocumentApplier.apply(doc, NoOpOperation.INSTANCE);
            assertEquals(doc, appliedNoOp);
        }
    }

    @Test
    @DisplayName("Property: Flattening Invariant (no nested groups, no NO_OPs)")
    void testFlatteningInvariant() {
        Random random = new Random(SEED + 2);

        for (int i = 0; i < 500; i++) {
            List<Operation> raw = generateNestedOperationList(random, 3);
            Operation flattened = OtEngine.flatten(raw);

            if (flattened instanceof NoOpOperation) {
                // OK
            } else if (flattened instanceof InsertOperation || flattened instanceof DeleteOperation) {
                // Single primitive, OK
            } else if (flattened instanceof GroupOperation grp) {
                assertTrue(grp.operations().size() >= 2, "Flattened group must have >= 2 elements");
                for (Operation child : grp.operations()) {
                    assertFalse(child instanceof GroupOperation, "Flattened group must not contain nested groups");
                    assertFalse(child instanceof NoOpOperation, "Flattened group must not contain NO_OPs");
                }
            }
        }
    }

    private List<Operation> generateNestedOperationList(Random random, int depth) {
        int count = random.nextInt(4);
        List<Operation> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int choice = random.nextInt(4);
            if (choice == 0) {
                list.add(NoOpOperation.INSTANCE);
            } else if (choice == 1) {
                list.add(new InsertOperation(random.nextInt(10), "x"));
            } else if (choice == 2) {
                list.add(new DeleteOperation(random.nextInt(10), random.nextInt(5) + 1));
            } else if (depth > 0) {
                list.add(new GroupOperation(generateNestedOperationList(random, depth - 1)));
            }
        }
        return list;
    }

    private String generateRandomDoc(Random random, int minLen, int maxLen) {
        int count = minLen + random.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(VOCAB[random.nextInt(VOCAB.length)]);
        }
        return sb.toString();
    }

    private List<Integer> getValidBoundaries(String doc) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        for (int i = 1; i < doc.length(); i++) {
            if (!OperationValidator.bisectsSurrogatePair(doc, i)) {
                boundaries.add(i);
            }
        }
        boundaries.add(doc.length());
        return boundaries;
    }

    private Operation generateValidOperation(Random random, String doc) {
        List<Integer> boundaries = getValidBoundaries(doc);
        boolean doInsert = doc.isEmpty() || random.nextBoolean();

        if (doInsert) {
            int pos = boundaries.get(random.nextInt(boundaries.size()));
            String text = generateRandomDoc(random, 1, 4);
            return new InsertOperation(pos, text);
        } else {
            int startIdx = random.nextInt(boundaries.size() - 1);
            int endIdx = startIdx + 1 + random.nextInt(boundaries.size() - startIdx - 1);
            int startPos = boundaries.get(startIdx);
            int endPos = boundaries.get(endIdx);
            int len = endPos - startPos;
            return new DeleteOperation(startPos, len);
        }
    }
}

