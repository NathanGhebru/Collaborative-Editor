import { useCallback, useSyncExternalStore } from "react";
import { PlainTextEditor } from "../editor/PlainTextEditor";
import type { TextOperation } from "../editor/types";
import type { CollaborationClient, CollaborationSnapshot } from "./CollaborationClient";

export function CollaborativeEditor({ client }: { client: CollaborationClient }) {
  const snapshot = useSyncExternalStore(
    client.subscribe,
    client.getSnapshot,
    client.getSnapshot,
  );
  const submitOperations = useCallback((operations: TextOperation[]) => {
    for (const operation of operations) {
      client.submitLocalOperation(operation);
    }
  }, [client]);
  const readOnly = snapshot.status === "closed" || snapshot.status === "error";

  return (
    <>
      {snapshot.error !== null && (
        <p className="form-error" role="alert">
          {snapshot.error.message}
        </p>
      )}
      <PlainTextEditor
        value={snapshot.optimisticContent}
        readOnly={readOnly}
        dirty={snapshot.inFlight !== null || snapshot.pendingBuffer.length > 0}
        statusLabel={collaborationStatusLabel(snapshot)}
        onOperationsExtracted={submitOperations}
      />
    </>
  );
}

export function collaborationStatusLabel(snapshot: CollaborationSnapshot): string {
  switch (snapshot.status) {
    case "disconnected":
    case "fetching-ticket":
    case "connecting":
      return "Connecting…";
    case "reconnecting":
      return "Reconnecting…";
    case "awaiting-ready":
      return "Synchronizing…";
    case "active":
      return snapshot.inFlight !== null || snapshot.pendingBuffer.length > 0
        ? "Saving…"
        : "Saved";
    case "closed":
      return "Disconnected";
    case "error":
      return "Sync error";
  }
}
