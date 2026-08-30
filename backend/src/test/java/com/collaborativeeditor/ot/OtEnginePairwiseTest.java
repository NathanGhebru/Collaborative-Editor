package com.collaborativeeditor.ot;

import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.engine.OtEngine;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.ot.model.OperationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OT Engine Pairwise & Edge Case Tests")
public class OtEnginePairwiseTest {

    private final OperationKey keyA = new OperationKey(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "11111111-1111-1111-1111-111111111111"
    );
    private final OperationKey keyB = new OperationKey(
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        "22222222-2222-2222-2222-222222222222"
    );

    @Nested
    @DisplayName("NO_OP Rules (ADR-001 Section 21.1)")
    class NoOpTests {
        @Test
        @DisplayName("transform(NO_OP, X) == NO_OP")
        void transformNoOpAgainstAnything() {
            Operation op = new InsertOperation(5, "abc");
            Operation res = OtEngine.transform(NoOpOperation.INSTANCE, op, keyA, keyB);
            assertInstanceOf(NoOpOperation.class, res);
        }

        @Test
        @DisplayName("transform(X, NO_OP) == X")
        void transformAnythingAgainstNoOp() {
            Operation op = new InsertOperation(5, "abc");
            Operation res = OtEngine.transform(op, NoOpOperation.INSTANCE, keyA, keyB);
            assertEquals(op, res);
        }
    }

    @Nested
    @DisplayName("Flattening Rules (ADR-001 Section 21.5)")
    class FlatteningTests {
        @Test
        @DisplayName("Empty list flattens to NO_OP")
        void emptyListFlattensToNoOp() {
            Operation res = OtEngine.flatten(List.of());
            assertInstanceOf(NoOpOperation.class, res);
        }

        @Test
        @DisplayName("List with only NO_OP flattens to NO_OP")
        void noOpListFlattensToNoOp() {
            Operation res = OtEngine.flatten(List.of(NoOpOperation.INSTANCE, NoOpOperation.INSTANCE));
            assertInstanceOf(NoOpOperation.class, res);
        }

        @Test
        @DisplayName("Single primitive in list flattens to that primitive")
        void singlePrimitiveFlattens() {
            InsertOperation ins = new InsertOperation(0, "hi");
            Operation res = OtEngine.flatten(List.of(NoOpOperation.INSTANCE, ins));
            assertEquals(ins, res);
        }

        @Test
        @DisplayName("Nested groups are flattened recursively")
        void nestedGroupsFlatten() {
            DeleteOperation d1 = new DeleteOperation(0, 2);
            DeleteOperation d2 = new DeleteOperation(5, 3);
            DeleteOperation d3 = new DeleteOperation(10, 1);

            GroupOperation innerGroup = new GroupOperation(List.of(d2, NoOpOperation.INSTANCE));
            GroupOperation outerGroup = new GroupOperation(List.of(d1, innerGroup, d3));

            Operation res = OtEngine.flatten(List.of(outerGroup));
            assertInstanceOf(GroupOperation.class, res);
            GroupOperation grp = (GroupOperation) res;
            assertEquals(3, grp.operations().size());
            assertEquals(List.of(d1, d2, d3), grp.operations());
        }
    }

    @Nested
    @DisplayName("Boundary Positions & Edge Cases")
    class BoundaryTests {
        @Test
        @DisplayName("Insert at document start and end")
        void insertAtStartAndEnd() {
            String doc = "Hello";
            InsertOperation insStart = new InsertOperation(0, "A");
            InsertOperation insEnd = new InsertOperation(5, "Z");

            Operation startPrime = OtEngine.transform(insStart, insEnd, keyA, keyB);
            Operation endPrime = OtEngine.transform(insEnd, insStart, keyB, keyA);

            assertEquals(new InsertOperation(0, "A"), startPrime);
            assertEquals(new InsertOperation(6, "Z"), endPrime);

            String doc1 = DocumentApplier.apply(DocumentApplier.apply(doc, insStart), endPrime);
            String doc2 = DocumentApplier.apply(DocumentApplier.apply(doc, insEnd), startPrime);
            assertEquals("AHelloZ", doc1);
            assertEquals("AHelloZ", doc2);
        }

        @Test
        @DisplayName("Delete entire document concurrently")
        void deleteEntireDocumentConcurrently() {
            String doc = "Hello World";
            DeleteOperation delA = new DeleteOperation(0, 11);
            DeleteOperation delB = new DeleteOperation(0, 11);

            Operation aPrime = OtEngine.transform(delA, delB, keyA, keyB);
            Operation bPrime = OtEngine.transform(delB, delA, keyB, keyA);

            assertInstanceOf(NoOpOperation.class, aPrime);
            assertInstanceOf(NoOpOperation.class, bPrime);

            String doc1 = DocumentApplier.apply(DocumentApplier.apply(doc, delA), bPrime);
            String doc2 = DocumentApplier.apply(DocumentApplier.apply(doc, delB), aPrime);
            assertEquals("", doc1);
            assertEquals("", doc2);
        }
    }
}

