import type { CollaborationClient } from "../realtime/CollaborationClient";
import type { ControlledRealtimeTransport } from "./ControlledReconnectHarness";

export const PRES_DOCUMENT_ID = "f3481704-6158-4eb9-af12-a2865d962edd";
export const PRES_EPOCH = "a165202b-bac1-431e-9aee-4a6524211454";
export const LOCAL_CLIENT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
export const REMOTE_CLIENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const REMOTE_CONNECTION_ID = "11111111-1111-4111-8111-111111111111";
export const REMOTE_USER_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

export interface PublicPresenceEntry {
  userId: string;
  displayName: string;
  clientId: string;
  connectionId: string;
  role: "OWNER" | "EDITOR";
}

export function publicPresence(
  overrides: Partial<PublicPresenceEntry> = {},
): PublicPresenceEntry {
  return {
    userId: REMOTE_USER_ID,
    displayName: "Remote Collaborator",
    clientId: REMOTE_CLIENT_ID,
    connectionId: REMOTE_CONNECTION_ID,
    role: "EDITOR",
    ...overrides,
  };
}

export function presenceSnapshot(entries: PublicPresenceEntry[]) {
  return serverEnvelope("presence.snapshot", {
    users: entries.map((entry) => ({ ...entry, connections: 1 })),
  });
}

export function presenceChanged(
  event: "JOINED" | "LEFT" | "UPDATED",
  entry: PublicPresenceEntry,
) {
  return serverEnvelope("presence.changed", { event, user: entry });
}

export function remoteCursor(
  entry: PublicPresenceEntry,
  baseRevision: number,
  anchor: number,
  head: number,
) {
  return {
    ...serverEnvelope("cursor.remote", {
      ...entry,
      baseRevision,
      anchor,
      head,
    }),
    revision: baseRevision,
  };
}

export function cursorMessages(transport: ControlledRealtimeTransport) {
  return transport.messages("cursor.update");
}

export function awarenessObjects(client: CollaborationClient): Array<Record<string, unknown>> {
  const found: Array<Record<string, unknown>> = [];
  const visited = new Set<unknown>();

  function visit(value: unknown, containingKey?: string): void {
    if (typeof value !== "object" || value === null || visited.has(value)) {
      return;
    }
    visited.add(value);
    if (value instanceof Map) {
      for (const [key, child] of value.entries()) {
        visit(child, String(key));
      }
      return;
    }
    if (Array.isArray(value)) {
      value.forEach((child) => visit(child, containingKey));
      return;
    }
    const record = value as Record<string, unknown>;
    found.push(containingKey === undefined ? record : { __key: containingKey, ...record });
    Object.entries(record).forEach(([key, child]) => visit(child, key));
  }

  visit(client.getSnapshot());
  return found;
}

export function findPresence(client: CollaborationClient, clientId: string) {
  return awarenessObjects(client).find((entry) =>
    entry.clientId === clientId || entry.__key === clientId);
}

export function findCursor(client: CollaborationClient, clientId: string) {
  return awarenessObjects(client).find((entry) =>
    (entry.clientId === clientId || entry.__key === clientId)
      && typeof entry.anchor === "number"
      && typeof entry.head === "number");
}

function serverEnvelope(type: string, payload: Record<string, unknown>) {
  return {
    protocolVersion: 1,
    type,
    messageId: `pres-${type}`,
    documentId: PRES_DOCUMENT_ID,
    syncEpoch: PRES_EPOCH,
    timestamp: "2026-08-30T12:15:00.000Z",
    payload,
  };
}
