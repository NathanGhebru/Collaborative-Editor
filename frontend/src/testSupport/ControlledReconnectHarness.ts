import { CollaborationClient } from "../realtime/CollaborationClient";
import type { CanonicalOperationItem } from "../realtime/protocol";
import type { RealtimeTransport, RealtimeTransportHandlers } from "../realtime/transport";

const FIXED_NOW = "2026-08-30T12:15:00.000Z";

export interface SentRealtimeMessage {
  type: string;
  clientId?: string;
  payload: Record<string, unknown>;
}

export class ControlledRealtimeTransport implements RealtimeTransport {
  handlers: RealtimeTransportHandlers | null = null;
  url: string | null = null;
  readonly sent: string[] = [];
  readonly clientCloses: Array<{ code?: number; reason?: string }> = [];

  connect(url: string, handlers: RealtimeTransportHandlers): void {
    this.url = url;
    this.handlers = handlers;
  }

  send(message: string): void {
    this.sent.push(message);
  }

  close(code?: number, reason?: string): void {
    this.clientCloses.push({ code, reason });
  }

  open(): void {
    this.handlers?.onOpen();
  }

  receive(message: unknown): void {
    this.handlers?.onMessage(JSON.stringify(message));
  }

  disconnect(code = 1006, reason = "test transport interruption"): void {
    this.handlers?.onClose({ code, reason, wasClean: false });
  }

  messages(type?: string): SentRealtimeMessage[] {
    const parsed = this.sent.map((message) => JSON.parse(message) as SentRealtimeMessage);
    return type === undefined ? parsed : parsed.filter((message) => message.type === type);
  }
}

export interface ReconnectHarnessOptions {
  content?: string;
  revision?: number;
  documentId?: string;
  syncEpoch?: string;
  clientId?: string;
}

export class ControlledReconnectHarness {
  readonly transports: ControlledRealtimeTransport[] = [];
  readonly issuedTickets: string[] = [];
  readonly client: CollaborationClient;
  private generatedUuid = 0;

  constructor(options: ReconnectHarnessOptions = {}) {
    const documentId = options.documentId ?? "f3481704-6158-4eb9-af12-a2865d962edd";
    const syncEpoch = options.syncEpoch ?? "a165202b-bac1-431e-9aee-4a6524211454";
    const clientId = options.clientId ?? "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    this.client = new CollaborationClient({
      documentId,
      syncEpoch,
      revision: options.revision ?? 0,
      content: options.content ?? "abc",
      clientId,
      ticketProvider: {
        create: async () => {
          const ticket = `rt_reconnect_${this.issuedTickets.length + 1}`;
          this.issuedTickets.push(ticket);
          return {
            ticket,
            expiresAt: "2026-08-30T12:16:00Z",
            websocketPath: `/ws/v1/documents/${documentId}`,
          };
        },
      },
      createTransport: () => {
        const transport = new ControlledRealtimeTransport();
        this.transports.push(transport);
        return transport;
      },
      createUuid: () => this.nextUuid(),
      now: () => FIXED_NOW,
      buildWebSocketUrl: (path, ticket) => `ws://test${path}?ticket=${ticket}`,
    });
  }

  async connectInitial(role: "OWNER" | "EDITOR" = "OWNER"): Promise<ControlledRealtimeTransport> {
    await this.client.start();
    const transport = this.currentTransport();
    transport.open();
    transport.receive(serverReady(
      this.client.getSnapshot().documentId,
      this.client.getSnapshot().syncEpoch,
      this.client.getSnapshot().confirmedRevision,
      `connection-${this.transports.length}`,
      role,
    ));
    return transport;
  }

  currentTransport(): ControlledRealtimeTransport {
    const transport = this.transports.at(-1);
    if (transport === undefined) {
      throw new Error("No controlled realtime transport has been created.");
    }
    return transport;
  }

  private nextUuid(): string {
    this.generatedUuid += 1;
    return `00000000-0000-4000-8000-${this.generatedUuid.toString().padStart(12, "0")}`;
  }
}

export function serverReady(
  documentId: string,
  syncEpoch: string,
  revision: number,
  connectionId = "connection-reconnected",
  role: "OWNER" | "EDITOR" = "OWNER",
) {
  return envelope("server.ready", documentId, syncEpoch, {
    connectionId,
    revision,
    role,
  });
}

export function serverOperations(
  documentId: string,
  syncEpoch: string,
  operations: CanonicalOperationItem[],
) {
  return envelope("server.operations", documentId, syncEpoch, { operations });
}

export function serverResyncRequired(
  documentId: string,
  syncEpoch: string,
  reason: string,
) {
  return envelope("server.resync_required", documentId, syncEpoch, { reason });
}

function envelope(
  type: string,
  documentId: string,
  syncEpoch: string,
  payload: Record<string, unknown>,
) {
  return {
    protocolVersion: 1,
    type,
    messageId: `test-${type}`,
    documentId,
    syncEpoch,
    timestamp: FIXED_NOW,
    payload,
  };
}
