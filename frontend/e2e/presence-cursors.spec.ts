import { expect, test, type Page } from "@playwright/test";
import { applyOperation } from "../src/ot/operations";
import type { PrimitiveOperation } from "../src/ot/types";

const DOCUMENT_ID = "f3481704-6158-4eb9-af12-a2865d962edd";
const EPOCH = "a165202b-bac1-431e-9aee-4a6524211454";
let serverFrameId = 0;

interface PublicUser {
  id: string;
  username: string;
  displayName: string;
}

interface ActiveConnection {
  user: PublicUser;
  clientId: string;
  connectionId: string;
  role: "OWNER" | "EDITOR";
  socket: RoutedSocket;
}

interface RoutedSocket {
  send(data: string): void;
  close(options?: { code?: number; reason?: string }): void | Promise<void>;
}

test("T: three browsers converge through presence, cursors, edits, cleanup, and reconnect", async ({ browser }) => {
  const alice = user("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "alice", "Alice");
  const bob = user("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "bob", "Bob");
  const carol = user("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "carol", "Carol");
  const active = new Map<string, ActiveConnection>();
  const observedCursorFrames: Array<{ clientId: string; baseRevision: number; anchor: number; head: number }> = [];
  const seenClientIds = new Map<string, string[]>();
  const ticketCounts = new Map<string, number>();
  let canonicalContent = "hello";
  let revision = 0;
  let releaseAliceReconnect!: () => void;
  let holdAliceReconnect = false;
  const aliceReconnectGate = new Promise<void>((resolve) => {
    releaseAliceReconnect = resolve;
  });

  const setup = async (publicUser: PublicUser, role: "OWNER" | "EDITOR") => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await mockRest(page, publicUser, role, () => canonicalContent, () => revision, ticketCounts);

    await page.routeWebSocket(/\/ws\/v1\/documents\//, (socket) => {
      let boundClientId = "";
      socket.onMessage(async (raw) => {
        const message = JSON.parse(raw.toString()) as {
          type: string;
          clientId: string;
          payload: Record<string, unknown>;
        };
        if (message.type === "client.hello") {
          boundClientId = message.clientId;
          const ids = seenClientIds.get(publicUser.id) ?? [];
          ids.push(boundClientId);
          seenClientIds.set(publicUser.id, ids);

          if (publicUser.id === alice.id && holdAliceReconnect && ids.length > 1) {
            await aliceReconnectGate;
          }

          const entry: ActiveConnection = {
            user: publicUser,
            clientId: boundClientId,
            connectionId: `${publicUser.username}-connection-${ids.length}`,
            role,
            socket,
          };
          const snapshotEntries = [...active.values()].map(publicPresenceEntry);
          socket.send(serverFrame("presence.snapshot", {
            users: snapshotEntries.map((existing) => ({ ...existing, connections: 1 })),
          }));
          active.set(boundClientId, entry);
          broadcast(serverFrame("presence.changed", {
            event: "JOINED",
            user: publicPresenceEntry(entry),
          }), boundClientId);
          socket.send(serverFrame("server.ready", {
            connectionId: entry.connectionId,
            revision,
            role,
          }));
          return;
        }

        if (message.type === "cursor.update") {
          const sender = active.get(boundClientId)!;
          const baseRevision = message.payload.baseRevision as number;
          const anchor = message.payload.anchor as number;
          const head = message.payload.head as number;
          observedCursorFrames.push({ clientId: boundClientId, baseRevision, anchor, head });
          broadcast(serverFrame("cursor.remote", {
            ...publicPresenceEntry(sender),
            baseRevision,
            anchor,
            head,
          }, baseRevision), boundClientId);
          return;
        }

        if (message.type === "client.operation") {
          const operation = message.payload.operation as PrimitiveOperation;
          canonicalContent = applyOperation(canonicalContent, operation);
          revision += 1;
          broadcast(serverFrame("server.operations", {
            operations: [{
              revision,
              clientId: boundClientId,
              clientOperationId: message.payload.clientOperationId,
              actorUserId: publicUser.id,
              operation,
            }],
          }));
        }
      });

      socket.onClose(() => {
        const departed = active.get(boundClientId);
        if (departed === undefined) {
          return;
        }
        active.delete(boundClientId);
        broadcast(serverFrame("presence.changed", {
          event: "LEFT",
          user: publicPresenceEntry(departed),
        }));
      });
    });
    return { context, page };
  };

  const broadcast = (frame: Record<string, unknown>, excludedClientId?: string) => {
    const encoded = JSON.stringify(frame);
    for (const connection of active.values()) {
      if (connection.clientId !== excludedClientId) {
        connection.socket.send(encoded);
      }
    }
  };

  const a = await setup(alice, "OWNER");
  const b = await setup(bob, "EDITOR");
  const c = await setup(carol, "EDITOR");
  await Promise.all([a.page, b.page, c.page].map((page) => page.goto(`/#/documents/${DOCUMENT_ID}`)));

  await expectPresence(a.page, ["Bob", "Carol"]);
  await expectPresence(b.page, ["Alice", "Carol"]);
  await expectPresence(c.page, ["Alice", "Bob"]);
  expect(new Set([...active.keys()]).size).toBe(3);

  const editorA = a.page.getByLabel("Document text editor");
  await editorA.evaluate((element: HTMLTextAreaElement) => {
    element.focus();
    element.setSelectionRange(1, 4, "backward");
    element.dispatchEvent(new Event("select", { bubbles: true }));
  });
  await expect.poll(() => observedCursorFrames.at(-1)).toEqual(expect.objectContaining({
    anchor: 4,
    head: 1,
    baseRevision: 0,
  }));
  const aliceClientId = seenClientIds.get(alice.id)!.at(-1)!;
  await expect(remoteCursor(b.page, aliceClientId)).toHaveCount(1);
  await expect(remoteCursor(c.page, aliceClientId)).toHaveCount(1);

  await editorA.fill("hello!");
  await expect(b.page.getByLabel("Document text editor")).toHaveValue("hello!");
  await expect(c.page.getByLabel("Document text editor")).toHaveValue("hello!");

  await c.context.close();
  await expectPresenceAbsent(a.page, "Carol");
  await expectPresenceAbsent(b.page, "Carol");
  expect(revision).toBe(1);

  holdAliceReconnect = true;
  const activeAlice = active.get(aliceClientId)!;
  await activeAlice.socket.close({ code: 1001, reason: "deterministic reconnect boundary" });
  await expectPresenceAbsent(b.page, "Alice");
  await expect.poll(() => (seenClientIds.get(alice.id) ?? []).length).toBeGreaterThan(1);
  releaseAliceReconnect();
  await expectPresence(b.page, ["Alice"]);

  const aliceIds = seenClientIds.get(alice.id)!;
  expect(new Set(aliceIds).size).toBe(1);
  expect(ticketCounts.get(alice.id)).toBeGreaterThanOrEqual(2);
  await a.page.getByLabel("Document text editor").fill("hello! reconnected");
  await expect(b.page.getByLabel("Document text editor")).toHaveValue("hello! reconnected");
  expect(canonicalContent).toBe("hello! reconnected");
  expect(revision).toBe(2);

  await a.context.close();
  await b.context.close();
});

function user(id: string, username: string, displayName: string): PublicUser {
  return { id, username, displayName };
}

function publicPresenceEntry(connection: ActiveConnection) {
  return {
    userId: connection.user.id,
    displayName: connection.user.displayName,
    clientId: connection.clientId,
    connectionId: connection.connectionId,
    role: connection.role,
  };
}

function serverFrame(type: string, payload: Record<string, unknown>, cursorRevision?: number) {
  return {
    protocolVersion: 1,
    type,
    messageId: `${type}-${++serverFrameId}`,
    documentId: DOCUMENT_ID,
    syncEpoch: EPOCH,
    timestamp: new Date().toISOString(),
    ...(cursorRevision === undefined ? {} : { revision: cursorRevision }),
    payload,
  };
}

async function mockRest(
  page: Page,
  publicUser: PublicUser,
  role: "OWNER" | "EDITOR",
  content: () => string,
  revision: () => number,
  ticketCounts: Map<string, number>,
) {
  await page.route("**/api/v1/auth/refresh", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ accessToken: `token-${publicUser.id}`, expiresInSeconds: 900 }),
  }));
  await page.route("**/api/v1/users/me", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ ...publicUser, email: `${publicUser.username}@private.example` }),
  }));
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}`, (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      id: DOCUMENT_ID,
      title: "Presence acceptance",
      content: content(),
      owner: user("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "alice", "Alice"),
      permission: role,
      currentRevision: revision(),
      syncEpoch: EPOCH,
      createdAt: "2026-08-30T12:00:00Z",
      updatedAt: "2026-08-30T12:00:00Z",
    }),
  }));
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}/permissions`, (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ owner: publicUser, permissions: [] }),
  }));
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}/realtime-ticket`, (route) => {
    const count = (ticketCounts.get(publicUser.id) ?? 0) + 1;
    ticketCounts.set(publicUser.id, count);
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        ticket: `rt_${publicUser.username}_${count}`,
        expiresAt: "2099-08-30T12:16:00Z",
        websocketPath: `/ws/v1/documents/${DOCUMENT_ID}`,
      }),
    });
  });
}

function presenceRegion(page: Page) {
  return page.locator(
    '[aria-label="Active collaborators"], [data-testid="presence-list"], [data-presence-list]',
  ).first();
}

async function expectPresence(page: Page, names: string[]) {
  const region = presenceRegion(page);
  await expect(region).toBeVisible();
  for (const name of names) {
    await expect(region).toContainText(name);
  }
}

async function expectPresenceAbsent(page: Page, name: string) {
  await expect(presenceRegion(page)).not.toContainText(name);
}

function remoteCursor(page: Page, clientId: string) {
  return page.locator(
    `[data-remote-client-id="${clientId}"][data-anchor][data-head], `
      + `[data-client-id="${clientId}"][data-remote-cursor][data-anchor][data-head]`,
  );
}
