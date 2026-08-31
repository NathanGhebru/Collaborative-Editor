import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CollaborativeEditor } from "./CollaborativeEditor";
import type { CanonicalOperationItem } from "./protocol";
import {
  ControlledReconnectHarness,
  serverOperations,
  serverReady,
} from "../testSupport/ControlledReconnectHarness";
import {
  LOCAL_CLIENT_ID,
  PRES_DOCUMENT_ID,
  PRES_EPOCH,
  REMOTE_CLIENT_ID,
  cursorMessages,
  findCursor,
  findPresence,
  presenceChanged,
  presenceSnapshot,
  publicPresence,
  remoteCursor,
} from "../testSupport/PresenceAcceptanceFixtures";

describe("PRES-001 client presence and cursor acceptance", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("A/B/C: tracks connection-scoped snapshots and JOINED/LEFT without collapsing same-user tabs", async () => {
    const firstTab = publicPresence({
      clientId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
      connectionId: "11111111-1111-4111-8111-111111111111",
    });
    const secondTab = publicPresence({
      clientId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2",
      connectionId: "22222222-2222-4222-8222-222222222222",
    });
    const joining = publicPresence({
      userId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
      displayName: "Third Collaborator",
      clientId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3",
      connectionId: "33333333-3333-4333-8333-333333333333",
      role: "OWNER",
    });
    const { harness, transport } = await activeEditor("hello", 7, [firstTab, secondTab]);

    expect(findPresence(harness.client, firstTab.clientId)).toEqual(expect.objectContaining(firstTab));
    expect(findPresence(harness.client, secondTab.clientId)).toEqual(expect.objectContaining(secondTab));

    act(() => transport.receive(presenceChanged("JOINED", joining)));
    expect(findPresence(harness.client, joining.clientId)).toEqual(expect.objectContaining(joining));
    act(() => transport.receive(remoteCursor(joining, 7, 2, 2)));
    expect(findCursor(harness.client, joining.clientId)).toEqual(expect.objectContaining({ anchor: 2, head: 2 }));

    act(() => transport.receive(presenceChanged("LEFT", joining)));
    expect(findPresence(harness.client, joining.clientId)).toBeUndefined();
    expect(findCursor(harness.client, joining.clientId)).toBeUndefined();
    expect(harness.client.getSnapshot().confirmedRevision).toBe(7);
  });

  it("E: transmits exact UTF-16 caret, forward selection, and backward selection at current revision", async () => {
    const { transport } = await activeEditor("A😀B", 9, []);
    const editor = screen.getByLabelText<HTMLTextAreaElement>("Document text editor");

    select(editor, 3, 3);
    await vi.runOnlyPendingTimersAsync();
    select(editor, 1, 3, "forward");
    await vi.runOnlyPendingTimersAsync();
    select(editor, 1, 3, "backward");
    await vi.runOnlyPendingTimersAsync();

    expect(cursorMessages(transport).map((message) => message.payload)).toEqual([
      expect.objectContaining({ baseRevision: 9, anchor: 3, head: 3 }),
      expect.objectContaining({ baseRevision: 9, anchor: 1, head: 3 }),
      expect.objectContaining({ baseRevision: 9, anchor: 3, head: 1 }),
    ]);
  });

  it("F: maps an optimistic INSERT caret back to canonical coordinates and forward after ACK", async () => {
    const { harness, transport } = await activeEditor("hello", 4, []);
    const editor = screen.getByLabelText<HTMLTextAreaElement>("Document text editor");

    fireEvent.change(editor, { target: { value: "hello!", selectionStart: 6, selectionEnd: 6 } });
    select(editor, 6, 6);
    await vi.runOnlyPendingTimersAsync();
    expect(cursorMessages(transport).at(-1)?.payload).toEqual(expect.objectContaining({
      baseRevision: 4,
      anchor: 5,
      head: 5,
    }));

    const originPresence = publicPresence({
      userId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
      displayName: "Optimistic Author",
      clientId: LOCAL_CLIENT_ID,
      connectionId: "44444444-4444-4444-8444-444444444444",
      role: "OWNER",
    });
    const peer = await activeHarness("hello", 4, [originPresence]);
    act(() => peer.transport.receive(remoteCursor(originPresence, 4, 5, 5)));
    expect(findCursor(peer.harness.client, LOCAL_CLIENT_ID)).toEqual(expect.objectContaining({
      anchor: 5,
      head: 5,
    }));

    const pending = harness.client.getSnapshot().inFlight!;
    const acceptance = canonicalOperations(5, LOCAL_CLIENT_ID, pending.clientOperationId, {
      kind: "INSERT", position: 5, text: "!",
    });
    act(() => transport.receive(acceptance));
    act(() => peer.transport.receive(acceptance));
    expect(findCursor(peer.harness.client, LOCAL_CLIENT_ID)).toEqual(expect.objectContaining({
      anchor: 6,
      head: 6,
    }));
    select(editor, 6, 6);
    await vi.runOnlyPendingTimersAsync();
    expect(cursorMessages(transport).at(-1)?.payload).toEqual(expect.objectContaining({
      baseRevision: 5,
      anchor: 6,
      head: 6,
    }));
  });

  it.each([
    ["forward", 1, 3, 1, 5],
    ["backward", 1, 3, 5, 1],
  ] as const)("G: maps an optimistic DELETE %s selection to its canonical base", async (
    direction,
    start,
    end,
    expectedAnchor,
    expectedHead,
  ) => {
    const { transport } = await activeEditor("abcdef", 11, []);
    const editor = screen.getByLabelText<HTMLTextAreaElement>("Document text editor");

    fireEvent.change(editor, { target: { value: "abef", selectionStart: 2, selectionEnd: 2 } });
    select(editor, start, end, direction);
    await vi.runOnlyPendingTimersAsync();

    expect(cursorMessages(transport).at(-1)?.payload).toEqual(expect.objectContaining({
      baseRevision: 11,
      anchor: expectedAnchor,
      head: expectedHead,
    }));
  });

  it("H: transforms a stale remote cursor through contiguous canonical history before display", async () => {
    const { harness, transport } = await activeEditor("abc", 0, []);
    const remote = publicPresence();
    act(() => transport.receive(canonicalOperations(1, REMOTE_CLIENT_ID, "remote-op-1", {
      kind: "INSERT", position: 0, text: "X",
    })));
    act(() => transport.receive(remoteCursor(remote, 0, 1, 2)));

    expect(findCursor(harness.client, remote.clientId)).toEqual(expect.objectContaining({
      anchor: 2,
      head: 3,
    }));
    expect(harness.client.getSnapshot().confirmedContent).toBe("Xabc");
  });

  it("I: never displays an old coordinate directly when retained history is bounded", async () => {
    const remote = publicPresence();
    const { harness, transport } = await activeEditor("a", 0, [remote]);
    expect(findPresence(harness.client, remote.clientId)).toBeDefined();
    const operations: CanonicalOperationItem[] = [];
    for (let revision = 1; revision <= 2048; revision += 1) {
      operations.push(canonical(revision, REMOTE_CLIENT_ID, `remote-${revision}`, {
        kind: "INSERT", position: 0, text: "x",
      }));
    }
    act(() => transport.receive(serverOperations(PRES_DOCUMENT_ID, PRES_EPOCH, operations)));
    act(() => transport.receive(remoteCursor(publicPresence(), 0, 1, 1)));

    const staleCursor = findCursor(harness.client, REMOTE_CLIENT_ID);
    expect(staleCursor === undefined
      || (staleCursor.anchor === 2049 && staleCursor.head === 2049)).toBe(true);
    expect(harness.client.getSnapshot()).toEqual(expect.objectContaining({
      confirmedRevision: 2048,
      confirmedContent: `${"x".repeat(2048)}a`,
      status: "active",
    }));
  });

  it("J: withholds a future cursor until its base revision is available or safely drops it", async () => {
    const remote = publicPresence();
    const { harness, transport } = await activeEditor("abc", 0, [remote]);
    expect(findPresence(harness.client, remote.clientId)).toBeDefined();
    act(() => transport.receive(remoteCursor(remote, 2, 3, 3)));
    expect(findCursor(harness.client, remote.clientId)).toBeUndefined();

    act(() => transport.receive(canonicalOperations(1, REMOTE_CLIENT_ID, "remote-1", {
      kind: "INSERT", position: 3, text: "X",
    })));
    act(() => transport.receive(canonicalOperations(2, REMOTE_CLIENT_ID, "remote-2", {
      kind: "INSERT", position: 4, text: "Y",
    })));
    const displayed = findCursor(harness.client, remote.clientId);
    expect(displayed === undefined || (displayed.anchor === 3 && displayed.head === 3)).toBe(true);
  });

  it("M: expires remote cursors at 30 seconds, resets activity, and removes immediately on LEFT", async () => {
    const remote = publicPresence();
    const { harness, transport } = await activeEditor("hello", 0, [remote]);
    act(() => transport.receive(remoteCursor(remote, 0, 2, 2)));
    expect(findCursor(harness.client, remote.clientId)).toBeDefined();

    await vi.advanceTimersByTimeAsync(29_999);
    expect(findCursor(harness.client, remote.clientId)).toBeDefined();
    act(() => transport.receive(remoteCursor(remote, 0, 3, 3)));
    await vi.advanceTimersByTimeAsync(29_999);
    expect(findCursor(harness.client, remote.clientId)).toEqual(expect.objectContaining({ anchor: 3 }));
    await vi.advanceTimersByTimeAsync(1);
    expect(findCursor(harness.client, remote.clientId)).toBeUndefined();

    act(() => transport.receive(remoteCursor(remote, 0, 1, 1)));
    act(() => transport.receive(presenceChanged("LEFT", remote)));
    expect(findCursor(harness.client, remote.clientId)).toBeUndefined();
  });

  it("N: throttles continuous movement to 20/sec while periodically and finally sending latest state", async () => {
    const { transport } = await activeEditor("x".repeat(100), 0, []);
    const editor = screen.getByLabelText<HTMLTextAreaElement>("Document text editor");

    for (let elapsed = 0; elapsed < 1_000; elapsed += 10) {
      select(editor, elapsed / 10, elapsed / 10);
      await vi.advanceTimersByTimeAsync(10);
    }
    const duringMovement = cursorMessages(transport);
    expect(duringMovement.length).toBeGreaterThan(1);
    expect(duringMovement.length).toBeLessThanOrEqual(20);

    await vi.advanceTimersByTimeAsync(50);
    expect(cursorMessages(transport).at(-1)?.payload).toEqual(expect.objectContaining({
      anchor: 99,
      head: 99,
    }));
  });

  it.each([
    ["insert tie", "A😀B", 3, 3, { kind: "INSERT", position: 3, text: "X" }, [3, 4]],
    ["delete before", "A😀BCD", 5, 5, { kind: "DELETE", position: 1, length: 2 }, [3]],
    ["delete containing forward selection", "A😀BCD", 3, 4, { kind: "DELETE", position: 1, length: 3 }, [1]],
    ["delete containing backward selection", "A😀BCD", 4, 3, { kind: "DELETE", position: 1, length: 3 }, [1]],
    ["delete ending at caret", "A😀BCD", 3, 3, { kind: "DELETE", position: 1, length: 2 }, [1]],
  ] as const)("O: transforms %s for caret/selection endpoints without splitting Unicode", async (
    _name,
    content,
    anchor,
    head,
    operation,
    allowedPositions,
  ) => {
    const { harness, transport } = await activeEditor(content, 0, []);
    const remote = publicPresence();
    act(() => transport.receive(remoteCursor(remote, 0, anchor, head)));
    act(() => transport.receive(canonicalOperations(1, REMOTE_CLIENT_ID, "canonical-tie", operation)));
    const cursor = findCursor(harness.client, remote.clientId);

    expect(cursor).toBeDefined();
    expect(allowedPositions).toContain(cursor?.anchor as number);
    expect(allowedPositions).toContain(cursor?.head as number);
  });
});

async function activeEditor(content: string, revision: number, presence: ReturnType<typeof publicPresence>[]) {
  const result = await activeHarness(content, revision, presence);
  render(<CollaborativeEditor client={result.harness.client} />);
  return result;
}

async function activeHarness(content: string, revision: number, presence: ReturnType<typeof publicPresence>[]) {
  const harness = new ControlledReconnectHarness({
    content,
    revision,
    documentId: PRES_DOCUMENT_ID,
    syncEpoch: PRES_EPOCH,
    clientId: LOCAL_CLIENT_ID,
  });
  await harness.client.start();
  const transport = harness.currentTransport();
  transport.open();
  transport.receive(presenceSnapshot(presence));
  transport.receive(serverReady(PRES_DOCUMENT_ID, PRES_EPOCH, revision, "local-connection"));
  return { harness, transport };
}

function select(
  editor: HTMLTextAreaElement,
  start: number,
  end: number,
  direction: "forward" | "backward" | "none" = "none",
) {
  editor.setSelectionRange(start, end, direction);
  fireEvent.select(editor);
}

function canonicalOperations(
  revision: number,
  clientId: string,
  operationId: string,
  operation: CanonicalOperationItem["operation"],
) {
  return serverOperations(PRES_DOCUMENT_ID, PRES_EPOCH, [
    canonical(revision, clientId, operationId, operation),
  ]);
}

function canonical(
  revision: number,
  clientId: string,
  operationId: string,
  operation: CanonicalOperationItem["operation"],
): CanonicalOperationItem {
  return {
    revision,
    clientId,
    clientOperationId: operationId,
    actorUserId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    operation,
  };
}
