import { useCallback, useState } from "react";
import { extractOperations } from "./operationExtractor";
import type { LocalEditorState, SelectionState, TextOperation } from "./types";

export interface UseLocalEditorOptions {
  initialContent?: string;
  readOnly?: boolean;
}

export interface UseLocalEditorResult extends LocalEditorState {
  updateContent: (newContent: string, newSelection?: SelectionState) => void;
  updateSelection: (selection: SelectionState) => void;
  resetContent: (newInitialContent: string) => void;
}

export function useLocalEditor({
  initialContent = "",
  readOnly = false,
}: UseLocalEditorOptions = {}): UseLocalEditorResult {
  const [snapshotContent, setSnapshotContent] = useState(initialContent);
  const [content, setContent] = useState(initialContent);
  const [selection, setSelection] = useState<SelectionState>({ start: 0, end: 0 });
  const [extractedOperations, setExtractedOperations] = useState<TextOperation[]>([]);
  const [lastOperation, setLastOperation] = useState<TextOperation | null>(null);
  const [status, setStatus] = useState<"idle" | "editing">("idle");

  const isDirty = content !== snapshotContent;

  const updateContent = useCallback(
    (newContent: string, newSelection?: SelectionState) => {
      if (readOnly) {
        return;
      }
      if (newContent === content) {
        if (newSelection) {
          setSelection(newSelection);
        }
        return;
      }

      const ops = extractOperations(content, newContent);
      if (ops.length > 0) {
        setExtractedOperations((prev) => [...prev, ...ops]);
        setLastOperation(ops[ops.length - 1]);
        setStatus("editing");
      }

      setContent(newContent);
      if (newSelection) {
        setSelection(newSelection);
      }
    },
    [content, readOnly],
  );

  const updateSelection = useCallback((newSelection: SelectionState) => {
    setSelection(newSelection);
  }, []);

  const resetContent = useCallback((newInitialContent: string) => {
    setSnapshotContent(newInitialContent);
    setContent(newInitialContent);
    setSelection({ start: 0, end: 0 });
    setExtractedOperations([]);
    setLastOperation(null);
    setStatus("idle");
  }, []);

  return {
    content,
    selection,
    isDirty,
    extractedOperations,
    lastOperation,
    status,
    updateContent,
    updateSelection,
    resetContent,
  };
}
