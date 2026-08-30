import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CollaborationClient } from "./CollaborationClient";
import { CollaborativeEditor } from "./CollaborativeEditor";
import type { RealtimeTransport, RealtimeTransportHandlers } from "./transport";

const DOCUMENT_ID = "f3481704-6158-4eb9-af12-a2865d962edd";
const EPOCH = "a165202b-bac1-431e-9aee-4a6524211454";
const LOCAL_CLIENT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

class EditorTransport implements RealtimeTransport {
  handlers: RealtimeTransportHandlers | null = null;
  sent: string[] = [];

  connect(_url: string, handlers: RealtimeTransportHandlers): void {
    this.handlers = handlers;
  }

  send(message: string): void {
    this.sent.push(message);
  }

  close(): void {}

  open(): void {
    this.handlers?.onOpen();
  }

  receive(message: unknown): void {
    this.handlers?.onMessage(JSON.stringify(message));
  }
}

describe("realtime editor integration", () => {
  afterEach(cleanup);

  it("keeps typing optimistic while enforcing one in-flight operation and buffering", async () => {
    const { client, transport } = await activeEditorClient("abc");
    render(<CollaborativeEditor client={client} />);
    const editor = screen.getByLabelText("Document text editor");

    expect(editor).toHaveValue("abc");
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
    fireEvent.change(editor, { target: { value: "abcX", selectionStart: 4, selectionEnd: 4 } });
    fireEvent.change(editor, { target: { value: "abcXY", selectionStart: 5, selectionEnd: 5 } });

    expect(editor).toHaveValue("abcXY");
    expect(screen.getByRole("status")).toHaveTextContent("Saving…");
    expect(client.getSnapshot().pendingBuffer).toHaveLength(1);
    expect(operationMessages(transport)).toHaveLength(1);

    const first = client.getSnapshot().inFlight!;
    act(() => transport.receive(serverOperations(1, LOCAL_CLIENT, first.clientOperationId, {
      kind: "INSERT",
      position: 3,
      text: "X",
    })));

    expect(editor).toHaveValue("abcXY");
    expect(client.getSnapshot().pendingBuffer).toHaveLength(0);
    expect(operationMessages(transport)).toHaveLength(2);
    expect(operationMessages(transport)[1].payload).toEqual(expect.objectContaining({ baseRevision: 1 }));
  });

  it("renders remote rebase and clears optimistic save state on canonical own ACK", async () => {
    const { client, transport } = await activeEditorClient("ab");
    render(<CollaborativeEditor client={client} />);
    const editor = screen.getByLabelText("Document text editor");
    fireEvent.change(editor, { target: { value: "abX", selectionStart: 3, selectionEnd: 3 } });
    const localId = client.getSnapshot().inFlight!.clientOperationId;

    act(() => transport.receive(serverOperations(1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "remote-1", {
      kind: "INSERT",
      position: 0,
      text: "R",
    })));
    expect(editor).toHaveValue("RabX");
    expect(client.getSnapshot().inFlight?.operation).toEqual({ kind: "INSERT", position: 3, text: "X" });

    act(() => transport.receive(serverOperations(2, LOCAL_CLIENT, localId, {
      kind: "INSERT",
      position: 3,
      text: "X",
    })));
    expect(editor).toHaveValue("RabX");
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
    expect(client.getSnapshot().inFlight).toBeNull();
  });
});

async function activeEditorClient(content: string) {
  const transport = new EditorTransport();
  let generated = 0;
  const client = new CollaborationClient({
    documentId: DOCUMENT_ID,
    syncEpoch: EPOCH,
    revision: 0,
    content,
    clientId: LOCAL_CLIENT,
    ticketProvider: {
      create: vi.fn(async () => ({
        ticket: "rt_ticket",
        expiresAt: "2026-08-30T12:16:00Z",
        websocketPath: `/ws/v1/documents/${DOCUMENT_ID}`,
      })),
    },
    createTransport: () => transport,
    createUuid: () => `generated-${++generated}`,
    now: () => "2026-08-30T12:15:00.000Z",
    buildWebSocketUrl: (_path, ticket) => `ws://test/ws?ticket=${ticket}`,
  });
  await client.start();
  transport.open();
  transport.receive({
    protocolVersion: 1,
    type: "server.ready",
    messageId: "ready-1",
    documentId: DOCUMENT_ID,
    syncEpoch: EPOCH,
    timestamp: "2026-08-30T12:15:00.000Z",
    payload: { connectionId: "connection-1", revision: 0, role: "OWNER" },
  });
  return { client, transport };
}

function serverOperations(
  revision: number,
  clientId: string,
  clientOperationId: string,
  operation: Record<string, unknown>,
) {
  return {
    protocolVersion: 1,
    type: "server.operations",
    messageId: `server-${revision}`,
    documentId: DOCUMENT_ID,
    syncEpoch: EPOCH,
    timestamp: "2026-08-30T12:15:00.000Z",
    payload: {
      operations: [{ revision, clientId, clientOperationId, actorUserId: "actor", operation }],
    },
  };
}

function operationMessages(transport: EditorTransport) {
  return transport.sent
    .map((message) => JSON.parse(message) as { type: string; payload: Record<string, unknown> })
    .filter((message) => message.type === "client.operation");
}
