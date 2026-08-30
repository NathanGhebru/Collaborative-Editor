import { applyOperation, assertOperationValidForDocument } from "../ot/operations";
import { rebasePendingOperations } from "../ot/rebase";
import type { IdentifiedOperation, PrimitiveOperation, TextOperation } from "../ot/types";
import type { UuidFactory } from "./clientId";
import {
  createClientHello,
  createClientOperation,
  parseServerMessage,
  RealtimeProtocolError,
  type CanonicalOperationItem,
  type ServerErrorMessage,
  type ServerOperationRejectedMessage,
  type ServerOperationsMessage,
  type ServerReadyMessage,
  type ServerResyncRequiredMessage,
} from "./protocol";
import {
  buildRealtimeWebSocketUrl,
  type RealtimeTransport,
  type RealtimeTransportHandlers,
} from "./transport";
import type { RealtimeTicketProvider } from "./ticketApi";

export type CollaborationStatus =
  | "disconnected"
  | "fetching-ticket"
  | "connecting"
  | "awaiting-ready"
  | "active"
  | "closed"
  | "error";

export type CollaborationErrorCategory =
  | "ticket"
  | "transport"
  | "protocol"
  | "operation-rejected"
  | "resync-required"
  | "server"
  | "backpressure";

export interface CollaborationError {
  category: CollaborationErrorCategory;
  code: string;
  message: string;
  fatal: boolean;
  reconnectable: boolean;
  requiresAuthentication: boolean;
  requiresResync: boolean;
}

export interface PendingClientOperation extends IdentifiedOperation {
  baseRevision: number;
  operation: TextOperation;
  sent: boolean;
}

export interface CollaborationSnapshot {
  status: CollaborationStatus;
  documentId: string;
  syncEpoch: string;
  clientId: string;
  confirmedRevision: number;
  confirmedContent: string;
  optimisticContent: string;
  inFlight: PendingClientOperation | null;
  pendingBuffer: PendingClientOperation[];
  connectionId: string | null;
  role: "OWNER" | "EDITOR" | null;
  error: CollaborationError | null;
}

export interface CollaborationClientOptions {
  documentId: string;
  syncEpoch: string;
  revision: number;
  content: string;
  clientId: string;
  ticketProvider: RealtimeTicketProvider;
  createTransport: () => RealtimeTransport;
  createUuid?: UuidFactory;
  now?: () => string;
  buildWebSocketUrl?: (websocketPath: string, ticket: string) => string;
  maxPendingOperations?: number;
}

type Listener = () => void;

export class CollaborationClient {
  private readonly listeners = new Set<Listener>();
  private readonly createUuid: UuidFactory;
  private readonly now: () => string;
  private readonly buildWebSocketUrl: (websocketPath: string, ticket: string) => string;
  private readonly maxPendingOperations: number;
  private transport: RealtimeTransport | null = null;
  private lifecycle = 0;
  private intentionallyStopped = false;
  private snapshot: CollaborationSnapshot;

  constructor(private readonly options: CollaborationClientOptions) {
    if (!Number.isSafeInteger(options.revision) || options.revision < 0) {
      throw new Error("Initial confirmed revision must be a non-negative safe integer.");
    }
    this.createUuid = options.createUuid ?? (() => crypto.randomUUID());
    this.now = options.now ?? (() => new Date().toISOString());
    this.buildWebSocketUrl = options.buildWebSocketUrl ?? buildRealtimeWebSocketUrl;
    this.maxPendingOperations = options.maxPendingOperations ?? 100;
    this.snapshot = {
      status: "disconnected",
      documentId: options.documentId,
      syncEpoch: options.syncEpoch,
      clientId: options.clientId,
      confirmedRevision: options.revision,
      confirmedContent: options.content,
      optimisticContent: options.content,
      inFlight: null,
      pendingBuffer: [],
      connectionId: null,
      role: null,
      error: null,
    };
  }

  getSnapshot = (): CollaborationSnapshot => this.snapshot;

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  async start(): Promise<void> {
    if (this.snapshot.status !== "disconnected") {
      return;
    }

    const lifecycle = ++this.lifecycle;
    this.intentionallyStopped = false;
    this.update({ status: "fetching-ticket", error: null });

    let ticket;
    try {
      ticket = await this.options.ticketProvider.create(this.snapshot.documentId);
    } catch (error) {
      if (lifecycle === this.lifecycle) {
        this.fail({
          category: "ticket",
          code: errorCode(error, "TICKET_REQUEST_FAILED"),
          message: errorMessage(error, "Unable to create a realtime connection ticket."),
          fatal: true,
          reconnectable: false,
          requiresAuthentication: isAuthenticationFailure(error),
          requiresResync: false,
        });
      }
      return;
    }

    if (lifecycle !== this.lifecycle) {
      return;
    }
    try {
      const transport = this.options.createTransport();
      this.transport = transport;
      this.update({ status: "connecting" });
      transport.connect(
        this.buildWebSocketUrl(ticket.websocketPath, ticket.ticket),
        this.transportHandlers(lifecycle),
      );
    } catch (error) {
      if (lifecycle !== this.lifecycle) {
        return;
      }
      this.fail({
        category: "transport",
        code: errorCode(error, "WEBSOCKET_CONNECT_FAILED"),
        message: errorMessage(error, "Unable to open the realtime connection."),
        fatal: true,
        reconnectable: true,
        requiresAuthentication: false,
        requiresResync: false,
      });
    }
  }

  stop(): void {
    ++this.lifecycle;
    this.intentionallyStopped = true;
    this.transport?.close(1000, "Client stopped");
    this.transport = null;
    this.update({
      status: "disconnected",
      connectionId: null,
      role: null,
    });
  }

  submitLocalOperation(operation: PrimitiveOperation): string {
    if (this.snapshot.status === "closed" || this.snapshot.status === "error") {
      throw new Error("Local operations are paused because realtime collaboration is unavailable.");
    }
    const pendingCount = (this.snapshot.inFlight === null ? 0 : 1)
      + this.snapshot.pendingBuffer.length;
    if (pendingCount >= this.maxPendingOperations) {
      const error: CollaborationError = {
        category: "backpressure",
        code: "PENDING_LIMIT_REACHED",
        message: "Local editing is paused because too many operations are awaiting confirmation.",
        fatal: false,
        reconnectable: false,
        requiresAuthentication: false,
        requiresResync: false,
      };
      this.fail(error);
      throw new Error(error.message);
    }

    assertOperationValidForDocument(this.snapshot.optimisticContent, operation);
    const pending: PendingClientOperation = {
      clientId: this.snapshot.clientId,
      clientOperationId: this.createUuid(),
      baseRevision: this.snapshot.confirmedRevision,
      operation,
      sent: false,
    };
    const optimisticContent = applyOperation(this.snapshot.optimisticContent, operation);

    if (this.snapshot.inFlight === null) {
      this.update({ inFlight: pending, optimisticContent });
    } else {
      this.update({
        pendingBuffer: [...this.snapshot.pendingBuffer, pending],
        optimisticContent,
      });
    }
    this.flushPendingOperation();
    return pending.clientOperationId;
  }

  private transportHandlers(lifecycle: number): RealtimeTransportHandlers {
    return {
      onOpen: () => {
        if (lifecycle !== this.lifecycle) {
          return;
        }
        this.update({ status: "awaiting-ready" });
        this.sendHello();
      },
      onMessage: (data) => {
        if (lifecycle !== this.lifecycle) {
          return;
        }
        this.receive(data);
      },
      onError: () => {
        if (lifecycle !== this.lifecycle || this.snapshot.status === "error") {
          return;
        }
        this.fail({
          category: "transport",
          code: "WEBSOCKET_ERROR",
          message: "The realtime connection encountered a transport error.",
          fatal: true,
          reconnectable: true,
          requiresAuthentication: false,
          requiresResync: false,
        });
      },
      onClose: (event) => {
        if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
          return;
        }
        this.handleClose(event.code, event.reason, event.wasClean);
      },
    };
  }

  private sendHello(): void {
    this.sendMessage(createClientHello({
      messageId: this.createUuid(),
      documentId: this.snapshot.documentId,
      syncEpoch: this.snapshot.syncEpoch,
      clientId: this.snapshot.clientId,
      timestamp: this.now(),
      knownRevision: this.snapshot.confirmedRevision,
    }));
  }

  private receive(data: unknown): void {
    if (typeof data !== "string") {
      this.protocolFailure("Server sent a non-text WebSocket frame.");
      return;
    }

    try {
      const message = parseServerMessage(data);
      if (message.documentId !== this.snapshot.documentId) {
        throw new RealtimeProtocolError("Server message documentId does not match this room.");
      }
      if ("syncEpoch" in message
        && message.syncEpoch !== undefined
        && message.syncEpoch !== this.snapshot.syncEpoch) {
        this.resyncFailure("EPOCH_MISMATCH", "Server message belongs to a different synchronization epoch.");
        return;
      }

      switch (message.type) {
        case "server.ready":
          this.handleReady(message);
          break;
        case "server.operations":
          this.handleOperations(message);
          break;
        case "server.operation_rejected":
          this.handleOperationRejected(message);
          break;
        case "server.resync_required":
          this.handleResyncRequired(message);
          break;
        case "server.error":
          this.handleServerError(message);
          break;
        case "presence.snapshot":
        case "presence.changed":
        case "cursor.remote":
          // Presence/cursor rendering is intentionally deferred to PRES-001.
          break;
      }
    } catch (error) {
      this.protocolFailure(errorMessage(error, "Malformed realtime server message."));
    }
  }

  private handleReady(message: ServerReadyMessage): void {
    if (this.snapshot.status !== "awaiting-ready") {
      throw new RealtimeProtocolError("server.ready arrived outside the bootstrap state.");
    }
    if (message.payload.revision !== this.snapshot.confirmedRevision) {
      this.resyncFailure(
        "PROTOCOL_ERROR",
        `server.ready revision ${message.payload.revision} does not match caught-up revision ${this.snapshot.confirmedRevision}.`,
      );
      return;
    }
    this.update({
      status: "active",
      connectionId: message.payload.connectionId,
      role: message.payload.role,
      error: null,
    });
    this.flushPendingOperation();
  }

  private handleOperations(message: ServerOperationsMessage): void {
    if (this.snapshot.status !== "awaiting-ready" && this.snapshot.status !== "active") {
      throw new RealtimeProtocolError("server.operations arrived before client.hello or after closure.");
    }

    let confirmedRevision = this.snapshot.confirmedRevision;
    let confirmedContent = this.snapshot.confirmedContent;
    let optimisticContent = this.snapshot.optimisticContent;
    let inFlight = this.snapshot.inFlight;
    let pendingBuffer = [...this.snapshot.pendingBuffer];
    let gapError: CollaborationError | null = null;

    for (const canonical of message.payload.operations) {
      const acknowledgementMatches = matchesInFlight(canonical, inFlight, this.snapshot.clientId);

      if (canonical.revision > confirmedRevision + 1) {
        gapError = {
          category: "resync-required",
          code: "REVISION_GAP",
          message: `Expected canonical revision ${confirmedRevision + 1} but received ${canonical.revision}.`,
          fatal: true,
          reconnectable: false,
          requiresAuthentication: false,
          requiresResync: true,
        };
      } else if (canonical.revision === confirmedRevision + 1) {
        if (acknowledgementMatches) {
          confirmedContent = applyOperation(confirmedContent, canonical.operation);
        } else {
          const remote = identifiedCanonical(canonical);
          const rebased = rebasePendingOperations(remote, inFlight, pendingBuffer);
          confirmedContent = applyOperation(confirmedContent, canonical.operation);
          optimisticContent = applyOperation(
            optimisticContent,
            rebased.remoteForOptimistic.operation,
          );
          inFlight = rebased.inFlight === null
            ? null
            : preservePendingMetadata(rebased.inFlight, inFlight!);
          pendingBuffer = rebased.pendingBuffer.map((operation, index) =>
            preservePendingMetadata(operation, pendingBuffer[index]));
        }
        confirmedRevision = canonical.revision;
      }

      // ACK matching remains independent from duplicate/gap revision processing.
      if (acknowledgementMatches) {
        inFlight = null;
        const promoted = promoteBuffered(pendingBuffer, confirmedRevision);
        inFlight = promoted.inFlight;
        pendingBuffer = promoted.pendingBuffer;
      }

      if (gapError !== null) {
        break;
      }
    }

    this.snapshot = {
      ...this.snapshot,
      confirmedRevision,
      confirmedContent,
      optimisticContent,
      inFlight,
      pendingBuffer,
      ...(gapError === null ? {} : { status: "error", error: gapError }),
    };
    this.emit();
    if (gapError === null) {
      this.flushPendingOperation();
    }
  }

  private handleOperationRejected(message: ServerOperationRejectedMessage): void {
    const matchesCurrent = this.snapshot.inFlight?.clientOperationId
      === message.payload.clientOperationId;
    const error: CollaborationError = {
      category: "operation-rejected",
      code: message.payload.code,
      message: message.payload.message,
      fatal: matchesCurrent,
      reconnectable: false,
      requiresAuthentication: false,
      requiresResync: false,
    };
    if (matchesCurrent) {
      // The local text is intentionally preserved for explicit user recovery.
      this.fail(error);
    } else {
      this.update({ error });
    }
  }

  private handleResyncRequired(message: ServerResyncRequiredMessage): void {
    this.resyncFailure(
      message.payload.reason,
      `The server requires document resynchronization: ${message.payload.reason}.`,
    );
  }

  private handleServerError(message: ServerErrorMessage): void {
    const error: CollaborationError = {
      category: "server",
      code: message.payload.code,
      message: message.payload.message,
      fatal: message.payload.fatal,
      reconnectable: message.payload.code === "INTERNAL_ERROR",
      requiresAuthentication: message.payload.closeCode === 4001,
      requiresResync: false,
    };
    if (message.payload.fatal) {
      this.fail(error);
    } else {
      this.update({ error });
    }
  }

  private flushPendingOperation(): void {
    if (this.snapshot.status !== "active" || this.snapshot.inFlight === null
      || this.snapshot.inFlight.sent) {
      return;
    }

    const operation = this.snapshot.inFlight.operation;
    if (operation.kind === "NO_OP") {
      const promoted = promoteBuffered(
        this.snapshot.pendingBuffer,
        this.snapshot.confirmedRevision,
      );
      this.update({ inFlight: promoted.inFlight, pendingBuffer: promoted.pendingBuffer });
      this.flushPendingOperation();
      return;
    }
    if (operation.kind === "GROUP") {
      this.fail({
        category: "protocol",
        code: "UNSENDABLE_CLIENT_GROUP",
        message: "A rebased local edit became a GROUP, which protocol v1 does not permit clients to send.",
        fatal: true,
        reconnectable: false,
        requiresAuthentication: false,
        requiresResync: true,
      });
      return;
    }

    const inFlight: PendingClientOperation = {
      ...this.snapshot.inFlight,
      baseRevision: this.snapshot.confirmedRevision,
      sent: true,
    };
    this.update({ inFlight });
    this.sendMessage(createClientOperation({
      messageId: this.createUuid(),
      documentId: this.snapshot.documentId,
      syncEpoch: this.snapshot.syncEpoch,
      clientId: this.snapshot.clientId,
      timestamp: this.now(),
      clientOperationId: inFlight.clientOperationId,
      baseRevision: inFlight.baseRevision,
      operation,
    }));
  }

  private sendMessage(message: unknown): void {
    try {
      this.transport?.send(JSON.stringify(message));
    } catch (error) {
      this.fail({
        category: "transport",
        code: "SEND_FAILED",
        message: errorMessage(error, "Unable to send a realtime message."),
        fatal: true,
        reconnectable: true,
        requiresAuthentication: false,
        requiresResync: false,
      });
    }
  }

  private protocolFailure(message: string): void {
    this.fail({
      category: "protocol",
      code: "INVALID_SERVER_MESSAGE",
      message,
      fatal: true,
      reconnectable: false,
      requiresAuthentication: false,
      requiresResync: true,
    });
    this.transport?.close(4000, "Invalid server message");
  }

  private resyncFailure(code: string, message: string): void {
    this.fail({
      category: "resync-required",
      code,
      message,
      fatal: true,
      reconnectable: false,
      requiresAuthentication: false,
      requiresResync: true,
    });
  }

  private handleClose(code: number, reason: string, wasClean: boolean): void {
    this.transport = null;
    // Preserve a structured fatal server/protocol error when its expected close frame follows.
    if (this.snapshot.status === "error" && this.snapshot.error?.fatal) {
      return;
    }
    if (code === 1000) {
      this.update({ status: "closed", connectionId: null, role: null });
      return;
    }
    const retryable = code === 1001 || code === 1006 || code === 1008 || code === 1011;
    this.fail({
      category: "transport",
      code: `WEBSOCKET_CLOSED_${code}`,
      message: reason || `Realtime connection closed${wasClean ? "" : " unexpectedly"} (${code}).`,
      fatal: true,
      reconnectable: retryable,
      requiresAuthentication: code === 4001,
      requiresResync: false,
    });
  }

  private fail(error: CollaborationError): void {
    this.update({ status: "error", error, connectionId: null, role: null });
  }

  private update(changes: Partial<CollaborationSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...changes };
    this.emit();
  }

  private emit(): void {
    this.listeners.forEach((listener) => listener());
  }
}

function identifiedCanonical(canonical: CanonicalOperationItem): IdentifiedOperation {
  return {
    clientId: canonical.clientId,
    clientOperationId: canonical.clientOperationId,
    baseRevision: canonical.revision - 1,
    operation: canonical.operation,
  };
}

function matchesInFlight(
  canonical: CanonicalOperationItem,
  inFlight: PendingClientOperation | null,
  localClientId: string,
): boolean {
  return canonical.clientId === localClientId
    && inFlight !== null
    && canonical.clientOperationId === inFlight.clientOperationId;
}

function preservePendingMetadata(
  operation: IdentifiedOperation,
  previous: PendingClientOperation,
): PendingClientOperation {
  return {
    ...operation,
    baseRevision: previous.baseRevision,
    sent: previous.sent,
  };
}

function promoteBuffered(
  pendingBuffer: PendingClientOperation[],
  confirmedRevision: number,
): { inFlight: PendingClientOperation | null; pendingBuffer: PendingClientOperation[] } {
  if (pendingBuffer.length === 0) {
    return { inFlight: null, pendingBuffer: [] };
  }
  const [next, ...remaining] = pendingBuffer;
  return {
    inFlight: { ...next, baseRevision: confirmedRevision, sent: false },
    pendingBuffer: remaining,
  };
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function errorCode(error: unknown, fallback: string): string {
  if (typeof error === "object" && error !== null && "code" in error
    && typeof error.code === "string") {
    return error.code;
  }
  return fallback;
}

function isAuthenticationFailure(error: unknown): boolean {
  return typeof error === "object" && error !== null
    && (("status" in error && error.status === 401)
      || ("code" in error && error.code === "UNAUTHENTICATED"));
}
