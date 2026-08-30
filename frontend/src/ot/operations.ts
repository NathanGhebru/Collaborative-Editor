import type {
  GroupOperation,
  PrimitiveOperation,
  TextOperation,
} from "./types";
import { isUtf16Boundary } from "./utf16";

export type OtValidationCode = "INVALID_OPERATION" | "INVALID_POSITION" | "INVALID_LENGTH";

export class OtValidationError extends Error {
  constructor(
    public readonly code: OtValidationCode,
    message: string,
  ) {
    super(message);
    this.name = "OtValidationError";
  }
}

export const NO_OP = Object.freeze({ kind: "NO_OP" } as const);

export function assertOperationShape(operation: unknown): asserts operation is TextOperation {
  if (typeof operation !== "object" || operation === null) {
    throw new OtValidationError("INVALID_OPERATION", "Operation must be an object.");
  }

  const candidate = operation as Record<string, unknown>;
  switch (candidate.kind) {
    case "INSERT":
      assertPositionShape(candidate.position);
      if (typeof candidate.text !== "string" || candidate.text.length === 0) {
        throw new OtValidationError("INVALID_OPERATION", "Insert text must be non-empty.");
      }
      return;
    case "DELETE":
      assertPositionShape(candidate.position);
      if (
        typeof candidate.length !== "number"
        || !Number.isSafeInteger(candidate.length)
        || candidate.length <= 0
      ) {
        throw new OtValidationError(
          "INVALID_LENGTH",
          "Delete length must be a positive safe integer.",
        );
      }
      if (!Number.isSafeInteger((candidate.position as number) + candidate.length)) {
        throw new OtValidationError("INVALID_LENGTH", "Delete range exceeds safe integer bounds.");
      }
      return;
    case "NO_OP":
      return;
    case "GROUP":
      if (!Array.isArray(candidate.operations) || candidate.operations.length < 2) {
        throw new OtValidationError(
          "INVALID_OPERATION",
          "A canonical server operation group must contain at least two primitives.",
        );
      }
      for (const primitive of candidate.operations) {
        assertOperationShape(primitive);
        if (primitive.kind !== "INSERT" && primitive.kind !== "DELETE") {
          throw new OtValidationError(
            "INVALID_OPERATION",
            "Operation groups must contain only INSERT and DELETE primitives.",
          );
        }
      }
      return;
    default:
      throw new OtValidationError("INVALID_OPERATION", "Operation kind is not supported.");
  }
}

export function assertOperationValidForDocument(
  document: string,
  operation: TextOperation,
): void {
  applyOperation(document, operation);
}

export function applyOperation(document: string, operation: TextOperation): string {
  assertOperationShape(operation);

  switch (operation.kind) {
    case "INSERT":
      assertBoundary(document, operation.position, "Insert position");
      return document.slice(0, operation.position) + operation.text + document.slice(operation.position);
    case "DELETE": {
      const end = operation.position + operation.length;
      if (end > document.length) {
        throw new OtValidationError(
          "INVALID_LENGTH",
          "Delete range exceeds the current document length.",
        );
      }
      assertBoundary(document, operation.position, "Delete start");
      assertBoundary(document, end, "Delete end");
      return document.slice(0, operation.position) + document.slice(end);
    }
    case "NO_OP":
      return document;
    case "GROUP":
      return operation.operations.reduce(
        (currentDocument, primitive) => applyOperation(currentDocument, primitive),
        document,
      );
  }
}

export function flattenOperations(operations: readonly TextOperation[]): TextOperation {
  const primitives: PrimitiveOperation[] = [];

  for (const operation of operations) {
    appendPrimitives(primitives, operation);
  }

  if (primitives.length === 0) {
    return NO_OP;
  }
  if (primitives.length === 1) {
    return primitives[0];
  }
  return { kind: "GROUP", operations: primitives } satisfies GroupOperation;
}

function appendPrimitives(target: PrimitiveOperation[], operation: TextOperation): void {
  if (operation.kind === "NO_OP") {
    return;
  }
  if (operation.kind === "GROUP") {
    target.push(...operation.operations);
    return;
  }
  target.push(operation);
}

function assertPositionShape(position: unknown): asserts position is number {
  if (typeof position !== "number" || !Number.isSafeInteger(position) || position < 0) {
    throw new OtValidationError(
      "INVALID_POSITION",
      "Operation position must be a non-negative safe integer.",
    );
  }
}

function assertBoundary(document: string, position: number, label: string): void {
  if (position > document.length) {
    throw new OtValidationError(
      "INVALID_POSITION",
      `${label} exceeds the current document length.`,
    );
  }
  if (!isUtf16Boundary(document, position)) {
    throw new OtValidationError(
      "INVALID_POSITION",
      `${label} bisects a UTF-16 surrogate pair.`,
    );
  }
}
