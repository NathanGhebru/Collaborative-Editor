import { afterEach, describe, expect, it, vi } from "vitest";
import { BrowserWebSocketTransport, buildRealtimeWebSocketUrl } from "./transport";

class MockSocket extends EventTarget {
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  readyState = 0;
  readonly send = vi.fn();
  readonly close = vi.fn();
}

describe("browser WebSocket transport", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("builds a same-origin WebSocket URL containing only the short-lived ticket", () => {
    const url = buildRealtimeWebSocketUrl(
      "/ws/v1/documents/document-1",
      "rt_short-lived",
      { protocol: "https:", host: "editor.example" } as Location,
    );
    const parsed = new URL(url);

    expect(parsed.protocol).toBe("wss:");
    expect(parsed.pathname).toBe("/ws/v1/documents/document-1");
    expect(parsed.searchParams.get("ticket")).toBe("rt_short-lived");
    expect(url).not.toContain("access-token");
  });

  it("forwards open, text, error, close, send, and explicit close behavior", () => {
    vi.stubGlobal("WebSocket", MockSocket);
    const socket = new MockSocket();
    const transport = new BrowserWebSocketTransport(() => socket as unknown as WebSocket);
    const handlers = {
      onOpen: vi.fn(),
      onMessage: vi.fn(),
      onError: vi.fn(),
      onClose: vi.fn(),
    };
    transport.connect("ws://example/ws?ticket=rt_ticket", handlers);

    socket.readyState = MockSocket.OPEN;
    socket.dispatchEvent(new Event("open"));
    socket.dispatchEvent(new MessageEvent("message", { data: "message" }));
    socket.dispatchEvent(new Event("error"));
    socket.dispatchEvent(new CloseEvent("close", { code: 1001, reason: "restart", wasClean: true }));
    transport.send("outgoing");
    transport.close(1000, "done");

    expect(handlers.onOpen).toHaveBeenCalledOnce();
    expect(handlers.onMessage).toHaveBeenCalledWith("message");
    expect(handlers.onError).toHaveBeenCalledOnce();
    expect(handlers.onClose).toHaveBeenCalledWith({ code: 1001, reason: "restart", wasClean: true });
    expect(socket.send).toHaveBeenCalledWith("outgoing");
    expect(socket.close).toHaveBeenCalledWith(1000, "done");
  });
});
