import { OtTransformError, transformIdentifiedOperation } from "./transform";
import type { IdentifiedOperation } from "./types";

export interface PendingRebaseResult {
  remoteForOptimistic: IdentifiedOperation;
  inFlight: IdentifiedOperation | null;
  pendingBuffer: IdentifiedOperation[];
}

export function rebasePendingOperations(
  remote: IdentifiedOperation,
  inFlight: IdentifiedOperation | null,
  pendingBuffer: readonly IdentifiedOperation[],
): PendingRebaseResult {
  if (inFlight === null) {
    if (pendingBuffer.length > 0) {
      throw new OtTransformError("A pending buffer requires an in-flight operation.");
    }
    return {
      remoteForOptimistic: remote,
      inFlight: null,
      pendingBuffer: [],
    };
  }

  const transformedInFlight = transformIdentifiedOperation(inFlight, remote);
  let remoteForOptimistic = transformIdentifiedOperation(remote, inFlight);
  const transformedBuffer: IdentifiedOperation[] = [];

  for (const bufferedOperation of pendingBuffer) {
    const previousRemote = remoteForOptimistic;
    transformedBuffer.push(transformIdentifiedOperation(bufferedOperation, previousRemote));
    remoteForOptimistic = transformIdentifiedOperation(previousRemote, bufferedOperation);
  }

  return {
    remoteForOptimistic,
    inFlight: transformedInFlight,
    pendingBuffer: transformedBuffer,
  };
}
