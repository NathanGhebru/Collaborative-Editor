export interface InsertOperation {
  kind: "INSERT";
  position: number;
  text: string;
}

export interface DeleteOperation {
  kind: "DELETE";
  position: number;
  length: number;
}

export type TextOperation = InsertOperation | DeleteOperation;

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
