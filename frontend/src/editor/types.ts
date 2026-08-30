import type { PrimitiveOperation } from "../ot/types";

export type { DeleteOperation, InsertOperation } from "../ot/types";

export type TextOperation = PrimitiveOperation;

export interface SelectionState {
  start: number;
  end: number;
}

export interface LocalEditorState {
  content: string;
  selection: SelectionState;
  isDirty: boolean;
  extractedOperations: TextOperation[];
  lastOperation: TextOperation | null;
  status: "idle" | "editing";
}
