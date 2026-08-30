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

export interface NoOpOperation {
  kind: "NO_OP";
}

export type PrimitiveOperation = InsertOperation | DeleteOperation;

export interface GroupOperation {
  kind: "GROUP";
  operations: PrimitiveOperation[];
}

export type TextOperation = PrimitiveOperation | NoOpOperation | GroupOperation;

export interface OperationKey {
  clientId: string;
  clientOperationId: string;
}

export interface IdentifiedOperation extends OperationKey {
  baseRevision?: number;
  operation: TextOperation;
}
