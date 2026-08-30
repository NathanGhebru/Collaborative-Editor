import { describe, expect, it } from "vitest";
import { applyOperation } from "./operations";
import { transformOperation } from "./transform";
import type { OperationKey, PrimitiveOperation } from "./types";

const keyA: OperationKey = {
  clientId: "00000000-0000-0000-0000-000000000001",
  clientOperationId: "10000000-0000-0000-0000-000000000001",
};
const keyB: OperationKey = {
  clientId: "00000000-0000-0000-0000-000000000002",
  clientOperationId: "20000000-0000-0000-0000-000000000002",
};

describe("seeded primitive OT convergence", () => {
  it("converges for 3,000 randomized ASCII, BMP, and surrogate-pair cases", () => {
    const random = mulberry32(0x0a7002);

    for (let caseNumber = 0; caseNumber < 3_000; caseNumber++) {
      const initialDocument = randomDocument(random);
      const operationA = randomPrimitive(initialDocument, random);
      const operationB = randomPrimitive(initialDocument, random);
      const transformedA = transformOperation(operationA, operationB, keyA, keyB);
      const transformedB = transformOperation(operationB, operationA, keyB, keyA);

      const afterAThenB = applyOperation(
        applyOperation(initialDocument, operationA),
        transformedB,
      );
      const afterBThenA = applyOperation(
        applyOperation(initialDocument, operationB),
        transformedA,
      );

      expect(afterAThenB, `random case ${caseNumber}`).toBe(afterBThenA);
    }
  });
});

function randomDocument(random: () => number): string {
  const tokens = ["a", "Z", "\n", "λ", "終", "😀", "🚀"];
  const length = Math.floor(random() * 18);
  let document = "";
  for (let index = 0; index < length; index++) {
    document += tokens[Math.floor(random() * tokens.length)];
  }
  return document;
}

function randomPrimitive(document: string, random: () => number): PrimitiveOperation {
  const boundaries = utf16Boundaries(document);
  if (document.length === 0 || random() < 0.55) {
    const insertTokens = ["x", "界", "😀", "e\u0301", "\n"];
    return {
      kind: "INSERT",
      position: boundaries[Math.floor(random() * boundaries.length)],
      text: insertTokens[Math.floor(random() * insertTokens.length)],
    };
  }

  const startIndex = Math.floor(random() * (boundaries.length - 1));
  const endIndex = startIndex + 1 + Math.floor(random() * (boundaries.length - startIndex - 1));
  return {
    kind: "DELETE",
    position: boundaries[startIndex],
    length: boundaries[endIndex] - boundaries[startIndex],
  };
}

function utf16Boundaries(document: string): number[] {
  const boundaries = [0];
  for (let position = 1; position <= document.length; position++) {
    const previous = document.charCodeAt(position - 1);
    const current = document.charCodeAt(position);
    if (!(previous >= 0xd800 && previous <= 0xdbff && current >= 0xdc00 && current <= 0xdfff)) {
      boundaries.push(position);
    }
  }
  return boundaries;
}

function mulberry32(seed: number): () => number {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let value = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value;
    return ((value ^ (value >>> 14)) >>> 0) / 4_294_967_296;
  };
}
