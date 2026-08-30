import { describe, expect, it } from "vitest";
import { applyOperation } from "./operations";
import { rebasePendingOperations } from "./rebase";
import { transformOperation } from "./transform";
import type { IdentifiedOperation, PrimitiveOperation, TextOperation } from "./types";

describe("deterministic multi-client OT simulations", () => {
  for (const clientCount of [3, 10, 50]) {
    it(`converges server and ${clientCount} optimistic clients`, () => {
      runSimulation(clientCount);
    });
  }
});

function runSimulation(clientCount: number): void {
  const initialDocument = "A😀BC終DEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  const random = mulberry32(0x0a7002 + clientCount);
  const submittedOperations = Array.from({ length: clientCount }, (_, index) => ({
    ...operationKey(index),
    baseRevision: 0,
    operation: randomPrimitive(initialDocument, random),
  }));

  const canonicalOperations: IdentifiedOperation[] = [];
  let serverDocument = initialDocument;
  for (const submitted of submittedOperations) {
    let canonicalOperation: TextOperation = submitted.operation;
    for (const earlierCanonical of canonicalOperations) {
      canonicalOperation = transformOperation(
        canonicalOperation,
        earlierCanonical.operation,
        submitted,
        earlierCanonical,
      );
    }
    const canonical = { ...submitted, operation: canonicalOperation };
    serverDocument = applyOperation(serverDocument, canonical.operation);
    canonicalOperations.push(canonical);
  }

  const clients = submittedOperations.map((submitted) => ({
    document: applyOperation(initialDocument, submitted.operation),
    inFlight: submitted as IdentifiedOperation | null,
  }));

  for (const canonical of canonicalOperations) {
    for (const client of clients) {
      if (client.inFlight?.clientOperationId === canonical.clientOperationId) {
        expect(client.inFlight.operation).toEqual(canonical.operation);
        client.inFlight = null;
      } else if (client.inFlight !== null) {
        const rebased = rebasePendingOperations(canonical, client.inFlight, []);
        client.document = applyOperation(client.document, rebased.remoteForOptimistic.operation);
        client.inFlight = rebased.inFlight;
      } else {
        client.document = applyOperation(client.document, canonical.operation);
      }
    }
  }

  for (const client of clients) {
    expect(client.inFlight).toBeNull();
    expect(client.document).toBe(serverDocument);
  }
}

function operationKey(index: number): Pick<IdentifiedOperation, "clientId" | "clientOperationId"> {
  const suffix = (index + 1).toString(16).padStart(12, "0");
  return {
    clientId: `00000000-0000-0000-0000-${suffix}`,
    clientOperationId: `10000000-0000-0000-0000-${suffix}`,
  };
}

function randomPrimitive(document: string, random: () => number): PrimitiveOperation {
  const boundaries = utf16Boundaries(document);
  if (random() < 0.55) {
    const inserts = ["x", "界", "😀", "YZ"];
    return {
      kind: "INSERT",
      position: boundaries[Math.floor(random() * boundaries.length)],
      text: inserts[Math.floor(random() * inserts.length)],
    };
  }

  const startIndex = Math.floor(random() * (boundaries.length - 1));
  const maximumSpan = Math.min(5, boundaries.length - startIndex - 1);
  const endIndex = startIndex + 1 + Math.floor(random() * maximumSpan);
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
