export interface RealtimeTransportHandlers {
  onOpen: () => void;
  onMessage: (data: unknown) => void;
  onError: () => void;
  onClose: (event: { code: number; reason: string; wasClean: boolean }) => void;
}

export interface RealtimeTransport {
  connect(url: string, handlers: RealtimeTransportHandlers): void;
  send(message: string): void;
  close(code?: number, reason?: string): void;
}

export type SocketFactory = (url: string) => WebSocket;

export class BrowserWebSocketTransport implements RealtimeTransport {
  private socket: WebSocket | null = null;

  constructor(private readonly createSocket: SocketFactory = (url) => new WebSocket(url)) {}

  connect(url: string, handlers: RealtimeTransportHandlers): void {
    if (this.socket !== null) {
      throw new Error("Realtime transport is already connected.");
    }
    const socket = this.createSocket(url);
    this.socket = socket;
    socket.addEventListener("open", handlers.onOpen);
    socket.addEventListener("message", (event) => handlers.onMessage(event.data));
    socket.addEventListener("error", handlers.onError);
    socket.addEventListener("close", (event) => handlers.onClose({
      code: event.code,
      reason: event.reason,
      wasClean: event.wasClean,
    }));
  }

  send(message: string): void {
    if (this.socket === null || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("Realtime socket is not open.");
    }
    this.socket.send(message);
  }

  close(code?: number, reason?: string): void {
    if (this.socket === null || this.socket.readyState >= WebSocket.CLOSING) {
      return;
    }
    this.socket.close(code, reason);
  }
}

export function buildRealtimeWebSocketUrl(
  websocketPath: string,
  ticket: string,
  location: Pick<Location, "protocol" | "host"> = window.location,
): string {
  const websocketProtocol = location.protocol === "https:" ? "wss:" : "ws:";
  const url = new URL(websocketPath, `${websocketProtocol}//${location.host}`);
  url.searchParams.set("ticket", ticket);
  return url.toString();
}
