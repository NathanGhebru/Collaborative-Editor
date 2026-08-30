import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createTabClientIdProvider } from "./clientId";
import type { CanonicalOperationItem } from "./protocol";
import {
  ControlledReconnectHarness,
  type ControlledRealtimeTransport,
  serverOperations,
  serverReady,
  serverResyncRequired,
} from "../testSupport/ControlledReconnectHarness";

const LOCAL_CLIENT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const REMOTE_CLIENT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const ACTOR = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const NEW_EPOCH = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

describe("RT-003 same-epoch reconnect acceptance", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("A: reconnect after observed ACK deduplicates canonical replay", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    const operationId = harness.client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });
    first.receive(operations(harness, [canonical(1, LOCAL_CLIENT, operationId, {
      kind: "INSERT", position: 3, text: "X",
    })]));
    expect(harness.client.getSnapshot().confirmedContent).toBe("abcX");

    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);
    expect(hello(second).payload).toEqual({
      knownEpoch: harness.client.getSnapshot().syncEpoch,
      knownRevision: 1,
    });
    second.receive(operations(harness, [canonical(1, LOCAL_CLIENT, operationId, {
      kind: "INSERT", position: 3, text: "X",
    })]));
    second.receive(ready(harness, 1));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "abcX",
      optimisticContent: "abcX",
      inFlight: null,
      pendingBuffer: [],
      status: "active",
    }));
    expect(second.messages("client.operation")).toHaveLength(0);
  });

  it("B: transmitted but uncommitted operation retries exactly once with its stable identity", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    const operationId = harness.client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });
    expect(operationMessage(first).payload.clientOperationId).toBe(operationId);

    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);
    second.receive(ready(harness, 0));

    const retry = operationMessage(second);
    expect(retry.payload).toEqual(expect.objectContaining({
      clientOperationId: operationId,
      baseRevision: 0,
      operation: { kind: "INSERT", position: 3, text: "X" },
    }));
    second.receive(operations(harness, [canonical(1, LOCAL_CLIENT, operationId, retry.payload.operation)]));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "abcX",
      optimisticContent: "abcX",
      inFlight: null,
    }));
    expect(allOperationIds(harness)).toEqual([operationId, operationId]);
  });

  it("C: catch-up reveals committed-but-unobserved operation without retransmission", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    const operationId = harness.client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });

    // The durable server accepted revision 1, but its canonical frame is deliberately dropped.
    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);
    second.receive(operations(harness, [canonical(1, LOCAL_CLIENT, operationId, {
      kind: "INSERT", position: 3, text: "X",
    })]));
    second.receive(ready(harness, 1));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "abcX",
      optimisticContent: "abcX",
      inFlight: null,
      status: "active",
    }));
    expect(second.messages("client.operation")).toHaveLength(0);
    expect(allOperationIds(harness)).toEqual([operationId]);
  });

  it("D: in-flight plus buffered edits rebase over disconnected history and resume serially", async () => {
    const harness = new ControlledReconnectHarness({ content: "abcdef" });
    const first = await harness.connectInitial();
    const firstId = harness.client.submitLocalOperation({ kind: "INSERT", position: 6, text: "X" });
    const secondId = harness.client.submitLocalOperation({ kind: "INSERT", position: 7, text: "Y" });
    const thirdId = harness.client.submitLocalOperation({ kind: "INSERT", position: 8, text: "Z" });
    expect(first.messages("client.operation")).toHaveLength(1);

    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);
    second.receive(operations(harness, [
      canonical(1, REMOTE_CLIENT, "remote-1", { kind: "INSERT", position: 0, text: "R" }),
      canonical(2, REMOTE_CLIENT, "remote-2", { kind: "DELETE", position: 2, length: 2 }),
    ]));
    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 2,
      confirmedContent: "Radef",
      optimisticContent: "RadefXYZ",
    }));
    second.receive(ready(harness, 2));

    expect(second.messages("client.operation")).toHaveLength(1);
    await acknowledgeLatest(harness, second, 3, firstId);
    expect(second.messages("client.operation")).toHaveLength(2);
    await acknowledgeLatest(harness, second, 4, secondId);
    expect(second.messages("client.operation")).toHaveLength(3);
    await acknowledgeLatest(harness, second, 5, thirdId);

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 5,
      confirmedContent: "RadefXYZ",
      optimisticContent: "RadefXYZ",
      inFlight: null,
      pendingBuffer: [],
    }));
    expect(second.messages("client.operation").map((message) => message.payload.clientOperationId))
      .toEqual([firstId, secondId, thirdId]);
  });

  it("E: several remote revisions catch up contiguously and converge before ready", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);

    second.receive(operations(harness, [
      canonical(1, REMOTE_CLIENT, "remote-1", { kind: "INSERT", position: 3, text: "X" }),
      canonical(2, REMOTE_CLIENT, "remote-2", { kind: "INSERT", position: 4, text: "Y" }),
      canonical(3, REMOTE_CLIENT, "remote-3", { kind: "DELETE", position: 0, length: 1 }),
    ]));
    second.receive(ready(harness, 3));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 3,
      confirmedContent: "bcXY",
      optimisticContent: "bcXY",
      status: "active",
    }));
  });

  it("F: duplicate canonical replay after reconnect is idempotent", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    const remote = canonical(1, REMOTE_CLIENT, "remote-1", {
      kind: "INSERT", position: 0, text: "R",
    });
    first.receive(operations(harness, [remote]));
    first.disconnect();

    const second = await awaitAutomaticReconnect(harness, 2);
    second.receive(operations(harness, [remote]));
    second.receive(ready(harness, 1));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 1,
      confirmedContent: "Rabc",
      optimisticContent: "Rabc",
    }));
  });

  it("G: a reconnect revision gap stops at the resync boundary", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);

    second.receive(operations(harness, [canonical(2, REMOTE_CLIENT, "remote-2", {
      kind: "INSERT", position: 0, text: "R",
    })]));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 0,
      confirmedContent: "abc",
      status: "error",
      error: expect.objectContaining({ code: "REVISION_GAP", requiresResync: true }),
    }));
  });

  it("H: epoch mismatch preserves pending intent and delegates old-epoch recovery", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    const operationId = harness.client.submitLocalOperation({ kind: "INSERT", position: 3, text: "X" });
    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);

    second.receive(serverResyncRequired(
      harness.client.getSnapshot().documentId,
      NEW_EPOCH,
      "EPOCH_MISMATCH",
    ));

    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      syncEpoch: "a165202b-bac1-431e-9aee-4a6524211454",
      optimisticContent: "abcX",
      inFlight: expect.objectContaining({ clientOperationId: operationId }),
      status: "error",
      error: expect.objectContaining({ code: "EPOCH_MISMATCH", requiresResync: true }),
    }));
    expect(second.messages("client.operation")).toHaveLength(0);
  });

  it("I: each reconnect obtains a fresh ticket instead of reusing a consumed ticket", async () => {
    const harness = new ControlledReconnectHarness();
    const first = await harness.connectInitial();
    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);
    second.receive(ready(harness, 0));
    second.disconnect();
    const third = await awaitAutomaticReconnect(harness, 3);

    expect(harness.issuedTickets).toEqual([
      "rt_reconnect_1",
      "rt_reconnect_2",
      "rt_reconnect_3",
    ]);
    expect(harness.transports.map((transport) => new URL(transport.url!).searchParams.get("ticket")))
      .toEqual(harness.issuedTickets);
    expect(third.messages("client.hello")).toHaveLength(1);
  });

  it("J: reconnect retains tab clientId while another tab receives a distinct identity", async () => {
    const firstTab = createTabClientIdProvider(() => LOCAL_CLIENT);
    const secondTab = createTabClientIdProvider(() => REMOTE_CLIENT);
    const harness = new ControlledReconnectHarness({ clientId: firstTab.getClientId() });
    const first = await harness.connectInitial();
    first.disconnect();
    const second = await awaitAutomaticReconnect(harness, 2);
    const independentTab = new ControlledReconnectHarness({ clientId: secondTab.getClientId() });
    const independentConnection = await independentTab.connectInitial();

    expect(hello(first).clientId).toBe(LOCAL_CLIENT);
    expect(hello(second).clientId).toBe(LOCAL_CLIENT);
    expect(hello(independentConnection).clientId).toBe(REMOTE_CLIENT);
    expect(hello(independentConnection).clientId).not.toBe(LOCAL_CLIENT);
  });
});

async function awaitAutomaticReconnect(
  harness: ControlledReconnectHarness,
  expectedTransportCount: number,
): Promise<ControlledRealtimeTransport> {
  await vi.runAllTimersAsync();
  await Promise.resolve();
  expect(harness.transports).toHaveLength(expectedTransportCount);
  expect(harness.issuedTickets).toHaveLength(expectedTransportCount);
  const transport = harness.currentTransport();
  transport.open();
  expect(transport.messages("client.hello")).toHaveLength(1);
  return transport;
}

function canonical(
  revision: number,
  clientId: string,
  clientOperationId: string,
  operation: unknown,
): CanonicalOperationItem {
  return {
    revision,
    clientId,
    clientOperationId,
    actorUserId: ACTOR,
    operation: operation as CanonicalOperationItem["operation"],
  };
}

function operations(harness: ControlledReconnectHarness, items: CanonicalOperationItem[]) {
  const snapshot = harness.client.getSnapshot();
  return serverOperations(snapshot.documentId, snapshot.syncEpoch, items);
}

function ready(harness: ControlledReconnectHarness, revision: number) {
  const snapshot = harness.client.getSnapshot();
  return serverReady(snapshot.documentId, snapshot.syncEpoch, revision);
}

function hello(transport: ControlledRealtimeTransport) {
  return transport.messages("client.hello")[0];
}

function operationMessage(transport: ControlledRealtimeTransport) {
  const message = transport.messages("client.operation")[0];
  if (message === undefined) {
    throw new Error("Expected one client.operation message.");
  }
  return message;
}

function allOperationIds(harness: ControlledReconnectHarness): unknown[] {
  return harness.transports.flatMap((transport) =>
    transport.messages("client.operation").map((message) => message.payload.clientOperationId));
}

async function acknowledgeLatest(
  harness: ControlledReconnectHarness,
  transport: ControlledRealtimeTransport,
  revision: number,
  expectedOperationId: string,
): Promise<void> {
  const message = transport.messages("client.operation").at(-1);
  expect(message?.payload.clientOperationId).toBe(expectedOperationId);
  transport.receive(operations(harness, [canonical(
    revision,
    LOCAL_CLIENT,
    expectedOperationId,
    message?.payload.operation,
  )]));
  await Promise.resolve();
}
