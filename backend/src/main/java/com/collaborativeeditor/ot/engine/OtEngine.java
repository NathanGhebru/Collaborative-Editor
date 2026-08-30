package com.collaborativeeditor.ot.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.collaborativeeditor.ot.model.ClientOperation;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.ot.model.OperationKey;

/**
 * Pure Operational Transformation engine implementing frozen ADR-001 formulas.
 */
public final class OtEngine {

    private OtEngine() {}

    /**
     * Transforms operation A against operation B, assuming both originally applied to the same base state.
     *
     * @param a Operation A to transform.
     * @param b Concurrent operation B that has already been applied.
     * @param keyA Deterministic tie-breaking key for operation A.
     * @param keyB Deterministic tie-breaking key for operation B.
     * @return Transformed operation A'.
     */
    public static Operation transform(Operation a, Operation b, OperationKey keyA, OperationKey keyB) {
        Objects.requireNonNull(a, "operation A must not be null");
        Objects.requireNonNull(b, "operation B must not be null");

        // Identity and NO_OP rules (ADR-001 Section 21.1)
        if (a instanceof NoOpOperation) {
            return NoOpOperation.INSTANCE;
        }
        if (b instanceof NoOpOperation) {
            return a;
        }

        // Composite GROUP transformations (ADR-001 Section 21)
        if (a instanceof GroupOperation groupA && b instanceof GroupOperation groupB) {
            return transformGroupVsGroup(groupA, groupB, keyA, keyB);
        }
        if (a instanceof GroupOperation groupA) {
            return transformGroupVsPrimitive(groupA, b, keyA, keyB);
        }
        if (b instanceof GroupOperation groupB) {
            return transformPrimitiveVsGroup(a, groupB, keyA, keyB);
        }

        // Pairwise primitive transformations
        if (a instanceof InsertOperation insertA && b instanceof InsertOperation insertB) {
            return transformInsertVsInsert(insertA, insertB, keyA, keyB);
        }
        if (a instanceof InsertOperation insertA && b instanceof DeleteOperation deleteB) {
            return transformInsertVsDelete(insertA, deleteB);
        }
        if (a instanceof DeleteOperation deleteA && b instanceof InsertOperation insertB) {
            return transformDeleteVsInsert(deleteA, insertB);
        }
        if (a instanceof DeleteOperation deleteA && b instanceof DeleteOperation deleteB) {
            return transformDeleteVsDelete(deleteA, deleteB);
        }

        throw new IllegalArgumentException("Unsupported operation pair: " + a.getClass().getSimpleName()
            + " vs " + b.getClass().getSimpleName());
    }

    /**
     * Convenience method to transform two ClientOperation envelopes.
     */
    public static Operation transform(ClientOperation a, ClientOperation b) {
        Objects.requireNonNull(a, "ClientOperation A must not be null");
        Objects.requireNonNull(b, "ClientOperation B must not be null");
        return transform(a.operation(), b.operation(), a.getOperationKey(), b.getOperationKey());
    }

    // --- Pairwise Primitive Transformations ---

    /**
     * INSERT vs INSERT (ADR-001 Section 16)
     */
    private static Operation transformInsertVsInsert(
        InsertOperation a,
        InsertOperation b,
        OperationKey keyA,
        OperationKey keyB
    ) {
        int posA = a.position();
        int posB = b.position();
        int lenB = b.text().length();

        if (posA < posB) {
            return a;
        }
        if (posA > posB) {
            return new InsertOperation(posA + lenB, a.text());
        }

        // Same position tie-breaking (ADR-001 Section 14 & 16)
        if (keyA != null && keyB != null && keyA.compareTo(keyB) < 0) {
            // A has precedence (smaller key), stays at posA
            return a;
        } else {
            // B has precedence, A shifts right
            return new InsertOperation(posA + lenB, a.text());
        }
    }

    /**
     * INSERT vs DELETE (ADR-001 Section 17 & 20)
     */
    private static Operation transformInsertVsDelete(InsertOperation a, DeleteOperation b) {
        int posA = a.position();
        int posB = b.position();
        int lenB = b.length();
        int endB = posB + lenB;

        if (posA <= posB) {
            return a;
        }
        if (posA >= endB) {
            return new InsertOperation(posA - lenB, a.text());
        }

        // posB < posA < endB: Insert-wins collapses insertion to deletion boundary
        return new InsertOperation(posB, a.text());
    }

    /**
     * DELETE vs INSERT (ADR-001 Section 18 & 20)
     */
    private static Operation transformDeleteVsInsert(DeleteOperation a, InsertOperation b) {
        int posA = a.position();
        int lenA = a.length();
        int endA = posA + lenA;
        int posB = b.position();
        int insertLen = b.text().length();

        if (posB < posA) {
            return new DeleteOperation(posA + insertLen, lenA);
        }
        if (posB >= endA) {
            return a;
        }
        if (posB == posA) {
            // Inserted text survives before A, delete shifts right
            return new DeleteOperation(posA + insertLen, lenA);
        }

        // posA < posB < endA: Insert-wins splits deletion into two segments around inserted text
        DeleteOperation leftDelete = new DeleteOperation(posA, posB - posA);
        DeleteOperation rightDelete = new DeleteOperation(posA + insertLen, endA - posB);
        return new GroupOperation(List.of(leftDelete, rightDelete));
    }

    /**
     * DELETE vs DELETE (ADR-001 Section 19)
     */
    private static Operation transformDeleteVsDelete(DeleteOperation a, DeleteOperation b) {
        int posA = a.position();
        int lenA = a.length();
        int endA = posA + lenA;
        int posB = b.position();
        int lenB = b.length();
        int endB = posB + lenB;

        // B entirely before A
        if (endB <= posA) {
            return new DeleteOperation(posA - lenB, lenA);
        }

        // B entirely after A
        if (posB >= endA) {
            return a;
        }

        // Overlapping deletes
        int overlap = Math.max(0, Math.min(endA, endB) - Math.max(posA, posB));
        int newLength = lenA - overlap;

        if (newLength == 0) {
            return NoOpOperation.INSTANCE;
        }

        if (posA < posB) {
            return new DeleteOperation(posA, newLength);
        } else {
            return new DeleteOperation(posB, newLength);
        }
    }

    // --- Composite GROUP Transformations (ADR-001 Section 21) ---

    /**
     * Primitive vs GROUP (ADR-001 Section 21.2)
     */
    private static Operation transformPrimitiveVsGroup(
        Operation p,
        GroupOperation group,
        OperationKey keyP,
        OperationKey keyG
    ) {
        Operation current = p;
        for (Operation element : group.operations()) {
            current = transform(current, element, keyP, keyG);
        }
        return current;
    }

    /**
     * GROUP vs Primitive (ADR-001 Section 21.3)
     */
    private static Operation transformGroupVsPrimitive(
        GroupOperation group,
        Operation p,
        OperationKey keyG,
        OperationKey keyP
    ) {
        Operation currentP = p;
        List<Operation> transformedElements = new ArrayList<>();

        for (Operation element : group.operations()) {
            Operation transformedElement = transform(element, currentP, keyG, keyP);
            currentP = transform(currentP, element, keyP, keyG);
            if (!(transformedElement instanceof NoOpOperation)) {
                transformedElements.add(transformedElement);
            }
        }

        return flatten(transformedElements);
    }

    /**
     * GROUP vs GROUP (ADR-001 Section 21.4)
     */
    private static Operation transformGroupVsGroup(
        GroupOperation groupA,
        GroupOperation groupB,
        OperationKey keyA,
        OperationKey keyB
    ) {
        Operation currentGB = groupB;
        List<Operation> transformedElements = new ArrayList<>();

        for (Operation elementA : groupA.operations()) {
            Operation transformedA = transform(elementA, currentGB, keyA, keyB);
            currentGB = transform(currentGB, elementA, keyB, keyA);
            if (!(transformedA instanceof NoOpOperation)) {
                transformedElements.add(transformedA);
            }
        }

        return flatten(transformedElements);
    }

    /**
     * Flattens a list of operations according to ADR-001 Section 21.5:
     * - Unpacks nested GROUP operations.
     * - Removes NO_OPs.
     * - 0 operations -> NO_OP.
     * - 1 operation -> single primitive.
     * - 2+ operations -> GROUP.
     */
    public static Operation flatten(List<Operation> operations) {
        if (operations == null || operations.isEmpty()) {
            return NoOpOperation.INSTANCE;
        }

        List<Operation> flatList = new ArrayList<>();
        collectFlattened(operations, flatList);

        if (flatList.isEmpty()) {
            return NoOpOperation.INSTANCE;
        }
        if (flatList.size() == 1) {
            return flatList.get(0);
        }
        return new GroupOperation(flatList);
    }

    private static void collectFlattened(List<Operation> input, List<Operation> output) {
        for (Operation op : input) {
            if (op instanceof GroupOperation grp) {
                collectFlattened(grp.operations(), output);
            } else if (!(op instanceof NoOpOperation)) {
                output.add(op);
            }
        }
    }
}

