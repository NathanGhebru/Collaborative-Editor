package com.collaborativeeditor.ot.validation;

import com.collaborativeeditor.ot.engine.DocumentApplier;
import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;

import java.util.Objects;

/**
 * Validates operations against document bounds and UTF-16 surrogate pair boundaries.
 */
public final class OperationValidator {

    private OperationValidator() {}

    /**
     * Checks whether the given position falls between a UTF-16 surrogate pair in the text.
     *
     * @param text The document text.
     * @param position The 0-indexed UTF-16 code unit offset.
     * @return true if the position bisects a surrogate pair, false otherwise.
     */
    public static boolean bisectsSurrogatePair(String text, int position) {
        if (text == null || position <= 0 || position >= text.length()) {
            return false;
        }
        char prev = text.charAt(position - 1);
        char curr = text.charAt(position);
        return Character.isHighSurrogate(prev) && Character.isLowSurrogate(curr);
    }

    /**
     * Checks whether the string contains well-formed surrogate pairs (no unpaired surrogates).
     *
     * @param text The text to check.
     * @return true if there are unpaired surrogates, false if well-formed.
     */
    public static boolean hasUnpairedSurrogates(String text) {
        if (text == null) {
            return false;
        }
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= len || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++; // Skip the low surrogate
            } else if (Character.isLowSurrogate(c)) {
                return true; // Lone low surrogate
            }
        }
        return false;
    }

    /**
     * Validates an operation against a document string.
     *
     * @param document The current document text.
     * @param operation The operation to validate.
     * @throws OperationValidationException if validation fails.
     */
    public static void validate(String document, Operation operation) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        switch (operation) {
            case InsertOperation insert -> validateInsert(document, insert);
            case DeleteOperation delete -> validateDelete(document, delete);
            case NoOpOperation noop -> {}
            case GroupOperation group -> validateGroup(document, group);
        }
    }

    private static void validateInsert(String document, InsertOperation insert) {
        if (insert.position() < 0 || insert.position() > document.length()) {
            throw new OperationValidationException("INVALID_POSITION",
                "Insert position " + insert.position() + " is out of bounds [0, " + document.length() + "]");
        }
        if (insert.text() == null || insert.text().isEmpty()) {
            throw new OperationValidationException("INVALID_OPERATION",
                "Insert text must not be null or empty");
        }
        if (bisectsSurrogatePair(document, insert.position())) {
            throw new OperationValidationException("INVALID_POSITION",
                "Insert position " + insert.position() + " bisects a UTF-16 surrogate pair");
        }
        if (hasUnpairedSurrogates(insert.text())) {
            throw new OperationValidationException("INVALID_OPERATION",
                "Insert text contains unpaired UTF-16 surrogates");
        }
    }

    private static void validateDelete(String document, DeleteOperation delete) {
        if (delete.position() < 0) {
            throw new OperationValidationException("INVALID_POSITION",
                "Delete position " + delete.position() + " cannot be negative");
        }
        if (delete.length() <= 0) {
            throw new OperationValidationException("INVALID_LENGTH",
                "Delete length " + delete.length() + " must be greater than 0");
        }
        if (delete.position() + delete.length() > document.length()) {
            throw new OperationValidationException("INVALID_LENGTH",
                "Delete range [" + delete.position() + ", " + (delete.position() + delete.length())
                    + "] exceeds document length " + document.length());
        }
        if (bisectsSurrogatePair(document, delete.position())) {
            throw new OperationValidationException("INVALID_POSITION",
                "Delete start position " + delete.position() + " bisects a UTF-16 surrogate pair");
        }
        if (bisectsSurrogatePair(document, delete.position() + delete.length())) {
            throw new OperationValidationException("INVALID_POSITION",
                "Delete end position " + (delete.position() + delete.length()) + " bisects a UTF-16 surrogate pair");
        }
    }

    private static void validateGroup(String document, GroupOperation group) {
        if (group.operations() == null || group.operations().isEmpty()) {
            throw new OperationValidationException("INVALID_OPERATION",
                "Group operation must contain at least one operation");
        }
        String currentDoc = document;
        for (Operation child : group.operations()) {
            validate(currentDoc, child);
            currentDoc = DocumentApplier.apply(currentDoc, child);
        }
    }
}

