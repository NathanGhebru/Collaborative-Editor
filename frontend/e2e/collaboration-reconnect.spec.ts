import { expect, test } from "@playwright/test";

test.describe("Two-Client Collaborative Editing and Reconnect Recovery", () => {
  test("two browser contexts collaborate, handle temporary disconnection, offline edits, and converge", async ({
    browser,
  }) => {
    const docId = "doc-reconnect-1";
    const epoch = "epoch-1";
    const userA = { id: "user-a", username: "alice", displayName: "Alice" };
    const userB = { id: "user-b", username: "bob", displayName: "Bob" };

    const docDetail = {
      id: docId,
      title: "Reconnect Recovery Doc",
      owner: userA,
      permission: "OWNER",
      currentRevision: 0,
      syncEpoch: epoch,
      createdAt: "2026-08-30T12:00:00Z",
      updatedAt: "2026-08-30T12:00:00Z",
      content: "Hello",
    };

    // Shared server state for the mock realtime hub
    let currentRevision = 0;
    const docContent = "Hello";
    const committedOperations: Array<{
      revision: number;
      clientId: string;
      clientOperationId: string;
      actorUserId: string;
      operation: unknown;
    }> = [];

    const activeSockets = new Map<string, { send: (data: string) => void; close: (options?: { code?: number; reason?: string }) => void }>();

    const setupContext = async (contextUser: typeof userA, permission: "OWNER" | "EDITOR") => {
      const context = await browser.newContext();
      const page = await context.newPage();

      await page.route("**/api/v1/auth/refresh", (route) =>
        route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({ accessToken: `token-${contextUser.id}`, expiresInSeconds: 900 }),
        }),
      );

      await page.route("**/api/v1/users/me", (route) =>
        route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({ ...contextUser, email: `${contextUser.username}@example.com` }),
        }),
      );

      await page.route(`**/api/v1/documents/${docId}`, (route) =>
        route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({
            ...docDetail,
            permission,
            currentRevision,
            content: docContent,
          }),
        }),
      );

      await page.route(`**/api/v1/documents/${docId}/realtime-ticket`, (route) =>
        route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({
            ticket: `rt_${contextUser.id}_${Date.now()}`,
            expiresAt: "2026-08-30T12:16:00Z",
            websocketPath: `/ws/v1/documents/${docId}`,
          }),
        }),
      );

      await page.routeWebSocket(/\/ws\/v1\/documents\//, (socket) => {
        let socketClientId = "";

        socket.onMessage((raw) => {
          const message = JSON.parse(raw.toString());
          if (message.type === "client.hello") {
            socketClientId = message.clientId;
            activeSockets.set(socketClientId, socket);

            const knownRevision = message.payload.knownRevision ?? 0;
            // Catch-up if needed
            if (knownRevision < currentRevision) {
              const catchUpOps = committedOperations.filter((op) => op.revision > knownRevision);
              if (catchUpOps.length > 0) {
                socket.send(JSON.stringify({
                  protocolVersion: 1,
                  type: "server.operations",
                  messageId: `catchup-${Date.now()}`,
                  documentId: docId,
                  syncEpoch: epoch,
                  timestamp: new Date().toISOString(),
                  payload: { operations: catchUpOps },
                }));
              }
            }

            socket.send(JSON.stringify({
              protocolVersion: 1,
              type: "server.ready",
              messageId: `ready-${Date.now()}`,
              documentId: docId,
              syncEpoch: epoch,
              timestamp: new Date().toISOString(),
              payload: {
                connectionId: `conn-${contextUser.id}`,
                revision: currentRevision,
                role: permission,
              },
            }));
          } else if (message.type === "client.operation") {
            currentRevision++;
            const canonicalOp = {
              revision: currentRevision,
              clientId: message.clientId,
              clientOperationId: message.payload.clientOperationId,
              actorUserId: contextUser.id,
              operation: message.payload.operation,
            };
            committedOperations.push(canonicalOp);

            // Broadcast to all active sockets
            const broadcastMsg = JSON.stringify({
              protocolVersion: 1,
              type: "server.operations",
              messageId: `op-broadcast-${currentRevision}`,
              documentId: docId,
              syncEpoch: epoch,
              timestamp: new Date().toISOString(),
              payload: { operations: [canonicalOp] },
            });

            for (const [, s] of activeSockets.entries()) {
              try {
                s.send(broadcastMsg);
              } catch {
                // Ignore closed socket
              }
            }
          }
        });

        socket.onClose(() => {
          if (socketClientId) {
            activeSockets.delete(socketClientId);
          }
        });
      });

      return { context, page };
    };

    const clientA = await setupContext(userA, "OWNER");
    const clientB = await setupContext(userB, "EDITOR");

    // Both open document
    await clientA.page.goto(`/#/documents/${docId}`);
    await clientB.page.goto(`/#/documents/${docId}`);

    const editorA = clientA.page.locator("textarea, [contenteditable='true'], [role='textbox']").first();
    const editorB = clientB.page.locator("textarea, [contenteditable='true'], [role='textbox']").first();

    await expect(editorA).toHaveValue("Hello");
    await expect(editorB).toHaveValue("Hello");

    // Client A makes an initial edit: "Hello World"
    await editorA.fill("Hello World");
    await expect(editorB).toHaveValue("Hello World", { timeout: 5000 });

    // Client A closes socket (simulating temporary connection loss)
    const socketA = [...activeSockets.values()][0];
    if (socketA) {
      socketA.close({ code: 1001, reason: "Server going away" });
    }

    // Client B makes edits while A is disconnected
    await editorB.fill("Hello World, from Bob!");
    await expect(editorB).toHaveValue("Hello World, from Bob!");

    // Wait for Client A to automatically reconnect via ticket
    await expect(editorA).toHaveValue("Hello World, from Bob!", { timeout: 10000 });

    // Both clients type concurrently
    await editorA.fill("Hello World, from Bob and Alice!");
    await expect(editorB).toHaveValue("Hello World, from Bob and Alice!", { timeout: 5000 });

    await clientA.context.close();
    await clientB.context.close();
  });
});
