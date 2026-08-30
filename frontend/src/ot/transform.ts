import { assertOperationShape, flattenOperations } from "./operations";
import type {
  DeleteOperation,
  IdentifiedOperation,
  InsertOperation,
  OperationKey,
  PrimitiveOperation,
  TextOperation,
} from "./types";

export class OtTransformError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "OtTransformError";
  }
}

export function compareOperationKeys(left: OperationKey, right: OperationKey): -1 | 0 | 1 {
  const leftClientId = left.clientId.toLowerCase();
  const rightClientId = right.clientId.toLowerCase();
  if (leftClientId < rightClientId) {
    return -1;
  }
  if (leftClientId > rightClientId) {
    return 1;
  }

  const leftOperationId = left.clientOperationId.toLowerCase();
  const rightOperationId = right.clientOperationId.toLowerCase();
  if (leftOperationId < rightOperationId) {
    return -1;
  }
  if (leftOperationId > rightOperationId) {
    return 1;
  }
  return 0;
}

export function transformOperation(
  operationA: TextOperation,
  operationB: TextOperation,
  keyA: OperationKey,
  keyB: OperationKey,
): TextOperation {
  assertOperationShape(operationA);
  assertOperationShape(operationB);
  return transformInternal(operationA, operationB, keyA, keyB);
}

export function transformIdentifiedOperation(
  operationA: IdentifiedOperation,
  operationB: IdentifiedOperation,
): IdentifiedOperation {
  return {
    ...operationA,
    operation: transformOperation(operationA.operation, operationB.operation, operationA, operationB),
  };
}

function transformInternal(
  operationA: TextOperation,
  operationB: TextOperation,
  keyA: OperationKey,
  keyB: OperationKey,
): TextOperation {
  if (operationA.kind === "NO_OP") {
    return operationA;
  }
  if (operationB.kind === "NO_OP") {
    return operationA;
  }

  if (operationA.kind === "GROUP") {
    let evolvingB: TextOperation = operationB;
    const transformedA: TextOperation[] = [];

    for (const primitiveA of operationA.operations) {
      const nextA = transformInternal(primitiveA, evolvingB, keyA, keyB);
      const nextB = transformInternal(evolvingB, primitiveA, keyB, keyA);
      transformedA.push(nextA);
      evolvingB = nextB;
    }

    return flattenOperations(transformedA);
  }

  if (operationB.kind === "GROUP") {
    let transformedA: TextOperation = operationA;
    for (const primitiveB of operationB.operations) {
      transformedA = transformInternal(transformedA, primitiveB, keyA, keyB);
    }
    return transformedA;
  }

  return transformPrimitive(operationA, operationB, keyA, keyB);
}

function transformPrimitive(
  operationA: PrimitiveOperation,
  operationB: PrimitiveOperation,
  keyA: OperationKey,
  keyB: OperationKey,
): TextOperation {
  if (operationA.kind === "INSERT" && operationB.kind === "INSERT") {
    return transformInsertAgainstInsert(operationA, operationB, keyA, keyB);
  }
  if (operationA.kind === "INSERT" && operationB.kind === "DELETE") {
    return transformInsertAgainstDelete(operationA, operationB);
  }
  if (operationA.kind === "DELETE" && operationB.kind === "INSERT") {
    return transformDeleteAgainstInsert(operationA, operationB);
  }
  return transformDeleteAgainstDelete(
    operationA as DeleteOperation,
    operationB as DeleteOperation,
  );
}

function transformInsertAgainstInsert(
  operationA: InsertOperation,
  operationB: InsertOperation,
  keyA: OperationKey,
  keyB: OperationKey,
): InsertOperation {
  if (operationA.position < operationB.position) {
    return operationA;
  }
  if (operationA.position > operationB.position) {
    return { ...operationA, position: operationA.position + operationB.text.length };
  }

  const ordering = compareOperationKeys(keyA, keyB);
  if (ordering === 0) {
    throw new OtTransformError(
      "Distinct concurrent inserts at the same position must have distinct operation keys.",
    );
  }
  return ordering < 0
    ? operationA
    : { ...operationA, position: operationA.position + operationB.text.length };
}

function transformInsertAgainstDelete(
  operationA: InsertOperation,
  operationB: DeleteOperation,
): InsertOperation {
  const endB = operationB.position + operationB.length;
  if (operationA.position <= operationB.position) {
    return operationA;
  }
  if (operationA.position >= endB) {
    return { ...operationA, position: operationA.position - operationB.length };
  }
  return { ...operationA, position: operationB.position };
}

function transformDeleteAgainstInsert(
  operationA: DeleteOperation,
  operationB: InsertOperation,
): TextOperation {
  const endA = operationA.position + operationA.length;
  const insertLength = operationB.text.length;

  if (operationB.position <= operationA.position) {
    return { ...operationA, position: operationA.position + insertLength };
  }
  if (operationB.position >= endA) {
    return operationA;
  }

  return flattenOperations([
    {
      kind: "DELETE",
      position: operationA.position,
      length: operationB.position - operationA.position,
    },
    {
      kind: "DELETE",
      position: operationA.position + insertLength,
      length: endA - operationB.position,
    },
  ]);
}

function transformDeleteAgainstDelete(
  operationA: DeleteOperation,
  operationB: DeleteOperation,
): TextOperation {
  const endA = operationA.position + operationA.length;
  const endB = operationB.position + operationB.length;

  if (endB <= operationA.position) {
    return { ...operationA, position: operationA.position - operationB.length };
  }
  if (operationB.position >= endA) {
    return operationA;
  }

  const overlap = Math.max(
    0,
    Math.min(endA, endB) - Math.max(operationA.position, operationB.position),
  );
  const newLength = operationA.length - overlap;
  if (newLength === 0) {
    return { kind: "NO_OP" };
  }

  if (operationA.position < operationB.position) {
    return {
      ...operationA,
      length: endA <= endB ? newLength : operationA.length - operationB.length,
    };
  }

  return { ...operationA, position: operationB.position, length: newLength };
}
