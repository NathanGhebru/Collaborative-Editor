import { describe, expect, it, vi } from "vitest";
import { CollaborationClient } from "./CollaborationClient";
import type { RealtimeTicketProvider } from "./ticketApi";
import type { RealtimeTransport, RealtimeTransportHandlers } from "./transport";

const DOCUMENT_ID = "f3481704-6158-4eb9-af12-a2865d962edd";
const EPOCH = "a165202b-bac1-431e-9aee-4a6524211454";
const LOCAL_CLIENT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const REMOTE_CLIENT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

class MockTransport implements RealtimeTransport {
  handlers: RealtimeTransportHandlers | null = null;
  url: string | null = null;
  sent: string[] = [];
  closed: Array<[number | undefined, string | undefined]> = [];

  connect(url: string, handlers: RealtimeTransportHandlers): void {
    this.url = url;
    this.handlers = handlers;
  }

  send(message: string): void {
    this.sent.push(message);
  }

  close(code?: number, reason?: string): void {
    this.closed.push([code, reason]);
  }

  open(): void {
    this.handlers?.onOpen();
  }

  receive(message: unknown): void {
    this.handlers?.onMessage(typeof message === "string" ? message : JSON.stringify(message));
  }

  fail(): void {
    this.handlers?.onError();
  }

  serverClose(code: number, reason = "", wasClean = true): void {
    this.handlers?.onClose({ code, reason, wasClean });
  }
}

describe("RT-002 collaboration state machine", () => {
  it("moves through ticket, socket, hello, bootstrap, and ready states", async () => {
    let resolveTicket!: RealtimeTicketProvider["create"] extends (...args: never[]) => Promise<infer T>
      ? (ticket: T) => void
      : never;
    const ticketPromise = new Promise<Awaited<ReturnType<RealtimeTicketProvider["create"]>>>((resolve) => {
      resolveTicket = resolve;
    });
    const transport = new MockTransport();
    const client = createClient(transport, {
      ticketProvider: { create: vi.fn(() => ticketPromise) },
    });

    const start = client.start();
    expect(client.getSnapshot().status).toBe("fetching-ticket");
    resolveTicket(ticket());
    await start;
    expect(client.getSnapshot().status).toBe("connecting");
    expect(transport.url).toBe("ws://test/ws/v1/documents/document?ticket=rt_ticket");

    transport.open();
    expect(client.getSnapshot().status).toBe("awaiting-ready");
    expect(sentMessages(transport)).toEqual([
      expect.objectContaining({
        protocolVersion: 1,
        type: "client.hello",
        documentId: DOCUMENT_ID,
        syncEpoch: EPOCH,
        clientId: LOCAL_CLIENT,
        payload: { knownEpoch: EPOCH, knownRevision: 0 },
      }),
    ]);

    transport.receive(envelope("presence.snapshot", { users: [] }, false));
    expect(client.getSnapshot().status).toBe("awaiting-ready");
    transport.receive(ready(0));
    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      status: "active",
      connectionId: "connection-1",
      role: "OWNER",
    }));
  });

  it("classifies an unauthorized ticket response before opening a socket", async () => {
    const transport = new MockTransport();
    const unauthorized = Object.assign(new Error("Sign in again."), {
      status: 401,
      code: "UNAUTHENTICATED",
    });
    const client = createClient(transport, {
      ticketProvider: { create: vi.fn(async () => Promise.reject(unauthorized)) },
    });

    await client.start();

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      status: "error",
      error: expect.objectContaining({
        category: "ticket",
        code: "UNAUTHENTICATED",
        requiresAuthentication: true,
      }),
    }));
    expect(transport.url).toBeNull();
  });

  it("keeps pre-ready typing optimistic and gates transmission until ready", async () => {
    const { client, transport } = await openedClient("abc");

    const operationId = client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });
    expect(client.getSnapshot().optimisticContent).toBe("abcX");
    expect(client.getSnapshot().inFlight?.clientOperationId).toBe(operationId);
    expect(sentMessages(transport)).toHaveLength(1);

    transport.receive(ready(0));
    expect(sentMessages(transport)).toHaveLength(2);
    expect(sentMessages(transport)[1]).toEqual(expect.objectContaining({
      type: "client.operation",
      clientId: LOCAL_CLIENT,
      payload: expect.objectContaining({
        clientOperationId: operationId,
        baseRevision: 0,
        operation: { kind: "INSERT", position: 3, text: "X" },
      }),
    }));
  });

  it("rebases a pre-ready local edit over the contiguous catch-up stream", async () => {
    const { client, transport } = await openedClient("helloworld");
    client.submitLocalOperation({ kind: "INSERT", position: 5, text: "AAA" });

    transport.receive(operations({
      revision: 1,
      clientId: REMOTE_CLIENT,
      clientOperationId: "remote-1",
      operation: { kind: "INSERT", position: 5, text: "XXX" },
    }));
    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "helloXXXworld",
      optimisticContent: "helloXXXAAAworld",
    }));
    expect(client.getSnapshot().inFlight?.operation).toEqual({
      kind: "INSERT",
      position: 8,
      text: "AAA",
    });
    expect(sentMessages(transport)).toHaveLength(1);

    transport.receive(ready(1));
    expect(sentMessages(transport)[1]).toEqual(expect.objectContaining({
      type: "client.operation",
      payload: expect.objectContaining({
        baseRevision: 1,
        operation: { kind: "INSERT", position: 8, text: "AAA" },
      }),
    }));
  });

  it("allows one in-flight operation, buffers later edits, and sends the next only after canonical ACK", async () => {
    const { client, transport } = await activeClient("abc");
    const firstId = client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });
    const secondId = client.submitLocalOperation({ kind: "INSERT", position: 4, text: "Y" });

    expect(client.getSnapshot().optimisticContent).toBe("abcXY");
    expect(client.getSnapshot().inFlight?.clientOperationId).toBe(firstId);
    expect(client.getSnapshot().pendingBuffer.map((operation) => operation.clientOperationId)).toEqual([secondId]);
    expect(sentMessages(transport).filter((message) => message.type === "client.operation")).toHaveLength(1);

    transport.receive(operations({
      revision: 1,
      clientId: LOCAL_CLIENT,
      clientOperationId: firstId,
      operation: { kind: "INSERT", position: 3, text: "X" },
    }));

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "abcX",
      optimisticContent: "abcXY",
      pendingBuffer: [],
    }));
    expect(client.getSnapshot().inFlight?.clientOperationId).toBe(secondId);
    expect(sentMessages(transport).at(-1)).toEqual(expect.objectContaining({
      type: "client.operation",
      payload: expect.objectContaining({ clientOperationId: secondId, baseRevision: 1 }),
    }));
  });

  it("uses the existing OT pending rebase for an in-flight operation and sequential buffer", async () => {
    const { client, transport } = await activeClient("ABCDEF", {
      uuids: [
        "hello-message",
        "11111111-1111-1111-1111-111111111111",
        "first-message",
        "22222222-2222-2222-2222-222222222222",
      ],
    });
    client.submitLocalOperation({ kind: "INSERT", position: 1, text: "1" });
    client.submitLocalOperation({ kind: "INSERT", position: 2, text: "2" });

    transport.receive(operations({
      revision: 1,
      clientId: REMOTE_CLIENT,
      clientOperationId: "99999999-9999-9999-9999-999999999999",
      operation: { kind: "DELETE", position: 1, length: 2 },
    }));

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "ADEF",
      optimisticContent: "A12DEF",
    }));
    expect(client.getSnapshot().inFlight?.operation).toEqual({ kind: "INSERT", position: 1, text: "1" });
    expect(client.getSnapshot().pendingBuffer[0].operation).toEqual({ kind: "INSERT", position: 2, text: "2" });
  });

  it("processes same-client nonmatching history normally without acknowledging in-flight", async () => {
    const { client, transport } = await activeClient("ab");
    const localId = client.submitLocalOperation({ kind: "INSERT", position: 2, text: "X" });

    transport.receive(operations({
      revision: 1,
      clientId: LOCAL_CLIENT,
      clientOperationId: "older-operation",
      operation: { kind: "INSERT", position: 0, text: "R" },
    }));

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "Rab",
      optimisticContent: "RabX",
    }));
    expect(client.getSnapshot().inFlight?.clientOperationId).toBe(localId);
    expect(client.getSnapshot().inFlight?.operation).toEqual({ kind: "INSERT", position: 3, text: "X" });
  });

  it("deduplicates canonical revisions without dropping a matching canonical ACK rule", async () => {
    const { client, transport } = await activeClient("abc");
    transport.receive(operations({
      revision: 1,
      clientId: REMOTE_CLIENT,
      clientOperationId: "remote-1",
      operation: { kind: "INSERT", position: 3, text: "R" },
    }));
    const afterFirst = client.getSnapshot();

    transport.receive(operations({
      revision: 1,
      clientId: REMOTE_CLIENT,
      clientOperationId: "remote-1",
      operation: { kind: "INSERT", position: 3, text: "R" },
    }));
    expect(client.getSnapshot()).toEqual(afterFirst);
  });

  it("advances revision for canonical NO_OP without changing text", async () => {
    const { client, transport } = await activeClient("abc");
    transport.receive(operations({
      revision: 1,
      clientId: REMOTE_CLIENT,
      clientOperationId: "remote-noop",
      operation: { kind: "NO_OP" },
    }));

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "abc",
      optimisticContent: "abc",
      status: "active",
    }));
  });

  it("accepts a canonical NO_OP as the ACK after a local DELETE is eliminated", async () => {
    const { client, transport } = await activeClient("abc");
    const localId = client.submitLocalOperation({ kind: "DELETE", position: 0, length: 1 });
    expect(sentMessages(transport).at(-1)).toEqual(expect.objectContaining({
      type: "client.operation",
      payload: expect.objectContaining({
        clientOperationId: localId,
        operation: { kind: "DELETE", position: 0, length: 1 },
      }),
    }));

    transport.receive(operations({
      revision: 1,
      clientId: REMOTE_CLIENT,
      clientOperationId: "remote-delete",
      operation: { kind: "DELETE", position: 0, length: 1 },
    }));
    expect(client.getSnapshot().inFlight?.operation).toEqual({ kind: "NO_OP" });

    transport.receive(operations({
      revision: 2,
      clientId: LOCAL_CLIENT,
      clientOperationId: localId,
      operation: { kind: "NO_OP" },
    }));
    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 2,
      confirmedContent: "bc",
      optimisticContent: "bc",
      inFlight: null,
      status: "active",
    }));
  });

  it("stops at a canonical revision gap and exposes the resync boundary", async () => {
    const { client, transport } = await activeClient("abc");
    transport.receive(operations({
      revision: 2,
      clientId: REMOTE_CLIENT,
      clientOperationId: "remote-2",
      operation: { kind: "INSERT", position: 0, text: "R" },
    }));

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      status: "error",
      confirmedRevision: 0,
      confirmedContent: "abc",
      error: expect.objectContaining({ code: "REVISION_GAP", requiresResync: true }),
    }));
  });

  it("exposes operation rejection and resync-required without inventing a retry", async () => {
    const rejected = await activeClient("abc");
    const operationId = rejected.client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });
    rejected.transport.receive(envelope("server.operation_rejected", {
      clientOperationId: operationId,
      code: "INVALID_POSITION",
      message: "Invalid position.",
    }));
    expect(rejected.client.getSnapshot()).toEqual(expect.objectContaining({
      status: "error",
      optimisticContent: "abcX",
      error: expect.objectContaining({ category: "operation-rejected", code: "INVALID_POSITION" }),
    }));

    const resync = await activeClient("abc");
    resync.transport.receive(envelope("server.resync_required", { reason: "EPOCH_MISMATCH" }));
    expect(resync.client.getSnapshot()).toEqual(expect.objectContaining({
      status: "error",
      error: expect.objectContaining({ code: "EPOCH_MISMATCH", requiresResync: true }),
    }));
  });

  it("keeps nonfatal server errors visible and treats fatal errors as terminal", async () => {
    const nonfatal = await activeClient("abc");
    nonfatal.transport.receive(envelope("server.error", {
      code: "INTERNAL_ERROR",
      message: "Try later.",
      fatal: false,
    }, false));
    expect(nonfatal.client.getSnapshot()).toEqual(expect.objectContaining({
      status: "active",
      error: expect.objectContaining({ code: "INTERNAL_ERROR", reconnectable: true }),
    }));

    const fatal = await activeClient("abc");
    fatal.transport.receive(envelope("server.error", {
      code: "DOCUMENT_FORBIDDEN",
      message: "Permission revoked.",
      fatal: true,
      closeCode: 4001,
    }, false));
    expect(fatal.client.getSnapshot()).toEqual(expect.objectContaining({
      status: "error",
      error: expect.objectContaining({ requiresAuthentication: true }),
    }));
    fatal.transport.serverClose(4001, "permission revoked");
    expect(fatal.client.getSnapshot().error).toEqual(expect.objectContaining({
      category: "server",
      code: "DOCUMENT_FORBIDDEN",
    }));
  });

  it("classifies authoritative close codes without automatically reconnecting", async () => {
    const expected = await activeClient("abc");
    expected.transport.serverClose(1000, "normal");
    expect(expected.client.getSnapshot().status).toBe("closed");

    for (const code of [1001, 1006, 1008, 1011]) {
      const retryable = await activeClient("abc");
      retryable.transport.serverClose(code, "retryable close");
      expect(retryable.client.getSnapshot().error).toEqual(expect.objectContaining({
        code: `WEBSOCKET_CLOSED_${code}`,
        reconnectable: true,
      }));
    }

    for (const code of [1002, 1003, 1009, 4000, 4002, 4003, 4004]) {
      const nonRetryable = await activeClient("abc");
      nonRetryable.transport.serverClose(code, "terminal close");
      expect(nonRetryable.client.getSnapshot().error).toEqual(expect.objectContaining({
        code: `WEBSOCKET_CLOSED_${code}`,
        reconnectable: false,
        requiresAuthentication: false,
      }));
    }

    const unauthorized = await activeClient("abc");
    unauthorized.transport.serverClose(4001, "permission revoked");
    expect(unauthorized.client.getSnapshot().error).toEqual(expect.objectContaining({
      reconnectable: false,
      requiresAuthentication: true,
    }));

  });

  it("decomposes rebased local GROUP into sequential primitive operations", async () => {
    const { client, transport } = await activeClient("hello world", { uuids: ["op-split-2"] });
    // First edit becomes inFlight
    client.submitLocalOperation({ kind: "INSERT", position: 0, text: "A" });
    // Second edit goes into pendingBuffer (unsent)
    client.submitLocalOperation({ kind: "DELETE", position: 2, length: 7 }); // "llo wor"

    // Remote insert arrives, rebasing both inFlight and pendingBuffer
    transport.receive(operations({
      revision: 1,
      clientId: "remote-client",
      clientOperationId: "op-remote-1",
      operation: { kind: "INSERT", position: 5, text: "X" },
    }));

    // Server acknowledges first in-flight edit "A"
    transport.receive(operations({
      revision: 2,
      clientId: LOCAL_CLIENT,
      clientOperationId: (sentMessages(transport)
        .filter((message) => message.type === "client.operation")[0].payload as Record<string, unknown>)
        .clientOperationId as string,
      operation: { kind: "INSERT", position: 0, text: "A" },
    }));

    // Now buffered delete (which became GROUP([DELETE(3, 3), DELETE(7, 4)])) is promoted and decomposed!
    const ops = sentMessages(transport).filter((m) => m.type === "client.operation");
    expect(ops.length).toBe(2);
    expect(ops[1].payload).toEqual(expect.objectContaining({
      baseRevision: 2,
      operation: { kind: "DELETE", position: 2, length: 4 },
    }));
  });

  it("rejects malformed server messages and closes with BAD_REQUEST", async () => {
    const { client, transport } = await activeClient("abc");
    transport.receive("not-json");

    expect(client.getSnapshot()).toEqual(expect.objectContaining({
      status: "error",
      error: expect.objectContaining({ category: "protocol", requiresResync: true }),
    }));
    expect(transport.closed).toContainEqual([4000, "Invalid server message"]);
    transport.serverClose(4000, "bad request");
    expect(client.getSnapshot().error).toEqual(expect.objectContaining({
      category: "protocol",
      code: "INVALID_SERVER_MESSAGE",
      requiresResync: true,
    }));
  });
});

function createClient(
  transport: MockTransport,
  overrides: {
    content?: string;
    revision?: number;
    ticketProvider?: RealtimeTicketProvider;
    uuids?: string[];
  } = {},
): CollaborationClient {
  const uuids = [...(overrides.uuids ?? [])];
  let generated = 0;
  return new CollaborationClient({
    documentId: DOCUMENT_ID,
    syncEpoch: EPOCH,
    revision: overrides.revision ?? 0,
    content: overrides.content ?? "abc",
    clientId: LOCAL_CLIENT,
    ticketProvider: overrides.ticketProvider ?? { create: vi.fn(async () => ticket()) },
    createTransport: () => transport,
    createUuid: () => uuids.shift() ?? `generated-${++generated}`,
    now: () => "2026-08-30T12:15:00.000Z",
    buildWebSocketUrl: (_path, token) => `ws://test/ws/v1/documents/document?ticket=${token}`,
  });
}

async function openedClient(
  content: string,
  overrides: { uuids?: string[] } = {},
): Promise<{ client: CollaborationClient; transport: MockTransport }> {
  const transport = new MockTransport();
  const client = createClient(transport, { content, ...overrides });
  await client.start();
  transport.open();
  return { client, transport };
}

async function activeClient(
  content: string,
  overrides: { uuids?: string[] } = {},
): Promise<{ client: CollaborationClient; transport: MockTransport }> {
  const result = await openedClient(content, overrides);
  result.transport.receive(ready(0));
  return result;
}

function ticket() {
  return {
    ticket: "rt_ticket",
    expiresAt: "2026-08-30T12:16:00Z",
    websocketPath: `/ws/v1/documents/${DOCUMENT_ID}`,
  };
}

function envelope(type: string, payload: Record<string, unknown>, includeEpoch = true) {
  return {
    protocolVersion: 1,
    type,
    messageId: `message-${type}`,
    documentId: DOCUMENT_ID,
    ...(includeEpoch ? { syncEpoch: EPOCH } : {}),
    timestamp: "2026-08-30T12:15:00.000Z",
    payload,
  };
}

function ready(revision: number) {
  return envelope("server.ready", {
    connectionId: "connection-1",
    revision,
    role: "OWNER",
  });
}

function operations(item: {
  revision: number;
  clientId: string;
  clientOperationId: string;
  operation: Record<string, unknown>;
}) {
  return envelope("server.operations", {
    operations: [{ ...item, actorUserId: "actor-1" }],
  });
}

function sentMessages(transport: MockTransport): Array<Record<string, unknown>> {
  return transport.sent.map((message) => JSON.parse(message) as Record<string, unknown>);
}
