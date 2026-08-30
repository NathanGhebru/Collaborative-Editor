import { expect, type Page } from "@playwright/test";

export async function mockRealtimeReady(page: Page): Promise<void> {
  await page.route("**/api/v1/documents/*/realtime-ticket", (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().headers().authorization).toBe("Bearer access-token");
    expect(route.request().postData()).toBeNull();
    const pathParts = new URL(route.request().url()).pathname.split("/");
    const documentId = pathParts.at(-2)!;
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        ticket: `rt_${documentId}`,
        expiresAt: "2026-08-30T12:16:00Z",
        websocketPath: `/ws/v1/documents/${documentId}`,
      }),
    });
  });

  await page.routeWebSocket(/\/ws\/v1\/documents\//, (socket) => {
    socket.onMessage((rawMessage) => {
      const message = JSON.parse(rawMessage.toString()) as {
        type: string;
        documentId: string;
        syncEpoch: string;
        payload: { knownRevision?: number };
      };
      if (message.type !== "client.hello") {
        return;
      }
      socket.send(JSON.stringify({
        protocolVersion: 1,
        type: "server.ready",
        messageId: "playwright-ready-message",
        documentId: message.documentId,
        syncEpoch: message.syncEpoch,
        timestamp: "2026-08-30T12:15:00.000Z",
        payload: {
          connectionId: "playwright-connection",
          revision: message.payload.knownRevision,
          role: "OWNER",
        },
      }));
    });
  });
}
