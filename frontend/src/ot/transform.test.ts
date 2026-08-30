import { describe, expect, it } from "vitest";
import { applyOperation, flattenOperations } from "./operations";
import { OtTransformError, compareOperationKeys, transformOperation } from "./transform";
import type { OperationKey, TextOperation } from "./types";

const keyA: OperationKey = {
  clientId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  clientOperationId: "11111111-1111-1111-1111-111111111111",
};
const keyB: OperationKey = {
  clientId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  clientOperationId: "22222222-2222-2222-2222-222222222222",
};

describe("operation-key ordering", () => {
  it("compares lowercase client ID first and operation ID second", () => {
    expect(compareOperationKeys(keyA, keyB)).toBe(-1);
    expect(
      compareOperationKeys(
        { ...keyA, clientId: keyB.clientId.toUpperCase() },
        keyB,
      ),
    ).toBe(-1);
    expect(compareOperationKeys(keyA, { ...keyA })).toBe(0);
  });

  it("rejects an unresolved same-position insert tie for an identical logical key", () => {
    expect(() =>
      transformOperation(
        { kind: "INSERT", position: 0, text: "A" },
        { kind: "INSERT", position: 0, text: "B" },
        keyA,
        keyA,
      ),
    ).toThrow(OtTransformError);
  });
});

describe("GROUP transformation and flattening", () => {
  it("transforms a primitive sequentially against a GROUP", () => {
    const primitive: TextOperation = { kind: "INSERT", position: 5, text: "X" };
    const group: TextOperation = {
      kind: "GROUP",
      operations: [
        { kind: "DELETE", position: 1, length: 1 },
        { kind: "DELETE", position: 2, length: 1 },
      ],
    };

    expect(transformOperation(primitive, group, keyA, keyB)).toEqual({
      kind: "INSERT",
      position: 3,
      text: "X",
    });
    expectConvergence("abcdef", primitive, group);
  });

  it("transforms GROUP against GROUP using the progressively evolving opposing group", () => {
    const groupA: TextOperation = {
      kind: "GROUP",
      operations: [
        { kind: "DELETE", position: 1, length: 1 },
        { kind: "DELETE", position: 2, length: 1 },
      ],
    };
    const groupB: TextOperation = {
      kind: "GROUP",
      operations: [
        { kind: "INSERT", position: 0, text: "X" },
        { kind: "INSERT", position: 7, text: "Y" },
      ],
    };

    expect(transformOperation(groupA, groupB, keyA, keyB)).toEqual({
      kind: "GROUP",
      operations: [
        { kind: "DELETE", position: 2, length: 1 },
        { kind: "DELETE", position: 3, length: 1 },
      ],
    });
    expect(transformOperation(groupB, groupA, keyB, keyA)).toEqual({
      kind: "GROUP",
      operations: [
        { kind: "INSERT", position: 0, text: "X" },
        { kind: "INSERT", position: 5, text: "Y" },
      ],
    });
    expectConvergence("abcdef", groupA, groupB);
  });

  it("flattens a partially eliminated GROUP to its remaining primitive", () => {
    const group: TextOperation = {
      kind: "GROUP",
      operations: [
        { kind: "DELETE", position: 1, length: 2 },
        { kind: "DELETE", position: 1, length: 1 },
      ],
    };
    const concurrentDelete: TextOperation = { kind: "DELETE", position: 1, length: 2 };

    expect(transformOperation(group, concurrentDelete, keyA, keyB)).toEqual({
      kind: "DELETE",
      position: 1,
      length: 1,
    });
    expectConvergence("abcdef", group, concurrentDelete);
  });

  it("flattens a fully eliminated GROUP to NO_OP", () => {
    const group: TextOperation = {
      kind: "GROUP",
      operations: [
        { kind: "DELETE", position: 1, length: 1 },
        { kind: "DELETE", position: 1, length: 1 },
      ],
    };
    const coveringDelete: TextOperation = { kind: "DELETE", position: 1, length: 2 };

    expect(transformOperation(group, coveringDelete, keyA, keyB)).toEqual({ kind: "NO_OP" });
    expectConvergence("abcdef", group, coveringDelete);
  });

  it("keeps flattening deterministic when given identities", () => {
    expect(flattenOperations([])).toEqual({ kind: "NO_OP" });
  });
});

function expectConvergence(
  initialDocument: string,
  operationA: TextOperation,
  operationB: TextOperation,
): void {
  const transformedA = transformOperation(operationA, operationB, keyA, keyB);
  const transformedB = transformOperation(operationB, operationA, keyB, keyA);
  const afterAThenB = applyOperation(applyOperation(initialDocument, operationA), transformedB);
  const afterBThenA = applyOperation(applyOperation(initialDocument, operationB), transformedA);
  expect(afterAThenB).toBe(afterBThenA);
}
