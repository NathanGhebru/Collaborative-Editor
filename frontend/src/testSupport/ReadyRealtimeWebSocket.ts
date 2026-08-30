interface ClientHelloFrame {
  protocolVersion: 1;
  documentId: string;
  syncEpoch: string;
  payload: {
    knownRevision: number;
  };
}

export class ReadyRealtimeWebSocket extends EventTarget {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  readonly url: string;
  readyState = ReadyRealtimeWebSocket.CONNECTING;

  constructor(url: string | URL) {
    super();
    this.url = url.toString();
    queueMicrotask(() => {
      this.readyState = ReadyRealtimeWebSocket.OPEN;
      this.dispatchEvent(new Event("open"));
    });
  }

  send(data: string): void {
    const message = JSON.parse(data) as { type: string } & ClientHelloFrame;
    if (message.type !== "client.hello") {
      return;
    }

    queueMicrotask(() => this.dispatchEvent(new MessageEvent("message", {
      data: JSON.stringify({
        protocolVersion: 1,
        type: "server.ready",
        messageId: "test-ready-message",
        documentId: message.documentId,
        syncEpoch: message.syncEpoch,
        timestamp: "2026-08-30T12:15:00.000Z",
        payload: {
          connectionId: "test-connection",
          revision: message.payload.knownRevision,
          role: "OWNER",
        },
      }),
    })));
  }

  close(): void {
    this.readyState = ReadyRealtimeWebSocket.CLOSED;
    this.dispatchEvent(new CloseEvent("close", { code: 1000, wasClean: true }));
  }
}
