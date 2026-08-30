import { describe, expect, it } from "vitest";
import canonicalFixtureJson from "../../../docs/ot-test-vectors.json";
import { applyOperation } from "./operations";
import { rebasePendingOperations } from "./rebase";
import { transformOperation } from "./transform";
import type { IdentifiedOperation, TextOperation } from "./types";

interface VectorOperation extends IdentifiedOperation {
  baseRevision: number;
}

interface PairwiseVector {
  id: string;
  description: string;
  initialDocument: string;
  opA: VectorOperation;
  opB: VectorOperation;
  expectedTransformedA: TextOperation;
  expectedTransformedB: TextOperation;
  expectedDocAfterAThenBPrime: string;
  expectedDocAfterBThenAPrime: string;
  expectedConvergedDocument: string;
}

interface PendingRebaseVector {
  id: string;
  description: string;
  initialDocument: string;
  scenario: string;
  opR: VectorOperation;
  clientInFlight: VectorOperation;
  clientBuffered: VectorOperation;
  expectedTransformedInFlight: TextOperation;
  expectedTransformedBuffered: TextOperation;
  expectedTransformedRForOptimistic: TextOperation;
  expectedFinalOptimisticDoc: string;
}

interface CanonicalFixture {
  version: number;
  vectors: Array<PairwiseVector | PendingRebaseVector>;
}

const canonicalFixture = canonicalFixtureJson as unknown as CanonicalFixture;
const pairwiseVectors = canonicalFixture.vectors.filter(isPairwiseVector);
const pendingRebaseVectors = canonicalFixture.vectors.filter(isPendingRebaseVector);

describe("canonical OT-001 / OT-002 shared vectors", () => {
  it("loads every frozen vector from the language-neutral fixture", () => {
    expect(canonicalFixture.version).toBe(1);
    expect(canonicalFixture.vectors).toHaveLength(23);
    expect(pairwiseVectors).toHaveLength(22);
    expect(pendingRebaseVectors).toHaveLength(1);
  });

  for (const vector of pairwiseVectors) {
    it(`${vector.id}: ${vector.description}`, () => {
      const transformedA = transformOperation(
        vector.opA.operation,
        vector.opB.operation,
        vector.opA,
        vector.opB,
      );
      const transformedB = transformOperation(
        vector.opB.operation,
        vector.opA.operation,
        vector.opB,
        vector.opA,
      );

      expect(transformedA).toEqual(vector.expectedTransformedA);
      expect(transformedB).toEqual(vector.expectedTransformedB);

      const afterAThenBPrime = applyOperation(
        applyOperation(vector.initialDocument, vector.opA.operation),
        transformedB,
      );
      const afterBThenAPrime = applyOperation(
        applyOperation(vector.initialDocument, vector.opB.operation),
        transformedA,
      );

      expect(afterAThenBPrime).toBe(vector.expectedDocAfterAThenBPrime);
      expect(afterBThenAPrime).toBe(vector.expectedDocAfterBThenAPrime);
      expect(afterAThenBPrime).toBe(vector.expectedConvergedDocument);
      expect(afterBThenAPrime).toBe(vector.expectedConvergedDocument);
    });
  }

  for (const vector of pendingRebaseVectors) {
    it(`${vector.id}: ${vector.description}`, () => {
      const optimisticBeforeRemote = applyOperation(
        applyOperation(vector.initialDocument, vector.clientInFlight.operation),
        vector.clientBuffered.operation,
      );
      const result = rebasePendingOperations(
        vector.opR,
        vector.clientInFlight,
        [vector.clientBuffered],
      );

      expect(result.inFlight?.operation).toEqual(vector.expectedTransformedInFlight);
      expect(result.pendingBuffer).toHaveLength(1);
      expect(result.pendingBuffer[0].operation).toEqual(vector.expectedTransformedBuffered);
      expect(result.remoteForOptimistic.operation).toEqual(
        vector.expectedTransformedRForOptimistic,
      );

      const finalOptimisticDocument = applyOperation(
        optimisticBeforeRemote,
        result.remoteForOptimistic.operation,
      );
      expect(finalOptimisticDocument).toBe(vector.expectedFinalOptimisticDoc);

      const rebuiltFromConfirmed = applyOperation(
        applyOperation(
          applyOperation(vector.initialDocument, vector.opR.operation),
          result.inFlight!.operation,
        ),
        result.pendingBuffer[0].operation,
      );
      expect(rebuiltFromConfirmed).toBe(vector.expectedFinalOptimisticDoc);
    });
  }
});

function isPairwiseVector(
  vector: PairwiseVector | PendingRebaseVector,
): vector is PairwiseVector {
  return "opA" in vector;
}

function isPendingRebaseVector(
  vector: PairwiseVector | PendingRebaseVector,
): vector is PendingRebaseVector {
  return "opR" in vector;
}
