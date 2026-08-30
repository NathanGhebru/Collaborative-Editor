package com.collaborativeeditor.ot.engine;

import java.util.Objects;

import com.collaborativeeditor.ot.model.DeleteOperation;
import com.collaborativeeditor.ot.model.GroupOperation;
import com.collaborativeeditor.ot.model.InsertOperation;
import com.collaborativeeditor.ot.model.NoOpOperation;
import com.collaborativeeditor.ot.model.Operation;

/**
 * Pure document application engine.
 * Applies operations to UTF-16 strings without side effects.
 */
public final class DocumentApplier {

    private DocumentApplier() {}

    /**
     * Applies an operation to a document text string.
     *
     * @param document Current document text.
     * @param operation Operation to apply.
     * @return Updated document text.
     */
    public static String apply(String document, Operation operation) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        return switch (operation) {
            case NoOpOperation noop -> document;
            case InsertOperation insert -> {
                int pos = insert.position();
                yield document.substring(0, pos) + insert.text() + document.substring(pos);
            }
            case DeleteOperation delete -> {
                int pos = delete.position();
                int len = delete.length();
                yield document.substring(0, pos) + document.substring(pos + len);
            }
            case GroupOperation group -> {
                String current = document;
                for (Operation child : group.operations()) {
                    current = apply(current, child);
                }
                yield current;
            }
        };
    }
}

