import { type ChangeEvent, type SyntheticEvent, useEffect } from "react";
import { extractOperations } from "./operationExtractor";
import { useLocalEditor } from "./useLocalEditor";
import type { TextOperation } from "./types";

export interface PlainTextEditorProps {
  initialContent?: string;
  readOnly?: boolean;
  value?: string;
  dirty?: boolean;
  statusLabel?: string;
  onContentChange?: (content: string, isDirty: boolean) => void;
  onOperationExtracted?: (operation: TextOperation) => void;
  onOperationsExtracted?: (operations: TextOperation[]) => void;
}

export function formatOperation(op: TextOperation | null): string {
  if (!op) {
    return "None";
  }
  if (op.kind === "INSERT") {
    const escapedText = JSON.stringify(op.text);
    return `INSERT @ pos ${op.position}: ${escapedText}`;
  }
  return `DELETE @ pos ${op.position}: ${op.length} code unit${op.length === 1 ? "" : "s"}`;
}

export function PlainTextEditor({
  initialContent = "",
  readOnly = false,
  value,
  dirty,
  statusLabel,
  onContentChange,
  onOperationExtracted,
  onOperationsExtracted,
}: PlainTextEditorProps) {
  const {
    content: localContent,
    selection,
    isDirty: localIsDirty,
    extractedOperations,
    lastOperation,
    updateContent,
    updateSelection,
    resetContent,
  } = useLocalEditor({ initialContent, readOnly });
  const controlled = value !== undefined;
  const content = value ?? localContent;
  const isDirty = dirty ?? localIsDirty;

  // Re-sync when initialContent changes externally (e.g. document switch)
  useEffect(() => {
    if (!controlled) {
      resetContent(initialContent);
    }
  }, [controlled, initialContent, resetContent]);

  // Notify parent component if callback provided
  useEffect(() => {
    if (!controlled) {
      onContentChange?.(content, isDirty);
    }
  }, [content, controlled, isDirty, onContentChange]);

  useEffect(() => {
    if (!controlled && lastOperation) {
      onOperationExtracted?.(lastOperation);
    }
  }, [controlled, lastOperation, onOperationExtracted]);

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const newContent = event.target.value;
    const newSelection = {
      start: event.target.selectionStart ?? newContent.length,
      end: event.target.selectionEnd ?? newContent.length,
    };
    if (controlled) {
      const operations = extractOperations(content, newContent);
      if (operations.length > 0) {
        onOperationsExtracted?.(operations);
        operations.forEach((operation) => onOperationExtracted?.(operation));
      }
      updateSelection(newSelection);
      onContentChange?.(newContent, operations.length > 0 || isDirty);
      return;
    }
    updateContent(newContent, newSelection);
  }

  function handleSelectionChange(event: SyntheticEvent<HTMLTextAreaElement>) {
    const target = event.currentTarget;
    updateSelection({
      start: target.selectionStart ?? 0,
      end: target.selectionEnd ?? 0,
    });
  }

  // Calculate 1-indexed Line and Column from UTF-16 offset
  const textBeforeCursor = content.slice(0, selection.start);
  const lines = textBeforeCursor.split("\n");
  const line = lines.length;
  const col = lines[lines.length - 1].length + 1;
  const selectionLength = Math.abs(selection.end - selection.start);

  return (
    <div className="local-editor-container">
      <div className="editor-status-bar" aria-label="Editor Status">
        <span
          className={`status-badge ${isDirty ? "status-dirty" : "status-saved"}`}
          role="status"
          aria-live="polite"
        >
          {statusLabel ?? (isDirty ? "Unsaved local changes" : "Saved")}
        </span>
        {readOnly && <span className="status-badge status-readonly">Read Only</span>}
        <span className="editor-metrics" role="region" aria-label="Cursor position">
          Line {line}, Col {col} | {content.length} UTF-16 code units
          {selectionLength > 0 ? ` (${selectionLength} selected)` : ""}
        </span>
      </div>

      <div className="editor-input-wrapper">
        <label htmlFor="document-text-editor" className="sr-only">
          Document text editor
        </label>
        <textarea
          id="document-text-editor"
          aria-label="Document text editor"
          className="plain-text-editor-textarea"
          value={content}
          readOnly={readOnly}
          onChange={handleChange}
          onSelect={handleSelectionChange}
          onKeyUp={handleSelectionChange}
          onClick={handleSelectionChange}
          rows={15}
          placeholder="Type document content here..."
          spellCheck={false}
        />
      </div>

      <div className="editor-operations-bar" aria-label="Local Operation Log">
        <div className="operations-summary">
          <span>Local operations captured: <strong>{extractedOperations.length}</strong></span>
          <span className="last-operation">
            Last operation: <code>{formatOperation(lastOperation)}</code>
          </span>
        </div>
      </div>
    </div>
  );
}
