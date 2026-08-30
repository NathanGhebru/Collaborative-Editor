package com.collaborativeeditor.ot;

import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.validation.OperationValidationException;
import com.collaborativeeditor.ot.validation.OperationValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Operation Validator Tests (UTF-16 & Bounds)")
public class OperationValidatorTest {

    @Nested
    @DisplayName("Surrogate Pair Detection")
    class SurrogatePairTests {
        @Test
        @DisplayName("Emoji with high and low surrogates is detected when bisected")
        void bisectEmoji() {
            String text = "A🚀B"; // 'A' (index 0), 🚀 is high surrogate at 1, low surrogate at 2, 'B' at 3
            assertEquals(4, text.length());

            assertFalse(OperationValidator.bisectsSurrogatePair(text, 0));
            assertFalse(OperationValidator.bisectsSurrogatePair(text, 1));
            assertTrue(OperationValidator.bisectsSurrogatePair(text, 2)); // Position 2 bisects 🚀
            assertFalse(OperationValidator.bisectsSurrogatePair(text, 3));
            assertFalse(OperationValidator.bisectsSurrogatePair(text, 4));
        }

        @Test
        @DisplayName("Detects unpaired surrogates in insert text")
        void unpairedSurrogates() {
            String loneHigh = "\uD83D";
            String loneLow = "\uDE80";
            String validSurrogate = "🚀"; // \uD83D\uDE80

            assertTrue(OperationValidator.hasUnpairedSurrogates(loneHigh));
            assertTrue(OperationValidator.hasUnpairedSurrogates(loneLow));
            assertFalse(OperationValidator.hasUnpairedSurrogates(validSurrogate));
            assertFalse(OperationValidator.hasUnpairedSurrogates("Hello World"));
        }
    }

    @Nested
    @DisplayName("Insert Validation")
    class InsertValidationTests {
        @Test
        @DisplayName("Valid insert passes")
        void validInsert() {
            assertDoesNotThrow(() -> OperationValidator.validate("Hello", new InsertOperation(5, "!")));
            assertDoesNotThrow(() -> OperationValidator.validate("Hello", new InsertOperation(0, "Say ")));
        }

        @Test
        @DisplayName("Out of bounds insert position fails")
        void outOfBoundsInsert() {
            OperationValidationException ex = assertThrows(OperationValidationException.class,
                () -> OperationValidator.validate("Hello", new InsertOperation(6, "!")));
            assertEquals("INVALID_POSITION", ex.getErrorCode());
        }

        @Test
        @DisplayName("Insert bisecting surrogate pair fails")
        void insertBisectingSurrogate() {
            OperationValidationException ex = assertThrows(OperationValidationException.class,
                () -> OperationValidator.validate("A🚀B", new InsertOperation(2, "X")));
            assertEquals("INVALID_POSITION", ex.getErrorCode());
        }

        @Test
        @DisplayName("Insert with empty text fails")
        void insertEmptyText() {
            OperationValidationException ex = assertThrows(OperationValidationException.class,
                () -> OperationValidator.validate("Hello", new InsertOperation(2, "")));
            assertEquals("INVALID_OPERATION", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Delete Validation")
    class DeleteValidationTests {
        @Test
        @DisplayName("Valid delete passes")
        void validDelete() {
            assertDoesNotThrow(() -> OperationValidator.validate("Hello", new DeleteOperation(1, 3)));
            assertDoesNotThrow(() -> OperationValidator.validate("A🚀B", new DeleteOperation(1, 2))); // Deletes whole rocket
        }

        @Test
        @DisplayName("Delete out of bounds fails")
        void outOfBoundsDelete() {
            OperationValidationException ex = assertThrows(OperationValidationException.class,
                () -> OperationValidator.validate("Hello", new DeleteOperation(3, 4)));
            assertEquals("INVALID_LENGTH", ex.getErrorCode());
        }

        @Test
        @DisplayName("Delete start bisecting surrogate fails")
        void deleteStartBisectingSurrogate() {
            OperationValidationException ex = assertThrows(OperationValidationException.class,
                () -> OperationValidator.validate("A🚀B", new DeleteOperation(2, 1)));
            assertEquals("INVALID_POSITION", ex.getErrorCode());
        }

        @Test
        @DisplayName("Delete end bisecting surrogate fails")
        void deleteEndBisectingSurrogate() {
            OperationValidationException ex = assertThrows(OperationValidationException.class,
                () -> OperationValidator.validate("A🚀B", new DeleteOperation(0, 2)));
            assertEquals("INVALID_POSITION", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Group & NoOp Validation")
    class GroupAndNoOpTests {
        @Test
        @DisplayName("NO_OP is always valid")
        void validNoOp() {
            assertDoesNotThrow(() -> OperationValidator.validate("Hello", NoOpOperation.INSTANCE));
        }

        @Test
        @DisplayName("Sequential group validation evaluates state progression")
        void validGroupSequence() {
            // Document "0123456789"
            // Op 1: Delete(2, 2) -> "01456789"
            // Op 2: Delete(4, 2) -> "014589"
            GroupOperation group = new GroupOperation(List.of(
                new DeleteOperation(2, 2),
                new DeleteOperation(4, 2)
            ));
            assertDoesNotThrow(() -> OperationValidator.validate("0123456789", group));
        }
    }
}

