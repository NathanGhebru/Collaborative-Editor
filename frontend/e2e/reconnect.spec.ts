import { expect, test, type Page } from "@playwright/test";

const DOCUMENT_ID = "f3481704-6158-4eb9-af12-a2865d962edd";
const SYNC_EPOCH = "a165202b-bac1-431e-9aee-4a6524211454";
const USER_ID = "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31";

test("same-tab reconnect uses a fresh ticket and retries an uncommitted edit with stable identity", async ({ page }) => {
  const ticketRequests: string[] = [];
  const socketTickets: string[] = [];
  const clientIds: string[] = [];
  const operationIds: string[] = [];
  let socketCount = 0;

  await mockAuthenticatedDocument(page);
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}/realtime-ticket`, (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().headers().authorization).toBe("Bearer access-token");
    const ticket = `rt_reconnect_browser_${ticketRequests.length + 1}`;
    ticketRequests.push(ticket);
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        ticket,
        expiresAt: "2099-08-30T12:16:00Z",
        websocketPath: `/ws/v1/documents/${DOCUMENT_ID}`,
      }),
    });
  });

  await page.routeWebSocket(/\/ws\/v1\/documents\//, (socket) => {
    socketCount += 1;
    const connectionNumber = socketCount;
    const socketTicket = new URL(socket.url()).searchParams.get("ticket");
    expect(socketTicket).not.toBeNull();
    expect(ticketRequests).toContain(socketTicket);
    socketTickets.push(socketTicket!);

    socket.onMessage(async (rawFrame) => {
      const frame = JSON.parse(rawFrame.toString()) as {
        type: string;
        clientId: string;
        payload: {
          knownRevision?: number;
          clientOperationId?: string;
          operation?: Record<string, unknown>;
        };
      };

      if (frame.type === "client.hello") {
        clientIds.push(frame.clientId);
        socket.send(JSON.stringify(serverEnvelope("server.ready", {
          connectionId: `connection-${connectionNumber}`,
          revision: frame.payload.knownRevision,
          role: "OWNER",
        })));
        return;
      }

      if (frame.type === "client.operation") {
        operationIds.push(frame.payload.clientOperationId!);
        if (connectionNumber === 1) {
          // Deterministic Scenario B: the frame was transmitted but never committed.
          await socket.close({ code: 1011, reason: "injected pre-commit disconnect" });
          return;
        }
        socket.send(JSON.stringify(serverEnvelope("server.operations", {
          operations: [{
            revision: 1,
            clientId: frame.clientId,
            clientOperationId: frame.payload.clientOperationId,
            actorUserId: USER_ID,
            operation: frame.payload.operation,
          }],
        })));
      }
    });
  });

  await page.goto(`/#/documents/${DOCUMENT_ID}`);
  const editor = page.getByLabel("Document text editor");
  await expect(editor).toHaveValue("abc");
  await expect(page.getByRole("status")).toHaveText("Saved");

  await editor.fill("abcX");
  await expect(page.getByRole("status")).toHaveText(/Reconnect/i);
  await expect.poll(() => socketTickets.length).toBe(2);
  await expect.poll(() => operationIds.length).toBe(2);
  await expect(page.getByRole("status")).toHaveText("Saved");

  expect(new Set(ticketRequests).size).toBe(ticketRequests.length);
  expect(socketTickets[1]).not.toBe(socketTickets[0]);
  expect(new Set(clientIds).size).toBe(1);
  expect(operationIds[1]).toBe(operationIds[0]);
  await expect(editor).toHaveValue("abcX");
});

test("same-tab reconnect handles committed-but-unobserved edit without creating duplicate revision", async ({ page }) => {
  const ticketRequests: string[] = [];
  const socketTickets: string[] = [];
  const clientIds: string[] = [];
  const operationIds: string[] = [];
  let socketCount = 0;
  let committedRevision = 0;

  await mockAuthenticatedDocument(page);
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}/realtime-ticket`, (route) => {
    const ticket = `rt_committed_unobserved_${ticketRequests.length + 1}`;
    ticketRequests.push(ticket);
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        ticket,
        expiresAt: "2099-08-30T12:16:00Z",
        websocketPath: `/ws/v1/documents/${DOCUMENT_ID}`,
      }),
    });
  });

  await page.routeWebSocket(/\/ws\/v1\/documents\//, (socket) => {
    socketCount += 1;
    const connectionNumber = socketCount;
    const socketTicket = new URL(socket.url()).searchParams.get("ticket");
    socketTickets.push(socketTicket!);

    socket.onMessage(async (rawFrame) => {
      const frame = JSON.parse(rawFrame.toString()) as {
        type: string;
        clientId: string;
        payload: {
          knownRevision?: number;
          clientOperationId?: string;
          operation?: Record<string, unknown>;
        };
      };

      if (frame.type === "client.hello") {
        clientIds.push(frame.clientId);
        if (connectionNumber === 2 && committedRevision === 1) {
          // Catch-up stream for committed operation X sent before server.ready
          socket.send(JSON.stringify(serverEnvelope("server.operations", {
            operations: [{
              revision: 1,
              clientId: frame.clientId,
              clientOperationId: operationIds[0],
              actorUserId: USER_ID,
              operation: { kind: "INSERT", position: 3, text: "X" },
            }],
          })));
        }
        socket.send(JSON.stringify(serverEnvelope("server.ready", {
          connectionId: `connection-${connectionNumber}`,
          revision: connectionNumber === 2 ? 1 : 0,
          role: "OWNER",
        })));
        return;
      }

      if (frame.type === "client.operation") {
        operationIds.push(frame.payload.clientOperationId!);
        if (connectionNumber === 1) {
          // Scenario C: Server commits to DB (revision 1), but socket drops before frame sent back.
          committedRevision = 1;
          await socket.close({ code: 1006, reason: "abnormal drop post-commit" });
          return;
        }

        // Connection 2: subsequent edit Y
        committedRevision = 2;
        socket.send(JSON.stringify(serverEnvelope("server.operations", {
          operations: [{
            revision: 2,
            clientId: frame.clientId,
            clientOperationId: frame.payload.clientOperationId,
            actorUserId: USER_ID,
            operation: frame.payload.operation,
          }],
        })));
      }
    });
  });

  await page.goto(`/#/documents/${DOCUMENT_ID}`);
  const editor = page.getByLabel("Document text editor");
  await expect(editor).toHaveValue("abc");

  // First edit "X"
  await editor.fill("abcX");
  await expect(page.getByRole("status")).toHaveText(/Reconnect/i);
  await expect.poll(() => socketTickets.length).toBe(2);
  await expect(page.getByRole("status")).toHaveText("Saved");

  // Subsequent edit "Y" succeeds
  await editor.fill("abcXY");
  await expect(page.getByRole("status")).toHaveText("Saved");

  // Invariants verified:
  // 1. Committed operation X (operationIds[0]) was NOT retransmitted on socket 2 after catch-up
  const socket2Operations = operationIds.slice(1);
  expect(socket2Operations).not.toContain(operationIds[0]);
  expect(committedRevision).toBe(2); // Exactly 1 revision for X, 1 for Y
  expect(new Set(clientIds).size).toBe(1); // Stable same-tab clientId
  await expect(editor).toHaveValue("abcXY");
});

async function mockAuthenticatedDocument(page: Page): Promise<void> {
  const user = {
    id: USER_ID,
    username: "reconnect-user",
    displayName: "Reconnect User",
    createdAt: "2026-08-30T12:00:00Z",
  };
  await page.route("**/api/v1/auth/refresh", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ accessToken: "access-token", expiresInSeconds: 900 }),
  }));
  await page.route("**/api/v1/users/me", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ ...user, email: "reconnect@example.com" }),
  }));
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}`, (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      id: DOCUMENT_ID,
      title: "Reconnect acceptance",
      content: "abc",
      owner: user,
      permission: "OWNER",
      currentRevision: 0,
      syncEpoch: SYNC_EPOCH,
      createdAt: "2026-08-30T12:00:00Z",
      updatedAt: "2026-08-30T12:00:00Z",
    }),
  }));
  await page.route(`**/api/v1/documents/${DOCUMENT_ID}/permissions`, (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ owner: user, permissions: [] }),
  }));
}

function serverEnvelope(type: string, payload: Record<string, unknown>) {
  return {
    protocolVersion: 1,
    type,
    messageId: `browser-${type}`,
    documentId: DOCUMENT_ID,
    syncEpoch: SYNC_EPOCH,
    timestamp: "2026-08-30T12:15:00.000Z",
    payload,
  };
}
